package com.c6823821.videolinktool

import android.content.Context
import java.io.File
import java.security.MessageDigest

object MediaCache {
    private const val MAX_FILES = 3

    fun save(context: Context, url: String, source: File): File? {
        if (!source.exists() || source.length() < 1024) return null
        val dir = File(context.cacheDir, "media_cache").apply { mkdirs() }
        val target = File(dir, key(url) + ".mp4")
        val part = File(dir, target.name + ".part")
        return try {
            source.inputStream().use { input ->
                part.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            cleanup(dir)
            target
        } catch (_: Exception) {
            part.delete()
            null
        }
    }

    fun get(context: Context, url: String): File? {
        val file = File(File(context.cacheDir, "media_cache"), key(url) + ".mp4")
        return file.takeIf { it.exists() && it.length() > 1024 }
    }

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun cleanup(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") } ?: return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }
}
