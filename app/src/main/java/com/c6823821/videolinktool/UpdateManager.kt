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
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object UpdateManager {
    private const val REPO = "c6823821-sketch/video-link-tool"
    private const val LATEST_PAGE = "https://github.com/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    private const val PREFS = "video_link_tool_update"
    private const val KEY_SKIP = "skipped_version"

    /**
     * 加速线路优先，github.com 直连放最后。
     *
     * 实测（国内）：gh-proxy 约 480 KB/s、ghfast 约 400 KB/s，而 github.com 直连只有 40 KB/s
     * 甚至直接卡死。以前把直连排在第一位，所以更新一直停在 0%~1%。
     * 空字符串代表不走代理的直连地址。
     */
    private val MIRRORS = listOf("https://gh-proxy.com/", "https://ghfast.top/", "")

    data class UpdateInfo(val version: String, val downloadUrls: List<String>)

    fun check(context: Context): UpdateInfo? {
        val tag = latestTag() ?: return null
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        if (compareVersion(tag, current) <= 0) return null
        val clean = tag.removePrefix("v").removePrefix("V")
        val base = "https://github.com/$REPO/releases/download/v$clean/"
        val names = mutableListOf<String>()
        if (isArm64()) names += "VideoLinkTool-v$clean-arm64.apk"
        names += "VideoLinkTool-v$clean.apk"
        val urls = mutableListOf<String>()
        names.forEach { name ->
            MIRRORS.forEach { mirror -> urls += mirror + base + name }
        }
        return UpdateInfo(version = clean, downloadUrls = urls)
    }

    fun skippedVersion(context: Context): String? =
        prefs(context).getString(KEY_SKIP, null)

    fun skipVersion(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIP, version).apply()
    }

    fun cachedApk(context: Context, version: String): File? {
        val file = apkFile(context, version)
        return if (file.exists() && isValidApk(file)) file else null
    }

    /**
     * Downloads the update. Every mirror is tried in turn; a stalled connection is
     * dropped after 25 seconds of silence so one dead route cannot freeze everything.
     */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int, String) -> Unit): File {
        cachedApk(context, info.version)?.let { return it }
        val target = apkFile(context, info.version)
        val partial = File(target.parentFile, target.name + ".part")

        var lastError: Exception? = null
        for (url in info.downloadUrls) {
            repeat(2) { attempt ->
                try {
                    fetch(url, partial, onProgress)
                    if (!isValidApk(partial)) throw IllegalStateException("更新包不完整")
                    if (target.exists()) target.delete()
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        partial.delete()
                    }
                    onProgress(100, "下载完成，正在打开安装")
                    return target
                } catch (e: Exception) {
                    lastError = e
                    partial.delete()
                    if (attempt == 1) onProgress(-1, "这条线路不行，换下一条…")
                }
            }
        }
        throw IllegalStateException(
            lastError?.message ?: "更新包下载失败，请点“浏览器打开”手动下载"
        )
    }

    private fun fetch(url: String, target: File, onProgress: (Int, String) -> Unit) {
        val existing = if (target.exists()) target.length() else 0L
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClient.MOBILE_UA)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val client = HttpClient.client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
        var lastMb = -1
        client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("下载失败：HTTP " + response.code)
            val body = response.body ?: throw IllegalStateException("下载响应为空")
            val append = existing > 0L && response.code == 206
            val startAt = if (append) existing else 0L
            val total = if (response.code == 206) {
                contentRangeTotal(response.header("Content-Range"))
            } else {
                body.contentLength()
            }
            RandomAccessFile(target, "rw").use { file ->
                if (!append) file.setLength(0)
                file.seek(startAt)
                var written = startAt
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        file.write(buffer, 0, count)
                        written += count
                        val mb = (written / (1024 * 1024)).toInt()
                        if (mb != lastMb) {
                            lastMb = mb
                            val percent = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 99) else -1
                            val detail = if (total > 0) {
                                "已下载 " + readable(written) + " / " + readable(total)
                            } else {
                                "已下载 " + readable(written)
                            }
                            onProgress(percent, detail)
                        }
                    }
                }
                if (total > 0 && file.length() != total) throw IllegalStateException("连接中断，已下载 " + readable(file.length()))
            }
        }
    }

    fun isInstallAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun install(context: Context, file: File): Boolean {
        if (!isInstallAllowed(context)) {
            openInstallPermission(context)
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    private fun apkFile(context: Context, version: String): File {
        val dir = File(context.filesDir, "updates")
        dir.mkdirs()
        return File(dir, "VideoLinkTool-$version.apk")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun contentRangeTotal(header: String?): Long =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L

    private fun readable(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1) String.format(java.util.Locale.US, "%.1fMB", mb) else (bytes / 1024).toString() + "KB"
    }

    private fun isArm64(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    fun isValidApk(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        val tailLength = file.length().coerceAtMost(70_000L).toInt()
        val buffer = ByteArray(tailLength)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - tailLength)
            raf.readFully(buffer)
        }
        for (i in buffer.size - 22 downTo 0) {
            if (buffer[i] == 0x50.toByte() && buffer[i + 1] == 0x4B.toByte() &&
                buffer[i + 2] == 0x05.toByte() && buffer[i + 3] == 0x06.toByte()
            ) {
                return true
            }
        }
        return false
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