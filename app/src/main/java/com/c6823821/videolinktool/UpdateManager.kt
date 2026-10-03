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
    private const val REPO = "c6823821-sketch/video-link-tool"
    private const val LATEST_PAGE = "https://github.com/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    data class UpdateInfo(val version: String, val downloadUrls: List<String>)

    fun check(context: Context): UpdateInfo? {
        val tag = latestTag() ?: return null
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        if (compareVersion(tag, current) <= 0) return null
        val clean = tag.removePrefix("v").removePrefix("V")
        val file = "VideoLinkTool-v$clean.apk"
        val direct = "https://github.com/$REPO/releases/download/v$clean/$file"
        val fallback = "https://github.com/$REPO/releases/download/v$clean/app-release.apk"
        return UpdateInfo(
            version = clean,
            downloadUrls = listOf(
                direct,
                "https://gh-proxy.com/$direct",
                "https://ghfast.top/$direct",
                fallback,
                "https://gh-proxy.com/$fallback",
                "https://ghfast.top/$fallback",
            ),
        )
    }

    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val output = File(context.cacheDir, "VideoLinkTool-update.apk")
        var lastError: Exception? = null
        for (url in info.downloadUrls) {
            try {
                Downloader.download(url, emptyMap(), output) { progress -> onProgress(progress) }
                if (output.exists() && output.length() > 1024) return output
            } catch (e: Exception) {
                lastError = e
                output.delete()
            }
        }
        throw IllegalStateException(lastError?.message ?: "更新包下载失败，请用浏览器打开 Release 下载")
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

    private fun latestTag(): String? {
        latestTagFromPage()?.let { return it }
        latestTagFromApi()?.let { return it }
        return null
    }

    private fun latestTagFromPage(): String? {
        return try {
            val request = Request.Builder()
                .url(LATEST_PAGE)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()
            HttpClient.client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                Regex("/releases/tag/v?([0-9][0-9.]*)").find(finalUrl)?.groupValues?.getOrNull(1)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun latestTagFromApi(): String? {
        return try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("User-Agent", "VideoLinkTool")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            HttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body?.string().orEmpty()).optString("tag_name").removePrefix("v").removePrefix("V")
            }
        } catch (_: Exception) {
            null
        }
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
