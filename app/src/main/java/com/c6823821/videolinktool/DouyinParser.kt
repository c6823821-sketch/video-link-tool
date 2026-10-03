package com.c6823821.videolinktool

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object DouyinParser {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private val ttwidRegex = Regex("ttwid=([^;]+)")
    @Volatile private var ttwid: String? = null

    fun parse(finalUrl: String): DirectMedia {
        val id = extractAwemeId(finalUrl) ?: throw IllegalStateException("抖音链接里没有作品 ID")
        val cookie = getTtwid()
        val queries = listOf("aweme_ids=%5B$id%5D", "aweme_ids=%5B$id%5D&request_source=200")
        val hosts = listOf("www.douyin.com", "www.iesdouyin.com")
        var lastError = "抖音没有返回作品数据"
        for (query in queries) {
            for (host in hosts) {
                val api = "https://$host/web/api/v2/aweme/slidesinfo/?$query"
                try {
                    val headers = mutableMapOf("User-Agent" to UA)
                    if (!cookie.isNullOrBlank()) headers["Cookie"] = "ttwid=$cookie"
                    val page = PageFetcher.fetch(api, referer = "https://www.douyin.com/", headers = headers, userAgent = UA)
                    val root = JSONObject(page.html)
                    val details = root.optJSONArray("aweme_details")
                    if (details != null && details.length() > 0) {
                        val detail = details.optJSONObject(0)
                        if (detail != null) return build(detail)
                    }
                    val filters = root.optJSONArray("filter_list")
                    if (filters != null && filters.length() > 0) lastError = "抖音限制了这个作品的接口数据"
                } catch (e: Exception) {
                    lastError = e.message ?: lastError
                }
            }
        }
        throw IllegalStateException(lastError)
    }

    private fun build(detail: JSONObject): DirectMedia {
        val title = detail.optString("desc").ifBlank { "抖音作品" }
        val images = mutableListOf<ImageItem>()
        val imageArray = detail.optJSONArray("images")
        if (imageArray != null) {
            for (i in 0 until imageArray.length()) {
                val item = imageArray.optJSONObject(i) ?: continue
                val url = chooseImage(item.optJSONArray("url_list"))
                if (url.isNotBlank()) images += ImageItem(url)
            }
        }
        val video = chooseVideo(detail.optJSONObject("video"))
        if (images.isNotEmpty()) {
            return DirectMedia(
                title = title,
                headers = mapOf("Referer" to "https://www.douyin.com/", "User-Agent" to UA),
                site = "抖音",
                quality = "图集",
                images = images,
            )
        }
        if (video.second.isBlank()) throw IllegalStateException("抖音作品没有可下载资源")
        return DirectMedia(
            title = title,
            url = video.second,
            headers = mapOf("Referer" to "https://www.douyin.com/", "User-Agent" to UA),
            site = "抖音",
            quality = video.first,
        )
    }

    private fun chooseVideo(video: JSONObject?): Pair<String, String> {
        if (video == null) return "" to ""
        data class Candidate(val url: String, val pixels: Long, val bitrate: Long, val quality: String)
        val candidates = mutableListOf<Candidate>()
        val bitRates = video.optJSONArray("bit_rate")
        if (bitRates != null) {
            for (i in 0 until bitRates.length()) {
                val item = bitRates.optJSONObject(i) ?: continue
                if (item.optInt("is_h265", 0) == 1) continue
                val play = item.optJSONObject("play_addr") ?: continue
                var url = play.optJSONArray("url_list")?.optString(0).orEmpty().replace("playwm", "play")
                if (url.isBlank()) continue
                val width = play.optLong("width")
                val height = play.optLong("height")
                val quality = if (height > 0) height.toString() + "P" else item.optString("gear_name")
                candidates += Candidate(url, width * height, item.optLong("bit_rate"), quality)
            }
        }
        val fallback = video.optJSONObject("play_addr_h264") ?: video.optJSONObject("play_addr")
        var fallbackUrl = fallback?.optJSONArray("url_list")?.optString(0).orEmpty().replace("playwm", "play")
        val fallbackHeight = fallback?.optLong("height") ?: 0
        if (candidates.isEmpty() && fallbackUrl.isNotBlank()) {
            return (if (fallbackHeight > 0) fallbackHeight.toString() + "P" else "原画") to fallbackUrl
        }
        val best = candidates.maxWithOrNull(compareBy<Candidate> { it.pixels }.thenBy { it.bitrate })
        return if (best != null) best.quality.ifBlank { "原画" } to best.url else "" to fallbackUrl
    }

    private fun chooseImage(urlList: JSONArray?): String {
        if (urlList == null) return ""
        var fallback = ""
        for (i in 0 until urlList.length()) {
            val url = urlList.optString(i)
            if (url.isBlank()) continue
            if (fallback.isBlank()) fallback = url
            if (!url.contains(".webp", ignoreCase = true)) return url
        }
        return fallback
    }

    private fun extractAwemeId(url: String): String? {
        val uri = Uri.parse(url)
        val modal = uri.getQueryParameter("modal_id")
        if (!modal.isNullOrBlank()) return modal
        return uri.pathSegments?.reversed()?.firstOrNull { it.matches(Regex("\\d{12,}")) }
    }

    private fun getTtwid(): String? {
        ttwid?.let { return it }
        synchronized(this) {
            ttwid?.let { return it }
            return try {
                val body = JSONObject()
                    .put("region", "cn").put("aid", 1768).put("needFid", false)
                    .put("service", "www.ixigua.com").put("union", true).put("cbUrlProtocol", "https")
                    .put("migrate_info", JSONObject().put("ticket", "").put("source", "node")).toString()
                val request = Request.Builder()
                    .url("https://ttwid.bytedance.com/ttwid/union/register/")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", UA)
                    .build()
                val value = HttpClient.client.newCall(request).execute().use { response ->
                    response.headers.values("Set-Cookie").firstNotNullOfOrNull { header ->
                        ttwidRegex.find(header)?.groupValues?.getOrNull(1)
                    }
                }
                ttwid = value
                value
            } catch (_: Exception) {
                null
            }
        }
    }
}
