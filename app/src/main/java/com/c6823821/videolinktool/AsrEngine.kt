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
 */
object AsrEngine {
    fun transcribe(
        context: Context,
        wavPath: String,
        onProgress: (Int, String) -> Unit,
    ): String {
        onProgress(2, "初始化离线语音模型...")
        val model = ModelManager.load(context, onProgress)
        onProgress(28, "正在识别语音 0%")

        val recognizer = Recognizer(model, 16000.0f)
        val text: String
        try {
            text = FileInputStream(wavPath).use { raw ->
                BufferedInputStream(raw, 1 shl 16).use { input ->
                    val dataSize = skipToWavData(input)
                    recognize(recognizer, input, dataSize, onProgress)
                }
            }
        } finally {
            runCatching { recognizer.close() }
        }

        val result = text.trim()
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
        onProgress: (Int, String) -> Unit,
    ): String {
        val builder = StringBuilder()
        val buffer = ByteArray(1 shl 13)
        var readTotal = 0L
        var lastPercent = -1
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            readTotal += read
            if (recognizer.acceptWaveForm(buffer, read)) {
                appendResult(builder, recognizer.result)
            }
            if (dataSize > 0) {
                val percent = (readTotal * 100 / dataSize).toInt().coerceIn(0, 99)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(30 + percent * 68 / 100, "正在识别语音 " + percent + "%")
                }
            }
        }
        appendResult(builder, recognizer.finalResult)
        return builder.toString()
    }

    private fun appendResult(builder: StringBuilder, json: String?) {
        if (json.isNullOrBlank()) return
        val text = runCatching { JSONObject(json).optString("text", "") }.getOrDefault("")
        if (text.isNotBlank()) {
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(text.trim())
        }
    }

    /** Moves [input] to the first byte of the WAV "data" payload and returns its size. */
    private fun skipToWavData(input: InputStream): Long {
        val header = ByteArray(12)
        if (!readFully(input, header)) return -1L
        var position = 12L
        val chunkHeader = ByteArray(8)
        while (readFully(input, chunkHeader)) {
            position += 8
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = (chunkHeader[4].toLong() and 0xFF) or
                ((chunkHeader[5].toLong() and 0xFF) shl 8) or
                ((chunkHeader[6].toLong() and 0xFF) shl 16) or
                ((chunkHeader[7].toLong() and 0xFF) shl 24)
            if (id == "data") return if (size <= 0L) -1L else size
            val skip = size + (size and 1L)
            if (skip <= 0L) return -1L
            if (!skipBytes(input, skip)) return -1L
            position += skip
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
