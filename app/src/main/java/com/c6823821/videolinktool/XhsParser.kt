package com.c6823821.videolinktool

import org.json.JSONArray
import org.json.JSONObject

object XhsParser {
    private const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
    private val stateRegex = Regex("window\\.__INITIAL_STATE__\\s*=\\s*(\\{.*?\\})\\s*</script>", RegexOption.DOT_MATCHES_ALL)
    private val masterRegex = Regex("\"masterUrl\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
    private val titleRegex = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)

    fun parse(finalUrl: String): DirectMedia {
        val page = PageFetcher.fetch(finalUrl, referer = "https://www.xiaohongshu.com/", userAgent = UA)
        val html = page.html
        val raw = stateRegex.find(html)?.groupValues?.getOrNull(1)
        if (raw != null) {
            val jsonText = raw.replace("undefined", "null").replace("NaN", "null").replace("Infinity", "null")
            val root = JSONObject(jsonText)
            val note = findNote(root)
            if (note != null) return buildFromJson(note)
        }
        return buildFromRegex(html)
    }

    private fun buildFromJson(note: JSONObject): DirectMedia {
        val video = note.optJSONObject("video")
        val stream = video?.optJSONObject("media")?.optJSONObject("stream")
        val h264 = stream?.optJSONArray("h264")
        val h265 = stream?.optJSONArray("h265")
        val chosen = bestVideo(h264) ?: bestVideo(h265)
        val videoUrl = chosen?.optString("masterUrl").orEmpty().replace("http://", "https://")
        val title = note.optString("title").ifBlank { note.optString("desc").take(60) }.ifBlank { "小红书作品" }
        if (videoUrl.isNotBlank()) {
            val height = chosen?.optLong("height") ?: 0
            return DirectMedia(
                title = title,
                url = videoUrl,
                headers = mapOf("Referer" to "https://www.xiaohongshu.com/", "User-Agent" to UA),
                site = "小红书",
                quality = if (height > 0) height.toString() + "P" else "原画",
            )
        }
        val images = mutableListOf<ImageItem>()
        val imageList = note.optJSONArray("imageList")
        if (imageList != null) {
            for (i in 0 until imageList.length()) {
                val item = imageList.optJSONObject(i) ?: continue
                val url = item.optString("urlDefault")
                    .ifBlank { item.optString("url") }
                    .ifBlank { item.optString("urlPre") }
                    .replace("http://", "https://")
                if (url.isNotBlank()) images += ImageItem(url)
            }
        }
        if (images.isEmpty()) throw IllegalStateException("小红书页面里没有找到视频或图片")
        return DirectMedia(
            title = title,
            headers = mapOf("Referer" to "https://www.xiaohongshu.com/", "User-Agent" to UA),
            site = "小红书",
            quality = "图集",
            images = images,
        )
    }

    private fun buildFromRegex(html: String): DirectMedia {
        val master = masterRegex.find(html)?.groupValues?.getOrNull(1)?.replace("\\u002F", "/")?.replace("\\/", "/")?.replace("http://", "https://")
        val title = titleRegex.find(html)?.groupValues?.getOrNull(1)?.replace("\\u002F", "/") ?: "小红书作品"
        if (!master.isNullOrBlank()) {
            return DirectMedia(title, url = master, headers = mapOf("Referer" to "https://www.xiaohongshu.com/"), site = "小红书")
        }
        val images = mutableListOf<ImageItem>()
        val imageRegex = Regex("\"urlDefault\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
        imageRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1].replace("\\u002F", "/").replace("\\/", "/").replace("http://", "https://")
            if (url.startsWith("http")) images += ImageItem(url)
        }
        if (images.isEmpty()) throw IllegalStateException("小红书页面里没有找到视频或图片")
        return DirectMedia(title = title, headers = mapOf("Referer" to "https://www.xiaohongshu.com/"), site = "小红书", images = images)
    }

    private fun bestVideo(array: JSONArray?): JSONObject? {
        if (array == null || array.length() == 0) return null
        var best: JSONObject? = null
        var bestPixels = -1L
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val pixels = item.optLong("width") * item.optLong("height")
            if (pixels > bestPixels) {
                bestPixels = pixels
                best = item
            }
        }
        return best
    }

    private fun findNote(obj: JSONObject?): JSONObject? {
        if (obj == null) return null
        if (obj.has("imageList") || (obj.has("video") && (obj.has("title") || obj.has("desc")))) return obj
        for (key in obj.keys()) {
            val value = obj.opt(key)
            if (value is JSONObject) {
                findNote(value)?.let { return it }
            } else if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val child = value.optJSONObject(i) ?: continue
                    findNote(child)?.let { return it }
                }
            }
        }
        return null
    }
}
