package com.c6823821.videolinktool

import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets a running task be stopped for real. Each function has its own slot, so
 * cancelling 下视频 does not touch a 转文字 job running beside it.
 *
 * Cancelling the coroutine alone is not enough: yt-dlp runs as a native child
 * process and the HTTP downloader keeps reading bytes.
 */
object TaskControl {
    private val cancelled = ConcurrentHashMap<TaskMode, Boolean>()
    private val processIds = ConcurrentHashMap<TaskMode, String>()

    fun begin(mode: TaskMode, processId: String) {
        cancelled[mode] = false
        processIds[mode] = processId
    }

    fun cancel(mode: TaskMode) {
        cancelled[mode] = true
        processIds.remove(mode)?.let { id ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        }
    }

    fun isCancelled(mode: TaskMode): Boolean = cancelled[mode] == true

    fun processId(mode: TaskMode): String? = processIds[mode]

    fun finish(mode: TaskMode, processId: String) {
        if (processIds[mode] == processId) processIds.remove(mode)
    }
}