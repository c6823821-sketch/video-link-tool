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
            is ResolvedSource.Direct -> runDirect(context, mode, rawInput, source.media, onProgress)
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
            detail = "已保存到系统下载目录：" + saved.displayName,
            outputUri = saved.uri?.toString() ?: saved.path,
            outputPath = saved.path,
            previewUri = saved.uri?.toString() ?: saved.path,
        )
    }

    /** Prefer the cloud engine; fall back to the bundled offline model. */
    private fun transcribeText(
        context: Context,
        wavPath: String,
        onProgress: (Int, String) -> Unit,
    ): String {
        val cloud = runCatching {
            val segments = CloudAsr.transcribe(wavPath) { p, detail ->
                onProgress(70 + (p * 8) / 100, detail)
            }
            if (segments.isEmpty()) "" else CloudAsr.compose(segments)
        }.getOrNull()
        if (!cloud.isNullOrBlank()) {
            onProgress(100, "识别完成")
            return cloud
        }
        onProgress(78, "正在识别语音...")
        return AsrEngine.transcribe(context, wavPath) { p, detail ->
            onProgress(78 + (p * 22) / 100, detail)
        }
    }


    /** Transcription is shown in the app itself; nothing is written to Downloads. */
    private fun completeText(text: String) {
        TaskBus.update(
            mode = TaskMode.TEXT,
            stateValue = RunState.SUCCESS,
            progress = 100,
            title = "识别完成",
            detail = "文字在下方框里，可长按选择或点“复制文字”。",
            text = text,
        )
    }
    private fun completeGallery(saved: List<OutputStore.Saved>) {
        if (saved.isEmpty()) throw IllegalStateException("图集没有保存成功")
        TaskBus.update(
            mode = TaskMode.VIDEO,
            stateValue = RunState.SUCCESS,
            progress = 100,
            title = "图集已保存",
            detail = "已保存 " + saved.size + " 张图片到系统下载目录",
            outputUri = saved.first().uri?.toString() ?: saved.first().path,
            outputPath = saved.first().path,
        )
    }

    private fun runDirect(
        context: Context,
        mode: TaskMode,
        rawInput: String,
        media: DirectMedia,
        onProgress: (Int, String) -> Unit,
    ) {
        val originalUrl = LinkExtractor.extract(rawInput).orEmpty();
        if (media.images.isNotEmpty()) {
            if (mode != TaskMode.VIDEO) {
                throw IllegalStateException("这是图集，没有音频。请选择“下视频”来保存图片。")
            }
            downloadGallery(context, media, onProgress)
            return
        }

        when (mode) {
            TaskMode.VIDEO -> {
                if (media.url.isBlank()) throw IllegalStateException("没有找到可下载的视频地址")
                val temp = File(context.cacheDir, "video_link_tool_" + System.currentTimeMillis() + "." + media.ext)
                
                Downloader.download(media.url, media.headers, temp, mode) { percent ->
                    onProgress(percent, "正在下载无水印视频...")
                }
                MediaCache.save(context, originalUrl, temp)
                val saved = OutputStore.saveFile(context, temp, media.title, "video/mp4")
                complete(mode, saved)
            }
            TaskMode.AUDIO -> {
                if (media.url.isBlank()) throw IllegalStateException("没有找到可提取的音频")
                val cached = MediaCache.get(context, originalUrl)
                val result = YoutubeDlEngine.download(
                    context = context,
                    url = cached?.toURI()?.toString() ?: media.url,
                    mode = TaskMode.AUDIO,
                    headers = if (cached == null) media.headers else emptyMap(),
                    titleHint = media.title,
                    onProgress = onProgress,
                )
                val saved = OutputStore.saveFile(context, result.file, result.title, "audio/mp4")
                complete(mode, saved)
            }
            TaskMode.TEXT -> {
                if (media.url.isBlank()) throw IllegalStateException("没有找到可转写的音频")
                val cached = MediaCache.get(context, originalUrl)
                val result = YoutubeDlEngine.download(
                    context = context,
                    url = cached?.toURI()?.toString() ?: media.url,
                    mode = TaskMode.TEXT,
                    headers = if (cached == null) media.headers else emptyMap(),
                    titleHint = media.title,
                    onProgress = { p, detail -> onProgress((p * 70) / 100, detail) },
                )
            val text = transcribeText(context, result.file.absolutePath, onProgress)
                completeText(text)
            }
        }
    }

    private fun downloadGallery(
        context: Context,
        media: DirectMedia,
        onProgress: (Int, String) -> Unit,
    ) {
        val tempFiles = mutableListOf<File>()
        media.images.forEachIndexed { index, item ->
            val temp = File(context.cacheDir, "video_link_tool_" + System.currentTimeMillis() + "_" + index + "." + item.ext)
            Downloader.download(item.url, media.headers, temp, TaskMode.VIDEO) { p ->
                val overall = ((index * 100 + p) / media.images.size).coerceIn(0, 100)
                onProgress(overall, "正在保存图集 " + (index + 1) + "/" + media.images.size + "...")
            }
            tempFiles += temp
        }
        val saved = OutputStore.saveGallery(context, tempFiles, media.title)
        completeGallery(saved)
    }

    private fun runYoutubeDl(
        context: Context,
        mode: TaskMode,
        rawInput: String,
        source: ResolvedSource.YoutubeDl,
        onProgress: (Int, String) -> Unit,
    ) {
        val url = LinkExtractor.extract(rawInput) ?: throw IllegalArgumentException("没有识别到链接")
        val cached = if (mode != TaskMode.VIDEO) MediaCache.get(context, url) else null
        val result = YoutubeDlEngine.download(
            context = context,
            url = cached?.toURI()?.toString() ?: url,
            mode = mode,
            titleHint = source.titleHint,
            onProgress = { p, text ->
                if (mode == TaskMode.TEXT) onProgress((p * 70) / 100, text) else onProgress(p, text)
            },
        )
        if (mode == TaskMode.VIDEO) MediaCache.save(context, url, result.file)
        if (mode == TaskMode.TEXT) {
            val text = transcribeText(context, result.file.absolutePath, onProgress)
            completeText(text)
        } else {
            val mime = if (mode == TaskMode.VIDEO) "video/mp4" else "audio/mp4"
            val saved = OutputStore.saveFile(context, result.file, result.title, mime)
            complete(mode, saved)
        }
    }
}