package com.c6823821.videolinktool

object XhsParser {
    private val h264MasterRegex = Regex(
        "\"h264\"\\s*:\\s*\\[.*?\"masterUrl\"\\s*:\\s*\"([^\"]+)\"",
        RegexOption.DOT_MATCHES_ALL
    )
    private val anyMasterRegex = Regex("\"masterUrl\"\\s*:\\s*\"([^\"]+)\"")
    private val noteTitleRegex = Regex(
        "\"noteData\"\\s*:\\s*\\{.*?\"title\"\\s*:\\s*\"([^\"]+)\"",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parse(finalUrl: String): DirectMedia {
        val page = PageFetcher.fetch(finalUrl, referer = "https://www.xiaohongshu.com/")
        val html = page.html
        val rawUrl = JsonText.first(h264MasterRegex, html)
            ?: JsonText.first(anyMasterRegex, html)
            ?: throw IllegalStateException("小红书页面里没有找到视频地址。当前版本只支持视频笔记，图集笔记暂不支持。")
        val url = rawUrl.replace("http://", "https://")
        val title = JsonText.first(noteTitleRegex, html)?.ifBlank { null } ?: "小红书视频"
        return DirectMedia(
            title = title,
            url = url,
            headers = mapOf(
                "Referer" to "https://www.xiaohongshu.com/",
                "User-Agent" to HttpClient.MOBILE_UA,
            ),
            site = "小红书",
        )
    }
}
