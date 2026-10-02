package com.c6823821.videolinktool

import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

object Downloader {
    fun download(
        url: String,
        headers: Map<String, String>,
        output: File,
        onProgress: (Int) -> Unit,
    ) {
        output.parentFile?.mkdirs()
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClient.MOBILE_UA)
        headers.forEach { (key, value) -> builder.header(key, value) }
        HttpClient.client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败：HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("下载响应为空")
            val total = body.contentLength().coerceAtLeast(1L)
            var readTotal = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                BufferedOutputStream(FileOutputStream(output)).use { outputStream ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        outputStream.write(buffer, 0, count)
                        readTotal += count
                        val percent = ((readTotal * 100) / total).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
        if (!output.exists() || output.length() < 1024) {
            throw IllegalStateException("下载结果不完整")
        }
    }
}
