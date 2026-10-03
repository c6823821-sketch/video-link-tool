package com.c6823821.videolinktool

import android.content.Context
import android.content.res.AssetManager
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream

object ModelManager {
    @Volatile private var cached: Model? = null

    fun load(context: Context, onProgress: (Int, String) -> Unit): Model {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            onProgress(90, "初始化离线语音模型...")
            val modelDir = File(context.filesDir, "vosk-model")
            if (!File(modelDir, "final.mdl").exists()) {
                copyAssetDirectory(context.assets, "vosk-model-small-cn-0.3", modelDir, onProgress)
            }
            if (!File(modelDir, "final.mdl").exists()) {
                throw IllegalStateException("内置语音模型不完整，请重新安装最新版")
            }
            return Model(modelDir.absolutePath).also { cached = it }
        }
    }

    private fun copyAssetDirectory(
        assets: AssetManager,
        assetPath: String,
        targetDir: File,
        onProgress: (Int, String) -> Unit,
    ) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            targetDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(targetDir, false).use { output -> input.copyTo(output) }
            }
            return
        }
        targetDir.mkdirs()
        var index = 0
        children.forEach { child ->
            val sourceChild = "$assetPath/$child"
            val targetChild = File(targetDir, child)
            copyAssetDirectory(assets, sourceChild, targetChild, onProgress)
            index += 1
            onProgress((90 + (index * 5 / children.size)).coerceIn(90, 95), "初始化离线语音模型...")
        }
    }
}
