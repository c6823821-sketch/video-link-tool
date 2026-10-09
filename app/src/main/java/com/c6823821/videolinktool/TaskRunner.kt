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

    private fun completeGallery(saved: List<OutputStore.Saved>, assetCount: Int, audioFailed: Boolean = false) {
        if (saved.isEmpty()) throw IllegalStateException("图集没有保存成功")
        TaskBus.update(
            mode = TaskMode.VIDEO,
            stateValue = RunState.SUCCESS,
            progress = 100,
            title = "图集已保存",
            detail = "已保存 " + assetCount + " 个文件到系统下载目录" + if (audioFailed) "（音频下载失败）" else "",
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
        val originalUrl = LinkExtractor.extract(rawInput).orEmpty()
        if (media.images.isNotEmpty() && mode == TaskMode.VIDEO) {
            downloadGallery(context, media, onProgress)
            return
        }

        val sourceUrl = if (media.images.isNotEmpty()) {
            media.audioUrl.ifBlank { media.url }
        } else {
            media.url
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
                if (sourceUrl.isBlank()) throw IllegalStateException("没有找到可提取的音频")
                val cached = if (media.images.isEmpty()) MediaCache.get(context, originalUrl) else null
                val result = YoutubeDlEngine.download(
                    context = context,
                    url = cached?.toURI()?.toString() ?: sourceUrl,
                    mode = TaskMode.AUDIO,
                    headers = if (cached == null) media.headers else emptyMap(),
                    titleHint = media.title,
                    onProgress = onProgress,
                )
                val saved = OutputStore.saveFile(context, result.file, result.title, "audio/mp4")
                complete(mode, saved)
            }
            TaskMode.TEXT -> {
                if (sourceUrl.isBlank()) throw IllegalStateException("没有找到可转写的音频")
                val cached = if (media.images.isEmpty()) MediaCache.get(context, originalUrl) else null
                val result = YoutubeDlEngine.download(
                    context = context,
                    url = cached?.toURI()?.toString() ?: sourceUrl,
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
        val totalAssets = media.images.sumOf {
            (if (it.url.isNotBlank()) 1 else 0) + (if (it.liveUrl.isNotBlank()) 1 else 0)
        } + if (media.audioUrl.isNotBlank()) 1 else 0
        val exportFiles = mutableListOf<OutputStore.GalleryFile>()
        var finishedAssets = 0
        var audioFailed = false

        fun report(percent: Int, label: String) {
            val overall = if (totalAssets <= 0) 100 else ((finishedAssets * 100 + percent) / totalAssets).coerceIn(0, 100)
            onProgress(overall, label)
        }

        media.images.forEachIndexed { index, item ->
            val baseName = (index + 1).toString().padStart(2, '0')
            if (item.url.isNotBlank()) {
                val temp = File(context.cacheDir, "video_link_tool_${System.currentTimeMillis()}_${baseName}.${item.ext}")
                Downloader.download(item.url, media.headers, temp, TaskMode.VIDEO) { p ->
                    report(p, "正在保存图集 " + (index + 1) + "/" + media.images.size + "...")
                }
                exportFiles += OutputStore.GalleryFile(baseName + "." + item.ext, temp)
                finishedAssets++
            }
            if (item.liveUrl.isNotBlank()) {
                val temp = File(context.cacheDir, "video_link_tool_${System.currentTimeMillis()}_${baseName}_live.${item.liveExt}")
                Downloader.download(item.liveUrl, media.headers, temp, TaskMode.VIDEO) { p ->
                    report(p, "正在保存实况 " + (index + 1) + "/" + media.images.size + "...")
                }
                exportFiles += OutputStore.GalleryFile(baseName + "_live." + item.liveExt, temp)
                finishedAssets++
            }
        }

        if (media.audioUrl.isNotBlank()) {
            try {
                val temp = File(context.cacheDir, "video_link_tool_${System.currentTimeMillis()}_background_audio.${media.audioExt}")
                Downloader.download(media.audioUrl, media.headers, temp, TaskMode.VIDEO) { p ->
                    report(p, "正在保存图集音频...")
                }
                exportFiles += OutputStore.GalleryFile("background_audio." + media.audioExt, temp)
            } catch (_: Exception) {
                audioFailed = true
            } finally {
                finishedAssets++
            }
        }

        val saved = OutputStore.saveGallery(context, exportFiles, media.title)
        completeGallery(saved, exportFiles.size, audioFailed)
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
