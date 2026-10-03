package com.c6823821.videolinktool

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.WaveReader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline speech to text with sherpa-onnx + Paraformer (much more accurate on
 * Mandarin than the previous Vosk model).
 *
 * Paraformer is a non-streaming model, so feeding it a whole ten minute video in
 * one shot blows up memory and kills the process. The audio is therefore cut at
 * silence into sentence sized pieces, and any piece that is still too long is
 * split at its quietest frame before decoding.
 */
object AsrEngine {
    private const val SAMPLE_RATE = 16000
    private const val HOP = 160 // 10 ms
    private const val WINDOW = 400 // 25 ms
    private const val SENTENCE_SILENCE_MS = 450
    private const val MAX_SEGMENT_MS = 22_000
    private const val MAX_CLAUSE = 30

    private data class Segment(val start: Int, val end: Int, val sentenceEnd: Boolean)

    private val questionTails = listOf("吗", "呢", "吧", "么")
    private val exclaimTails = listOf("啊", "呀", "哇", "啦", "哦", "唉", "哎")
    private val questionWords = listOf(
        "为什么", "什么", "怎么", "哪儿", "哪里", "哪个", "哪些", "多少", "几个", "几点",
        "多久", "多大", "能不能", "是不是", "有没有", "谁",
    )
    private val breakBefore = listOf(
        "但是", "不过", "所以", "因为", "同时", "而且", "并且", "然后", "如果", "虽然",
        "只要", "由于", "因此", "于是", "另外", "以及", "其实", "反正", "而", "结果",
    )
    private val softBreakChars = "了的是在有就都也还又而但并或及与和把被让给对从到中上"

    fun transcribe(
        context: Context,
        wavPath: String,
        onProgress: (Int, String) -> Unit,
    ): String {
        onProgress(1, "准备离线语音模型...")
        val paths = ModelManager.prepare(context, onProgress)

        onProgress(16, "读取音频...")
        val wave = WaveReader.readWave(wavPath)
        if (wave.samples.isEmpty()) throw IllegalStateException("音频是空的")
        if (wave.sampleRate != SAMPLE_RATE) {
            throw IllegalStateException("音频采样率不是 16kHz（实际 " + wave.sampleRate + "），无法识别")
        }

        val segments = splitSegments(wave.samples)
        onProgress(20, "加载语音模型...")
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = paths.model.absolutePath),
                tokens = paths.tokens.absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
        )
        val recognizer = OfflineRecognizer(assetManager = null, config = config)
        val pieces = ArrayList<String>(segments.size)
        try {
            segments.forEachIndexed { index, segment ->
                val chunk = wave.samples.copyOfRange(segment.start, segment.end)
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(chunk, SAMPLE_RATE)
                    recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text.trim()
                    if (text.isNotEmpty()) pieces.add(text)
                } finally {
                    stream.release()
                }
                onProgress(
                    (22 + (index + 1) * 76 / segments.size).coerceIn(22, 98),
                    "正在识别语音 " + ((index + 1) * 100 / segments.size) + "%",
                )
            }
        } finally {
            recognizer.release()
        }

        val result = compose(pieces)
        if (result.isBlank()) throw IllegalStateException("没有识别到文字，可能这段视频里没有说话声")
        onProgress(100, "识别完成")
        return result
    }

    /** Sentence sized slices: cut on real silence, then force-split anything too long. */
    private fun splitSegments(samples: FloatArray): List<Segment> {
        val frameCount = max(1, (samples.size - WINDOW) / HOP + 1)
        val rms = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            val from = frame * HOP
            val to = min(from + WINDOW, samples.size)
            var sum = 0.0
            for (i in from until to) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            rms[frame] = sqrt(sum / max(1, to - from)).toFloat()
        }
        val sorted = rms.clone()
        sorted.sort()
        val noiseFloor = sorted[(sorted.size * 0.1f).toInt().coerceIn(0, sorted.size - 1)]
        val threshold = max(noiseFloor * 4f, 0.004f)

        val cuts = mutableListOf<Int>()
        var index = 0
        while (index < frameCount) {
            if (rms[index] <= threshold) {
                val start = index
                while (index < frameCount && rms[index] <= threshold) index++
                val lengthMs = (index - start) * 10
                if (lengthMs >= SENTENCE_SILENCE_MS && start > 0 && index < frameCount) {
                    cuts.add(((start + index) / 2) * HOP)
                }
            } else {
                index++
            }
        }
        cuts.add(samples.size)

        val segments = mutableListOf<Segment>()
        var from = 0
        cuts.forEach { cut ->
            var start = from
            if (cut - start > 1) {
                // force-split overly long stretches at their quietest frame
                while ((cut - start) > SAMPLE_RATE * MAX_SEGMENT_MS / 1000) {
                    val limit = start + SAMPLE_RATE * 10
                    val wanted = start + SAMPLE_RATE * (MAX_SEGMENT_MS - 3000) / 1000
                    var best = wanted
                    var bestRms = Float.MAX_VALUE
                    var frame = (wanted / HOP)
                    val lastFrame = min((cut - WINDOW) / HOP, frame + SAMPLE_RATE * 3000 / 1000 / HOP)
                    while (frame < lastFrame && frame < rms.size) {
                        if (rms[frame] < bestRms) {
                            bestRms = rms[frame]
                            best = frame * HOP
                        }
                        frame++
                    }
                    if (best <= start + limit) break
                    segments.add(Segment(start, best, false))
                    start = best
                }
            }
            if (cut > start) segments.add(Segment(start, cut, true))
            from = cut
        }
        return segments.filter { it.end - it.start > SAMPLE_RATE / 5 }
    }

    /** Join decoded pieces and give the plain text some punctuation. */
    private fun compose(pieces: List<String>): String {
        val builder = StringBuilder()
        pieces.forEachIndexed { index, piece ->
            val clause = punctuate(piece)
            if (clause.isEmpty()) return@forEachIndexed
            if (index > 0 && builder.isNotEmpty()) {
                builder.append(endingMark(builder.toString()))
            }
            builder.append(clause)
        }
        if (builder.isNotEmpty() && builder.last() !in "。？！，") {
            builder.append(endingMark(builder.toString()))
        }
        return builder.toString()
    }

    /** Rule based commas for a chunk of unpunctuated Mandarin. */
    private fun punctuate(text: String): String {
        if (text.isEmpty()) return text
        val builder = StringBuilder()
        var sinceBreak = 0
        var index = 0
        while (index < text.length) {
            val matched = breakBefore.firstOrNull { text.startsWith(it, index) && sinceBreak >= 6 }
            if (matched != null && builder.isNotEmpty() && builder.last() !in "，。？！") {
                builder.append('，')
                sinceBreak = 0
            }
            val ch = text[index]
            builder.append(ch)
            sinceBreak++
            if (sinceBreak >= MAX_CLAUSE && ch in softBreakChars && index + 1 < text.length) {
                builder.append('，')
                sinceBreak = 0
            }
            index++
        }
        return builder.toString().trim().trim(',', '，')
    }

    private fun endingMark(sentence: String): Char {
        val trimmed = sentence.trimEnd('，')
        if (trimmed.isEmpty()) return '。'
        val tail = trimmed.takeLast(8)
        if (questionTails.any { trimmed.endsWith(it) } || questionWords.any { tail.contains(it) }) return '？'
        if (exclaimTails.any { trimmed.endsWith(it) } ||
            (tail.contains("太") && trimmed.endsWith("了"))
        ) return '！'
        return '。'
    }
}