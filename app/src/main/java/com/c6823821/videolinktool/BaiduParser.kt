package com.c6823821.videolinktool

import org.json.JSONObject

object BaiduParser {
    private val jsonDataRegex = Regex("window\\.jsonData\\s*=\\s*(\\{.*?\\})\\s*</script>", RegexOption.DOT_MATCHES_ALL)

    fun parse(rawUrl: String): DirectMedia {
        val host = LinkExtractor.host(rawUrl)
        if (host.contains("haokan.baidu.com")) return parseHaokan(rawUrl)
        val page = PageFetcher.fetch(rawUrl, referer = "https://mbd.baidu.com/")
        val rawJson = jsonDataRegex.find(page.html)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("百度视频页面里没有找到可下载信息")
        val root = JSONObject(rawJson)
        val data = root.optJSONObject("data") ?: throw IllegalStateException("百度视频数据为空")
        val info = data.optJSONObject("videoInfo") ?: throw IllegalStateException("百度视频详情为空")
        val title = info.optString("title").ifBlank { data.optString("title", "百度视频") }

        data class Choice(val url: String, val size: Double, val label: String)
        val choices = mutableListOf<Choice>()
        val clarity = info.optJSONArray("clarityArr")
        if (clarity != null) {
            for (i in 0 until clarity.length()) {
                val item = clarity.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.isBlank()) continue
                val size = item.optString("videoSize").toDoubleOrNull() ?: 0.0
                val label = item.optString("key").uppercase().ifBlank { item.optString("title") }
                choices += Choice(url, size, label)
            }
        }
        val fallback = info.optString("play_url")
        val best = choices.maxByOrNull { it.size }
        val chosen = best?.url ?: fallback
        if (chosen.isBlank()) throw IllegalStateException("百度视频没有可用播放地址")
        return DirectMedia(
            title = title,
            url = chosen,
            headers = mapOf("Referer" to "https://mbd.baidu.com/"),
            site = "百度视频",
            quality = best?.label?.ifBlank { "原画" } ?: "原画",
        )
    }

    private fun parseHaokan(rawUrl: String): DirectMedia {
        val vid = Regex("[?&]vid=([^&]+)").find(rawUrl)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("好看视频链接缺少 vid")
        val api = "https://haokan.baidu.com/v?_format=json&vid=$vid"
        val page = PageFetcher.fetch(api, referer = "https://haokan.baidu.com/")
        val root = JSONObject(page.html)
        val meta = root.optJSONObject("data")
            ?.optJSONObject("apiData")
            ?.optJSONObject("curVideoMeta")
            ?: throw IllegalStateException("好看视频详情为空")
        val title = meta.optString("title", "好看视频")
        val arr = meta.optJSONArray("clarityUrl")
        var bestUrl = ""
        var bestSize = -1.0
        var bestLabel = ""
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val url = item.optString("url")
                val size = item.optString("videoSize").toDoubleOrNull() ?: 0.0
                if (url.startsWith("http") && size > bestSize) {
                    bestUrl = url
                    bestSize = size
                    bestLabel = item.optString("name").ifBlank { item.optString("title") }
                }
            }
        }
        if (bestUrl.isBlank()) bestUrl = meta.optString("playurl")
        if (bestUrl.isBlank()) throw IllegalStateException("好看视频没有可用播放地址")
        return DirectMedia(
            title = title,
            url = bestUrl,
            headers = mapOf("Referer" to "https://haokan.baidu.com/"),
            site = "百度好看视频",
            quality = bestLabel.ifBlank { "原画" },
        )
    }
}
