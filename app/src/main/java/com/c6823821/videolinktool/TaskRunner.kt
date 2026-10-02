package com.c6823821.videolinktool

import android.content.Context
import java.io.File

object TaskRunner {
    fun run(
        context: Context,
        mode: TaskMode,
        rawInput: String,
        onProgress: (Int, String) -> Unit,
    ) {
        val source = MediaResolver.resolve(context, rawInput)
        when (source) {
            is ResolvedSource.Direct -> runDirect(context, mode, source.media, onProgress)
            is ResolvedSource.YoutubeDl -> runYoutubeDl(context, mode, rawInput, source, onProgress)
        }
    }

    fun complete(mode: TaskMode, saved: OutputStore.Saved) {
        val label = when (mode) {
            TaskMode.VIDEO -> "视频已保存"
            TaskMode.AUDIO -> "音频已保存"
            TaskMode.TEXT -> "文字已保存"
        }
        TaskBus.update(
            mode = mode,
            stateValue = RunState.SUCCESS,
            progress = 100,
            title = label,
            detail = "已保存到：下载/视频工具箱/" + saved.displayName,
            outputUri = saved.uri?.toString() ?: saved.path,
            outputPath = saved.path,
        )
    }

    private fun runDirect(
        context: Context,
        mode: TaskMode,
        media: DirectMedia,
        onProgress: (Int, String) -> Unit,
    ) {
        when (mode) {
            TaskMode.VIDEO -> {
                val temp = File(context.cacheDir, "video_link_tool_" + System.currentTimeMillis() + "." + media.ext)
                Downloader.download(media.url, media.headers, temp) { percent ->
                    onProgress(percent, "正在下载无水印视频...")
                }
                val saved = OutputStore.saveFile(context, temp, media.title, "video/mp4")
                complete(mode, saved)
            }
            TaskMode.AUDIO -> {
                val result = YoutubeDlEngine.download(
                    context = context,
                    url = media.url,
                    mode = TaskMode.AUDIO,
                    headers = media.headers,
                    titleHint = media.title,
                    onProgress = onProgress,
                )
                val saved = OutputStore.saveFile(context, result.file, result.title, "audio/mp4")
                complete(mode, saved)
            }
            TaskMode.TEXT -> {
                val result = YoutubeDlEngine.download(
                    context = context,
                    url = media.url,
                    mode = TaskMode.TEXT,
                    headers = media.headers,
                    titleHint = media.title,
                    onProgress = { p, text -> onProgress((p * 75) / 100, text) },
                )
                val text = AsrEngine.transcribe(context, result.file.absolutePath) { p, text ->
                    onProgress(75 + (p * 25) / 100, text)
                }
                val saved = OutputStore.saveText(context, text, result.title)
                complete(mode, saved)
            }
        }
    }

    private fun runYoutubeDl(
        context: Context,
        mode: TaskMode,
        rawInput: String,
        source: ResolvedSource.YoutubeDl,
        onProgress: (Int, String) -> Unit,
    ) {
        val url = LinkExtractor.extract(rawInput) ?: throw IllegalArgumentException("没有识别到链接")
        val host = LinkExtractor.host(url)
        val cookieFile = if (host.contains("douyin") || host.contains("iesdouyin")) {
            CookieStore.file(context).absolutePath
        } else {
            null
        }
        val result = YoutubeDlEngine.download(
            context = context,
            url = url,
            mode = mode,
            titleHint = source.titleHint,
            cookieFile = cookieFile,
            onProgress = { p, text ->
                if (mode == TaskMode.TEXT) onProgress((p * 75) / 100, text) else onProgress(p, text)
            },
        )
        if (mode == TaskMode.TEXT) {
            val text = AsrEngine.transcribe(context, result.file.absolutePath) { p, text ->
                onProgress(75 + (p * 25) / 100, text)
            }
            val saved = OutputStore.saveText(context, text, result.title)
            complete(mode, saved)
        } else {
            val mime = if (mode == TaskMode.VIDEO) "video/mp4" else "audio/mp4"
            val saved = OutputStore.saveFile(context, result.file, result.title, mime)
            complete(mode, saved)
        }
    }
}
