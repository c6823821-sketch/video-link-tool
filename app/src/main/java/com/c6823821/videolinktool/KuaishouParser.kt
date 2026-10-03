package com.c6823821.videolinktool

import okhttp3.Request
import org.json.JSONObject

object KuaishouParser {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    private val initRegex = Regex("window\\.INIT_STATE\\s*=\\s*(\\{.*?\\})\\s*</script>", RegexOption.DOT_MATCHES_ALL)

    fun parse(url: String): DirectMedia {
        val html = fetchWithCookies(url)
        val raw = initRegex.find(html)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("快手页面里没有 INIT_STATE")
        val state = JSONObject(raw)
        val normalized = JSONObject()
        for (key in state.keys()) normalized.put(shiftKey(key), state.get(key))

        var entry: JSONObject? = null
        val keyIterator = normalized.keys()
        while (keyIterator.hasNext()) {
            val candidate = normalized.optJSONObject(keyIterator.next())
            if (candidate != null && candidate.has("result") && candidate.has("photo")) {
                entry = candidate
                break
            }
        }
        val workEntry = entry ?: throw IllegalStateException("快手页面里没有作品数据")
        if (workEntry.optInt("result", 0) != 1) throw IllegalStateException("快手作品不可用")
        val photo = workEntry.optJSONObject("photo") ?: throw IllegalStateException("快手作品数据为空")

        val images = mutableListOf<ImageItem>()
        val atlas = photo.optJSONObject("ext_params")?.optJSONObject("atlas")
        val list = atlas?.optJSONArray("list")
        val cdns = atlas?.optJSONArray("cdn")
        if (list != null && cdns != null && list.length() > 0 && cdns.length() > 0) {
            val cdn = cdns.optString(0)
            for (i in 0 until list.length()) {
                val path = list.optString(i)
                if (path.isNotBlank() && cdn.isNotBlank()) images += ImageItem("https://$cdn/$path")
            }
        }

        var videoUrl = ""
        var quality = ""
        if (images.isEmpty()) {
            videoUrl = photo.optJSONArray("mainMvUrls")?.optJSONObject(0)?.optString("url").orEmpty()
            quality = photo.optLong("height", 0).takeIf { it > 0 }?.let { it.toString() + "P" } ?: ""
            val reps = photo.optJSONObject("manifest")?.optJSONArray("adaptationSet")
                ?.optJSONObject(0)?.optJSONArray("representation")
            if (reps != null) {
                var bestPixels = -1L
                var bestUrl = videoUrl
                var bestHeight = photo.optLong("height", 0)
                for (i in 0 until reps.length()) {
                    val rep = reps.optJSONObject(i) ?: continue
                    val codec = rep.optString("videoCodec").lowercase()
                    if (codec.isNotBlank() && codec != "avc" && codec != "h264") continue
                    val url = rep.optString("url")
                    if (url.isBlank()) continue
                    val pixels = rep.optLong("width") * rep.optLong("height")
                    if (pixels >= bestPixels) {
                        bestPixels = pixels
                        bestUrl = url
                        bestHeight = rep.optLong("height", bestHeight)
                    }
                }
                if (bestUrl.isNotBlank()) videoUrl = bestUrl
                if (bestHeight > 0) quality = bestHeight.toString() + "P"
            }
        }
        if (videoUrl.isBlank() && images.isEmpty()) throw IllegalStateException("快手作品没有可下载资源")
        return DirectMedia(
            title = photo.optString("caption", "快手作品"),
            url = videoUrl,
            headers = mapOf("Referer" to "https://v.kuaishou.com/", "User-Agent" to UA),
            site = "快手",
            quality = if (images.isNotEmpty()) "图集" else quality,
            images = images,
        )
    }

    private fun fetchWithCookies(url: String): String {
        val noFollow = HttpClient.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val firstRequest = Request.Builder().url(url).header("User-Agent", UA)
            .header("Referer", "https://v.kuaishou.com/").get().build()
        val first = noFollow.newCall(firstRequest).execute()
        val location = first.header("Location")
        val cookies = first.headers.values("Set-Cookie").mapNotNull { it.substringBefore(';').takeIf { c -> c.contains('=') } }
        first.close()
        val finalUrl = when {
            location.isNullOrBlank() -> url
            location.startsWith("http") -> location
            else -> firstRequest.url.resolve(location).toString()
        }.replace("/fw/long-video/", "/fw/photo/")
        val finalBuilder = Request.Builder().url(finalUrl).header("User-Agent", UA)
            .header("Referer", "https://v.kuaishou.com/")
        if (cookies.isNotEmpty()) finalBuilder.header("Cookie", cookies.joinToString("; "))
        return HttpClient.client.newCall(finalBuilder.get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("快手页面请求失败：HTTP " + response.code)
            response.body?.string().orEmpty()
        }
    }

    private fun shiftKey(key: String): String = buildString {
        key.forEach { append((it.code - 1).toChar()) }
    }
}
