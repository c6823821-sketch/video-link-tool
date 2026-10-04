package com.c6823821.videolinktool

import com.yausername.youtubedl_android.YoutubeDL

/**
 * Lets a running task be stopped for real.
 *
 * Cancelling the coroutine is not enough: yt-dlp runs as a native child process
 * and the HTTP downloader keeps reading bytes. Both have to be told to stop, and
 * the killed task must stop pushing progress so it cannot overwrite the next one.
 */
object TaskControl {
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    var processId: String? = null
        private set

    fun begin(processId: String) {
        cancelled = false
        this.processId = processId
    }

    fun cancel() {
        cancelled = true
        processId?.let { id ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        }
        processId = null
    }

    fun finish(processId: String) {
        if (this.processId == processId) this.processId = null
    }
}