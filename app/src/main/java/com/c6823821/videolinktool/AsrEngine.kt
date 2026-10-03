package com.c6823821.videolinktool

import android.content.Context
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream

/**
 * Offline speech to text with Vosk.
 *
 * Reads the WAV produced by ffmpeg, locates the "data" chunk (ffmpeg may write a
 * LIST chunk before it, so the payload does not always start at byte 44) and feeds
 * the raw 16 kHz mono PCM into the recognizer on this thread.
 *
 * The Chinese model returns bare words with timestamps but no punctuation, so we
 * rebuild sentences from the pauses between words and pick an ending mark.
 */
object AsrEngine {
    private data class Word(val text: String, val start: Double, val end: Double)

    private const val SENTENCE_GAP = 0.42
    private const val CLAUSE_GAP = 0.15
    private const val MIN_CLAUSE = 4
    private const val MAX_CLAUSE = 40

    fun transcribe(
        context: Context,
        wavPath: String,
        onProgress: (Int, String) -> Unit,
    ): String {
        onProgress(2, "初始化离线语音模型...")
        val model = ModelManager.load(context, onProgress)
        onProgress(28, "正在识别语音 0%")

        val recognizer = Recognizer(model, 16000.0f)
        recognizer.setWords(true)
        val words = mutableListOf<Word>()
        var fallbackText = ""
        try {
            FileInputStream(wavPath).use { raw ->
                BufferedInputStream(raw, 1 shl 16).use { input ->
                    val dataSize = skipToWavData(input)
                    recognize(recognizer, input, dataSize, words, onProgress) { plain ->
                        if (plain.isNotBlank()) fallbackText = plain
                    }
                }
            }
        } finally {
            runCatching { recognizer.close() }
        }

        val result = if (words.isNotEmpty()) punctuate(words) else fallbackText.trim()
        if (result.isBlank()) {
            throw IllegalStateException("没有识别到文字，可能这段视频里没有说话声")
        }
        onProgress(100, "识别完成")
        return result
    }

    private fun recognize(
        recognizer: Recognizer,
        input: InputStream,
        dataSize: Long,
        words: MutableList<Word>,
        onProgress: (Int, String) -> Unit,
        onPlain: (String) -> Unit,
    ) {
        val buffer = ByteArray(1 shl 13)
        var readTotal = 0L
        var lastPercent = -1
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            readTotal += read
            if (recognizer.acceptWaveForm(buffer, read)) {
                collect(recognizer.result, words, onPlain)
            }
            if (dataSize > 0) {
                val percent = (readTotal * 100 / dataSize).toInt().coerceIn(0, 99)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(30 + percent * 68 / 100, "正在识别语音 " + percent + "%")
                }
            }
        }
        collect(recognizer.finalResult, words, onPlain)
    }

    private fun collect(json: String?, words: MutableList<Word>, onPlain: (String) -> Unit) {
        if (json.isNullOrBlank()) return
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        obj.optString("text", "").takeIf { it.isNotBlank() }?.let(onPlain)
        val array = obj.optJSONArray("result") ?: return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val word = item.optString("word").trim()
            if (word.isEmpty()) continue
            words.add(
                Word(
                    text = word,
                    start = item.optDouble("start", -1.0),
                    end = item.optDouble("end", -1.0),
                )
            )
        }
    }

    private val questionTails = listOf("吗", "呢", "吧", "么")
    private val exclaimTails = listOf("啊", "呀", "哇", "啦", "哦", "唉", "哎")
    private val questionWords = listOf(
        "为什么", "什么", "怎么", "哪儿", "哪里", "哪个", "哪些", "多少", "几个", "几点",
        "多久", "多大", "能不能", "是不是", "有没有", "谁",
    )
    private val clauseStarters = listOf(
        "但是", "不过", "所以", "因为", "同时", "并且", "然后", "如果", "虽然", "只要",
        "由于", "因此", "于是", "另外", "以及", "而且", "相关", "目前", "现在", "长期",
        "最后", "而",
    )

    /**
     * The Chinese model emits bare words with per-word timestamps and no punctuation at all.
     * We rebuild sentences from the silence between words: long gaps end a sentence, medium
     * gaps add a comma. Continuous narration has almost no silence, so a long clause also
     * gets a comma as soon as a clause-opening word shows up.
     */
    private fun punctuate(words: List<Word>): String {
        val output = StringBuilder()
        val current = StringBuilder()
        var previousEnd = words.first().end
        words.forEachIndexed { index, word ->
            if (index > 0) {
                val gap = if (word.start >= 0 && previousEnd >= 0) word.start - previousEnd else 0.0
                if (gap >= SENTENCE_GAP && current.isNotEmpty()) {
                    output.append(current).append(endingMark(current.toString()))
                    current.setLength(0)
                } else if (gap >= CLAUSE_GAP && pendingLength(current) >= MIN_CLAUSE) {
                    val mark = clauseMark(current.toString())
                    if (mark == '，') {
                        current.append('，')
                    } else {
                        output.append(current).append(mark)
                        current.setLength(0)
                    }
                }
            }
            if (pendingLength(current) >= MAX_CLAUSE &&
                !endsWithPunctuation(current) &&
                clauseStarters.any { word.text.startsWith(it) }
            ) {
                current.append('，')
            }
            appendWord(current, word.text)
            if (word.end >= 0) previousEnd = word.end
        }
        if (current.isNotEmpty()) {
            output.append(current).append(endingMark(current.toString()))
        }
        return output.toString()
    }

    private fun clauseMark(sentence: String): Char = when {
        endsQuestion(sentence) -> '？'
        endsExclaim(sentence) -> '！'
        else -> '，'
    }

    private fun endingMark(sentence: String): Char {
        val tail = sentence.takeLast(8)
        if (endsQuestion(sentence) || questionWords.any { tail.contains(it) }) return '？'
        if (endsExclaim(sentence)) return '！'
        return '。'
    }

    private fun endsQuestion(sentence: String): Boolean =
        questionTails.any { sentence.endsWith(it) }

    private fun endsExclaim(sentence: String): Boolean =
        exclaimTails.any { sentence.endsWith(it) } ||
            (sentence.takeLast(8).contains("太") && sentence.endsWith("了"))

    private fun endsWithPunctuation(builder: StringBuilder): Boolean =
        builder.isNotEmpty() && builder.last() in "。？！，"

    /** Characters typed since the last punctuation mark. */
    private fun pendingLength(builder: StringBuilder): Int {
        for (index in builder.length - 1 downTo 0) {
            if (builder[index] in "。？！，") return builder.length - 1 - index
        }
        return builder.length
    }
    private fun appendWord(builder: StringBuilder, word: String) {
        if (builder.isEmpty()) {
            builder.append(word)
            return
        }
        val previous = builder.last()
        val next = word.first()
        if (previous.code < 128 && next.code < 128 && !previous.isWhitespace()) {
            builder.append(' ')
        }
        builder.append(word)
    }
    /** Moves [input] to the first byte of the WAV "data" payload and returns its size. */
    private fun skipToWavData(input: InputStream): Long {
        val header = ByteArray(12)
        if (!readFully(input, header)) return -1L
        val chunkHeader = ByteArray(8)
        while (readFully(input, chunkHeader)) {
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = (chunkHeader[4].toLong() and 0xFF) or
                ((chunkHeader[5].toLong() and 0xFF) shl 8) or
                ((chunkHeader[6].toLong() and 0xFF) shl 16) or
                ((chunkHeader[7].toLong() and 0xFF) shl 24)
            if (id == "data") return if (size <= 0L) -1L else size
            val skip = size + (size and 1L)
            if (skip <= 0L) return -1L
            if (!skipBytes(input, skip)) return -1L
        }
        return -1L
    }

    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun skipBytes(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() < 0) {
                return false
            } else {
                remaining -= 1
            }
        }
        return true
    }
}