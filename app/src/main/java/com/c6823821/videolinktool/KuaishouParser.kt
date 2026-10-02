package com.c6823821.videolinktool

object KuaishouParser {
    private val urlRegex = Regex("\"url\":\"(https?[^\"]+?\\.mp4(?:\\?[^\"]*)?)\"", RegexOption.DOT_MATCHES_ALL)
    private val captionRegex = Regex("\"caption\":\"(.*?)\"")
    private val codecRegex = Regex("\"videoCodec\":\"([^\"]+)\"")
    private val sizeRegex = Regex("\"fileSize\":(\\d+)")

    fun parse(finalUrl: String): DirectMedia {
        val page = PageFetcher.fetch(finalUrl, referer = "https://v.kuaishou.com/")
        val html = page.html
        val title = JsonText.first(captionRegex, html)?.ifBlank { null } ?: "快手视频"

        data class Candidate(val url: String, val codec: String, val fileSize: Long)
        val candidates = urlRegex.findAll(html).map { match ->
            val windowStart = (match.range.first - 2500).coerceAtLeast(0)
            val windowEnd = (match.range.last + 1800).coerceAtMost(html.length)
            val window = html.substring(windowStart, windowEnd)
            val codec = codecRegex.find(window)?.groupValues?.getOrNull(1) ?: ""
            val size = sizeRegex.find(window)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            Candidate(JsonText.decode(match.groupValues[1]), codec.lowercase(), size)
        }.filter { it.url.startsWith("http") }.toList()

        if (candidates.isEmpty()) {
            throw IllegalStateException("快手作品里没有找到视频文件。图集作品暂不支持。")
        }
        val preferred = candidates.filter { it.codec == "avc" || it.codec == "h264" }
        val chosen = (preferred.ifEmpty { candidates }).maxByOrNull { it.fileSize } ?: candidates.first()
        return DirectMedia(
            title = title,
            url = chosen.url,
            headers = mapOf("Referer" to "https://v.kuaishou.com/"),
            site = "快手",
        )
    }
}
