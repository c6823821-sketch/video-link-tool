package com.c6823821.videolinktool

import android.content.Context
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ModelManager {
    @Volatile private var cached: Model? = null

    fun load(context: Context, onProgress: (Int, String) -> Unit): Model {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            onProgress(90, "初始化离线语音模型...")
            val modelDir = File(context.filesDir, "vosk-model")
            if (File(modelDir, "final.mdl").exists()) {
                return Model(modelDir.absolutePath).also { cached = it }
            }

            val latch = CountDownLatch(1)
            var result: Model? = null
            var error: Exception? = null
            StorageService.unpack(
                context,
                "vosk-model-small-cn-0.3",
                "vosk-model",
                { model ->
                    result = model
                    latch.countDown()
                },
                { exception ->
                    error = exception
                    latch.countDown()
                },
            )
            if (!latch.await(10, TimeUnit.MINUTES)) {
                throw IllegalStateException("语音模型初始化超时")
            }
            error?.let { throw IllegalStateException("语音模型初始化失败：" + (it.message ?: "未知错误"), it) }
            return result?.also { cached = it } ?: throw IllegalStateException("语音模型初始化失败")
        }
    }
}
