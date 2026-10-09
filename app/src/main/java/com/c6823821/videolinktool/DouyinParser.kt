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

    internal fun buildForTest(detail: JSONObject): DirectMedia = build(detail)

    private fun build(detail: JSONObject): DirectMedia {
        val title = detail.optString("desc").ifBlank { "抖音作品" }
        val images = galleryItems(detail).mapNotNull(::buildGalleryItem)
        val video = chooseVideo(detail.optJSONObject("video"))
        val audioUrl = chooseAudio(detail)
        val headers = mapOf("Referer" to "https://www.douyin.com/", "User-Agent" to UA)

        if (images.isNotEmpty()) {
            return DirectMedia(
                title = title,
                url = video.second,
                headers = headers,
                site = "抖音",
                quality = "图集",
                images = images,
                audioUrl = audioUrl,
                audioExt = extensionFromUrl(audioUrl, "mp3"),
            )
        }
        if (video.second.isBlank()) throw IllegalStateException("抖音作品没有可下载资源")
        return DirectMedia(
            title = title,
            url = video.second,
            headers = headers,
            site = "抖音",
            quality = video.first,
            audioUrl = audioUrl,
            audioExt = extensionFromUrl(audioUrl, "mp3"),
        )
    }

    private fun galleryItems(detail: JSONObject): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        fun addArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(items::add)
            }
        }

        val imagePost = detail.optJSONObject("image_post_info")
        if (imagePost != null) {
            addArray(imagePost.optJSONArray("images"))
            addArray(imagePost.optJSONArray("image_list"))
        }
        addArray(detail.optJSONArray("images"))
        addArray(detail.optJSONArray("image_list"))
        return items
    }

    private fun buildGalleryItem(item: JSONObject): ImageItem? {
        val imageUrl = chooseGalleryImage(item)
        val liveUrl = chooseLiveVideo(item)
        if (imageUrl.isBlank() && liveUrl.isBlank()) return null
        return ImageItem(
            url = imageUrl,
            ext = extensionFromUrl(imageUrl, "jpg"),
            liveUrl = liveUrl,
            liveExt = "mp4",
        )
    }

    private fun chooseGalleryImage(item: JSONObject): String {
        val candidates = listOf<Any?>(
            item.optJSONArray("watermark_free_download_url_list"),
            item.optJSONObject("origin_image"),
            item.optJSONObject("display_image"),
            item.optJSONArray("url_list"),
            item.optJSONObject("download_url"),
            item.optJSONObject("download_addr"),
            item.optJSONArray("download_url_list"),
            item.optString("url").takeIf { it.isNotBlank() },
            item.optJSONObject("owner_watermark_image"),
        )
        return candidates.asSequence().map(::firstUrl).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun chooseLiveVideo(item: JSONObject): String {
        val chosen = chooseVideo(item.optJSONObject("video")).second
        if (chosen.isNotBlank()) return chosen
        return firstUrl(item.opt("video_play_addr"))
            .ifBlank { firstUrl(item.opt("video_download_addr")) }
    }

    private fun chooseAudio(detail: JSONObject): String {
        val imagePost = detail.optJSONObject("image_post_info")
        val candidates = listOf<Any?>(
            detail.optJSONObject("music")?.optJSONObject("play_url"),
            imagePost?.optJSONObject("music")?.optJSONObject("play_url"),
            detail.optJSONObject("images_music")?.optJSONObject("play_url"),
            detail.optJSONObject("video")?.optJSONObject("audio")?.optJSONObject("play_addr"),
        )
        return candidates.asSequence().map(::firstUrl).firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun chooseVideo(video: JSONObject?): Pair<String, String> {
        if (video == null) return "" to ""
        data class Candidate(val url: String, val pixels: Long, val bitrate: Long, val quality: String, val hevc: Boolean)
        val candidates = mutableListOf<Candidate>()
        val bitRates = video.optJSONArray("bit_rate")
        if (bitRates != null) {
            for (i in 0 until bitRates.length()) {
                val item = bitRates.optJSONObject(i) ?: continue
                val play = item.optJSONObject("play_addr") ?: continue
                val url = firstUrl(play).replace("playwm", "play")
                if (url.isBlank()) continue
                val width = play.optLong("width")
                val height = play.optLong("height")
                val quality = if (height > 0) height.toString() + "P" else item.optString("gear_name")
                val hevc = item.optInt("is_h265", 0) == 1
                candidates += Candidate(url, width * height, item.optLong("bit_rate"), quality, hevc)
            }
        }
        val fallback = video.optJSONObject("play_addr_h264") ?: video.optJSONObject("play_addr")
        val fallbackUrl = firstUrl(fallback).replace("playwm", "play")
        val fallbackHeight = fallback?.optLong("height") ?: 0
        if (fallbackUrl.isNotBlank()) {
            val width = fallback?.optLong("width") ?: 0
            candidates += Candidate(
                fallbackUrl,
                width * fallbackHeight,
                0L,
                if (fallbackHeight > 0) fallbackHeight.toString() + "P" else "原画",
                false,
            )
        }
        if (candidates.isEmpty() && fallbackUrl.isNotBlank()) {
            return (if (fallbackHeight > 0) fallbackHeight.toString() + "P" else "原画") to fallbackUrl
        }
        val best = candidates.maxWithOrNull(
            compareBy<Candidate> { it.pixels }
                .thenBy { if (it.hevc) 0 else 1 }
                .thenBy { it.bitrate }
        )
        return if (best != null) best.quality.ifBlank { "原画" } to best.url else "" to fallbackUrl
    }

    private fun firstUrl(value: Any?): String = when (value) {
        is String -> value.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }.orEmpty()
        is JSONArray -> {
            var found = ""
            for (i in 0 until value.length()) {
                found = firstUrl(value.opt(i))
                if (found.isNotBlank()) break
            }
            found
        }
        is JSONObject -> {
            val direct = value.optString("url").trim()
            if (direct.startsWith("http://", true) || direct.startsWith("https://", true)) {
                direct
            } else {
                firstUrl(value.optJSONArray("url_list"))
            }
        }
        else -> ""
    }

    private fun extensionFromUrl(url: String, fallback: String): String {
        if (url.isBlank()) return fallback
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        val match = Regex("\\.(jpe?g|png|webp|gif|heic|avif|mp3|m4a|aac|mp4)(?=~|\\.image|[^a-z0-9]|$)", RegexOption.IGNORE_CASE)
            .find(clean)
        val ext = match?.groupValues?.getOrNull(1).orEmpty().lowercase()
        return when (ext) {
            "jpeg" -> "jpg"
            "m4a" -> "m4a"
            else -> ext.ifBlank { fallback }
        }
    }

    private fun extractAwemeId(url: String): String? {
        val modal = Regex("[?&]modal_id=(\\d{12,})").find(url)?.groupValues?.getOrNull(1)
        if (!modal.isNullOrBlank()) return modal
        return Regex("/(?:video|note)/(\\d{12,})").find(url)?.groupValues?.getOrNull(1)
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
