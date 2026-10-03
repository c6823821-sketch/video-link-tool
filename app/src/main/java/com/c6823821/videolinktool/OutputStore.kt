package com.c6823821.videolinktool

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object OutputStore {
    private const val GALLERY_FOLDER = "视频工具箱图集"

    data class Saved(val uri: Uri?, val path: String, val displayName: String)

    fun saveFile(context: Context, source: File, title: String, mime: String): Saved {
        val safeTitle = LinkExtractor.sanitizeTitle(title)
        val ext = source.extension.ifBlank { "mp4" }
        val displayName = safeTitle + "." + ext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建下载文件")
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法写入下载文件")
            Saved(uri, uri.toString(), displayName)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            var target = File(dir, displayName)
            var index = 1
            while (target.exists()) {
                target = File(dir, safeTitle + "_" + index + "." + ext)
                index++
            }
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Saved(null, target.absolutePath, target.name)
        }
    }

    fun saveGallery(context: Context, files: List<File>, title: String): List<Saved> {
        val safeTitle = LinkExtractor.sanitizeTitle(title)
        val saved = mutableListOf<Saved>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = Environment.DIRECTORY_DOWNLOADS + "/" + GALLERY_FOLDER + "/" + safeTitle
            files.forEachIndexed { index, source ->
                val ext = source.extension.ifBlank { "jpg" }
                val displayName = (index + 1).toString().padStart(2, '0') + "." + ext
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法创建图集文件")
                resolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法写入图集文件")
                saved += Saved(uri, uri.toString(), displayName)
            }
        } else {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(root, GALLERY_FOLDER + "/" + safeTitle).apply { mkdirs() }
            files.forEachIndexed { index, source ->
                val ext = source.extension.ifBlank { "jpg" }
                val target = File(dir, (index + 1).toString().padStart(2, '0') + "." + ext)
                source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                saved += Saved(null, target.absolutePath, target.name)
            }
        }
        return saved
    }

    fun saveText(context: Context, text: String, title: String): Saved {
        val temp = File(context.cacheDir, "video_link_tool_result.txt")
        temp.writeText(text, Charsets.UTF_8)
        return saveFile(context, temp, title, "text/plain")
    }

    fun legacyUri(context: Context, path: String): Uri {
        val file = File(path)
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }
}
