package com.c6823821.videolinktool

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

object YoutubeDlEngine {
    @Volatile
    private var initialized = false

    data class Result(val file: File, val title: String)

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                initialized = true
            }
        }
    }

    fun download(
        context: Context,
        url: String,
        mode: TaskMode,
        headers: Map<String, String> = emptyMap(),
        titleHint: String? = null,
        cookieFile: String? = null,
        onProgress: (Int, String) -> Unit,
    ): Result {
        init(context)
        val work = File(context.cacheDir, "video_link_tool_" + System.currentTimeMillis())
        work.mkdirs()
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--newline")
        request.addOption("--no-warnings")
        request.addOption("--retries", "5")
        request.addOption("--fragment-retries", "5")
        request.addOption("--socket-timeout", "30")
        request.addOption("-o", File(work, "%(id)s.%(ext)s").absolutePath)
        if (!cookieFile.isNullOrBlank()) {
            request.addOption("--cookies", cookieFile)
        }
        headers.forEach { (key, value) -> request.addOption("--add-header", "$key: $value") }

        when (mode) {
            TaskMode.VIDEO -> {
                request.addOption("-f", "bv*[vcodec^=avc1][height<=1080]+ba/b[ext=mp4]/b")
                request.addOption("--format-sort", "res:1080,br")
                request.addOption("--merge-output-format", "mp4")
            }
            TaskMode.AUDIO -> {
                request.addOption("-f", "ba/b")
                request.addOption("-x")
                request.addOption("--audio-format", "m4a")
                request.addOption("--audio-quality", "0")
            }
            TaskMode.TEXT -> {
                request.addOption("-f", "ba/b")
                request.addOption("-x")
                request.addOption("--audio-format", "wav")
                request.addOption("--postprocessor-args", "ffmpeg:-ac 1 -ar 16000")
            }
        }

        val info = runCatching {
            val infoRequest = YoutubeDLRequest(url)
            infoRequest.addOption("--no-playlist")
            infoRequest.addOption("--no-warnings")
            if (!cookieFile.isNullOrBlank()) infoRequest.addOption("--cookies", cookieFile)
            headers.forEach { (key, value) -> infoRequest.addOption("--add-header", "$key: $value") }
            YoutubeDL.getInstance().getInfo(infoRequest)
        }.getOrNull()

        YoutubeDL.getInstance().execute(
            request,
            processId = null,
            redirectErrorStream = true,
        ) { progress, _, _ ->
            if (progress >= 0f) {
                onProgress(progress.toInt().coerceIn(0, 100), "下载/处理中...")
            }
        }

        val allowed = when (mode) {
            TaskMode.VIDEO -> setOf("mp4", "mkv", "webm", "mov")
            TaskMode.AUDIO -> setOf("m4a", "mp4", "aac", "opus")
            TaskMode.TEXT -> setOf("wav")
        }
        val file = work.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in allowed && !it.name.endsWith(".part") }
            .maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("下载完成但没有找到输出文件")

        val title = titleHint?.takeIf { it.isNotBlank() }
            ?: info?.title?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
        return Result(file, title)
    }
}
