package com.c6823821.videolinktool

import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP downloader.
 *
 * Big files are fetched over several range requests at once. A single connection to
 * a video CDN is often throttled per connection, so four connections usually finish
 * a Douyin video several times faster than one. If the server refuses ranges, or a
 * part fails, it falls back to a plain single stream download.
 */
object Downloader {
    private const val PARALLEL_MIN_BYTES = 3L * 1024 * 1024
    private const val MAX_PARTS = 4

    fun download(
        url: String,
        headers: Map<String, String>,
        output: File,
        mode: TaskMode? = null,
        onProgress: (Int) -> Unit,
    ) {
        output.parentFile?.mkdirs()
        val total = probeLength(url, headers)
        if (total >= PARALLEL_MIN_BYTES) {
            val finished = runCatching { parallel(url, headers, output, mode, total, onProgress) }
                .getOrDefault(false)
            if (finished) return
        }
        single(url, headers, output, mode, onProgress)
    }

    private fun builder(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url).header("User-Agent", HttpClient.MOBILE_UA)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return builder
    }

    private fun probeLength(url: String, headers: Map<String, String>): Long = runCatching {
        val request = builder(url, headers).header("Range", "bytes=0-0").get().build()
        HttpClient.client.newCall(request).execute().use { response ->
            if (response.code == 206) {
                response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
            } else {
                -1L
            }
        }
    }.getOrDefault(-1L)

    private fun parallel(
        url: String,
        headers: Map<String, String>,
        output: File,
        mode: TaskMode?,
        total: Long,
        onProgress: (Int) -> Unit,
    ): Boolean {
        val parts = minOf(MAX_PARTS, (total / (1024 * 1024)).toInt().coerceAtLeast(1))
        if (parts <= 1) return false
        val chunk = total / parts
        val executor = Executors.newFixedThreadPool(parts)
        val pieces = ArrayList<File>(parts)
        val counter = AtomicLong(0)
        try {
            val futures = (0 until parts).map { index ->
                val start = index * chunk
                val end = if (index == parts - 1) total - 1 else start + chunk - 1
                val piece = File(output.parentFile, output.name + ".part" + index)
                pieces.add(piece)
                executor.submit {
                    fetchRange(url, headers, piece, start, end, mode) { added ->
                        val done = counter.addAndGet(added)
                        onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            futures.forEach { it.get() }
        } catch (_: Exception) {
            pieces.forEach { it.delete() }
            return false
        } finally {
            executor.shutdownNow()
        }

        if (output.exists()) output.delete()
        BufferedOutputStream(FileOutputStream(output), 1 shl 20).use { target ->
            pieces.forEach { piece ->
                piece.inputStream().use { input -> input.copyTo(target, 1 shl 20) }
            }
        }
        pieces.forEach { it.delete() }
        if (output.length() != total) {
            output.delete()
            return false
        }
        onProgress(100)
        return true
    }

    private fun fetchRange(
        url: String,
        headers: Map<String, String>,
        target: File,
        start: Long,
        end: Long,
        mode: TaskMode?,
        onBytes: (Long) -> Unit,
    ) {
        val request = builder(url, headers).header("Range", "bytes=" + start + "-" + end).get().build()
        HttpClient.client.newCall(request).execute().use { response ->
            if (response.code != 206) throw IllegalStateException("服务器不支持分段下载")
            val body = response.body ?: throw IllegalStateException("下载响应为空")
            RandomAccessFile(target, "rw").use { out ->
                out.setLength(0)
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (mode != null && TaskControl.isCancelled(mode)) throw IllegalStateException("任务已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        onBytes(count.toLong())
                    }
                }
            }
        }
    }

    private fun single(
        url: String,
        headers: Map<String, String>,
        output: File,
        mode: TaskMode?,
        onProgress: (Int) -> Unit,
    ) {
        HttpClient.client.newCall(builder(url, headers).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败：HTTP " + response.code)
            }
            val body = response.body ?: throw IllegalStateException("下载响应为空")
            val total = body.contentLength().coerceAtLeast(1L)
            var readTotal = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                BufferedOutputStream(FileOutputStream(output)).use { outputStream ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (mode != null && TaskControl.isCancelled(mode)) throw IllegalStateException("任务已取消")
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