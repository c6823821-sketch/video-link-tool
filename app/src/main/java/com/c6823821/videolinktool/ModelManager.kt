package com.c6823821.videolinktool

import android.content.Context
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

object ModelManager {
    private const val MODEL_URL =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
    private const val TOKENS_URL =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"

    data class Paths(val model: File, val tokens: File)

    fun prepare(context: Context, onProgress: (Int, String) -> Unit): Paths {
        val dir = File(context.filesDir, "sensevoice").apply { mkdirs() }
        val model = File(dir, "model.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        downloadResume(MODEL_URL, model, 190_000_000L, 0, 88, onProgress, "首次下载语音模型")
        downloadResume(TOKENS_URL, tokens, 100_000L, 88, 100, onProgress, "下载模型词表")
        return Paths(model, tokens)
    }

    private fun downloadResume(
        url: String,
        target: File,
        minBytes: Long,
        progressStart: Int,
        progressEnd: Int,
        onProgress: (Int, String) -> Unit,
        label: String,
    ) {
        if (target.exists() && target.length() >= minBytes) return
        val existing = if (target.exists()) target.length() else 0L
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClient.MOBILE_UA)
        if (existing > 0) builder.header("Range", "bytes=" + existing + "-")
        HttpClient.client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException(label + "失败：HTTP " + response.code)
            val body = response.body ?: throw IllegalStateException(label + "响应为空")
            val append = existing > 0 && response.code == 206
            val contentLength = body.contentLength().coerceAtLeast(0L)
            val total = when {
                response.code == 206 -> Regex("/(\\d+)$").find(response.header("Content-Range").orEmpty())
                    ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: (existing + contentLength)
                contentLength > 0 -> contentLength
                else -> 1L
            }
            var done = if (append) existing else 0L
            var last = -1
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                BufferedOutputStream(FileOutputStream(target, append)).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        val p = progressStart + (((done * (progressEnd - progressStart)) / total.coerceAtLeast(1L)).toInt())
                        if (p != last) {
                            last = p
                            onProgress(p.coerceIn(progressStart, progressEnd), label)
                        }
                    }
                }
            }
        }
        if (!target.exists() || target.length() < minBytes) {
            throw IllegalStateException(label + "失败：文件不完整")
        }
    }
}
