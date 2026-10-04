package com.c6823821.videolinktool

import android.content.Context
import android.content.res.AssetManager
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the bundled Vosk model out of the APK assets into app storage.
 *
 * Vosk's own StorageService.unpack() refuses to work when the model folder has no
 * "uuid" file (the small-cn-0.3 zip does not ship one), so we copy the asset tree
 * ourselves. Recursion is required because the model contains an "ivector" folder.
 */
object ModelManager {
    private const val ASSET_DIR = "vosk-model-small-cn-0.3"
    private const val REQUIRED_FILE = "final.mdl"

    @Volatile
    private var cached: Model? = null

    fun load(context: Context, onProgress: (Int, String) -> Unit): Model {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            onProgress(5, "初始化离线语音模型...")
            val modelDir = File(context.filesDir, "vosk-model")
            if (!File(modelDir, REQUIRED_FILE).exists()) {
                runCatching { modelDir.deleteRecursively() }
                copyAssetDirectory(context.assets, ASSET_DIR, modelDir)
                onProgress(25, "离线语音模型准备完成")
            }
            if (!File(modelDir, REQUIRED_FILE).exists()) {
                throw IllegalStateException("内置语音模型不完整，请重新安装最新版")
            }
            val model = Model(modelDir.absolutePath)
            cached = model
            return model
        }
    }

    private fun copyAssetDirectory(assets: AssetManager, assetPath: String, target: File) {
        val children = runCatching { assets.list(assetPath) }.getOrNull().orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(target, false).use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAssetDirectory(assets, "$assetPath/$child", File(target, child))
        }
    }
}
