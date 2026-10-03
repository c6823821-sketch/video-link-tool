package com.c6823821.videolinktool

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the bundled Paraformer model out of the APK assets into app storage the
 * first time it is needed. Loading straight from assets is not possible for the
 * native recognizer, and Vosk-style helpers do not apply here.
 */
object ModelManager {
    private const val MODEL_ASSET = "asr/model.int8.onnx"
    private const val TOKENS_ASSET = "asr/tokens.txt"
    private const val MODEL_BYTES = 81_828_675L
    private const val TOKENS_BYTES = 75_352L

    data class Paths(val model: File, val tokens: File)

    @Volatile
    private var prepared: Paths? = null

    fun prepare(context: Context, onProgress: (Int, String) -> Unit): Paths {
        prepared?.let { return it }
        synchronized(this) {
            prepared?.let { return it }
            val dir = File(context.filesDir, "paraformer").apply { mkdirs() }
            val model = File(dir, "model.int8.onnx")
            val tokens = File(dir, "tokens.txt")
            if (!valid(model, MODEL_BYTES)) {
                copyAsset(context, MODEL_ASSET, model, MODEL_BYTES, 2, 12, onProgress, "正在初始化语音模型")
            }
            if (!valid(tokens, TOKENS_BYTES)) {
                copyAsset(context, TOKENS_ASSET, tokens, TOKENS_BYTES, 12, 15, onProgress, "正在初始化语音词表")
            }
            if (!valid(model, MODEL_BYTES) || !valid(tokens, TOKENS_BYTES)) {
                throw IllegalStateException("内置语音模型不完整，请重新安装最新版")
            }
            return Paths(model, tokens).also { prepared = it }
        }
    }

    private fun copyAsset(
        context: Context,
        assetPath: String,
        target: File,
        expectedBytes: Long,
        progressStart: Int,
        progressEnd: Int,
        onProgress: (Int, String) -> Unit,
        label: String,
    ) {
        val part = File(target.absolutePath + ".part")
        try {
            context.assets.open(assetPath).use { input ->
                BufferedOutputStream(FileOutputStream(part)).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        val percent = progressStart + ((done * (progressEnd - progressStart)) / expectedBytes).toInt()
                        if (percent != last) {
                            last = percent
                            onProgress(percent.coerceIn(progressStart, progressEnd), label)
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } catch (e: Exception) {
            part.delete()
            throw IllegalStateException("初始化内置语音模型失败：" + (e.message ?: "未知错误"))
        }
    }

    private fun valid(file: File, expected: Long): Boolean =
        file.exists() && file.length() == expected
}