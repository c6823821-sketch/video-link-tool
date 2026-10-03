package com.c6823821.videolinktool

import android.content.Context
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechStreamService
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object AsrEngine {
    fun transcribe(
        context: Context,
        wavPath: String,
        onProgress: (Int, String) -> Unit,
    ): String {
        onProgress(90, "初始化离线语音模型...")
        val model = ModelManager.load(context, onProgress)
        onProgress(95, "正在识别...")

        val recognizer = Recognizer(model, 16000.0f)
        val input = FileInputStream(wavPath)
        if (input.skip(44) != 44L) {
            input.close()
            throw IllegalStateException("音频文件太短")
        }

        val latch = CountDownLatch(1)
        var text = ""
        var error: Exception? = null
        val service = SpeechStreamService(recognizer, input, 16000.0f)
        service.start(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) = Unit
            override fun onResult(hypothesis: String?) = Unit
            override fun onFinalResult(hypothesis: String?) {
                text = hypothesis.orEmpty()
                latch.countDown()
            }
            override fun onError(exception: Exception?) {
                error = exception
                latch.countDown()
            }
            override fun onTimeout() {
                latch.countDown()
            }
        })

        val finished = latch.await(15, TimeUnit.MINUTES)
        runCatching { service.stop() }
        runCatching { input.close() }
        if (!finished) throw IllegalStateException("语音识别超时")
        error?.let { throw IllegalStateException("语音识别失败：" + (it.message ?: "未知错误"), it) }
        val result = text.trim()
        if (result.isBlank()) throw IllegalStateException("没有识别到文字，可能视频没有语音")
        onProgress(100, "识别完成")
        return result
    }
}
