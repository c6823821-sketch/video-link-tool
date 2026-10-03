package com.c6823821.videolinktool

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File

object UpdateManager {
    private const val API = "https://api.github.com/repos/c6823821-sketch/video-link-tool/releases/latest"

    data class UpdateInfo(val version: String, val downloadUrl: String)

    fun check(context: Context): UpdateInfo? {
        val request = Request.Builder()
            .url(API)
            .header("User-Agent", "VideoLinkTool")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        HttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val root = JSONObject(response.body?.string().orEmpty())
            val tag = root.optString("tag_name").removePrefix("v").removePrefix("V")
            val assets = root.optJSONArray("assets") ?: return null
            var url = ""
            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                val name = item.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    url = item.optString("browser_download_url")
                    if (url.isNotBlank()) break
                }
            }
            if (url.isBlank()) return null
            val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            return if (compareVersion(tag, current) > 0) UpdateInfo(tag, url) else null
        }
    }

    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val output = File(context.cacheDir, "VideoLinkTool-update.apk")
        Downloader.download(info.downloadUrl, emptyMap(), output) { progress -> onProgress(progress) }
        return output
    }

    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun compareVersion(a: String, b: String): Int {
        val left = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }
}
