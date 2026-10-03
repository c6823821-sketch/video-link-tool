package com.c6823821.videolinktool

import org.json.JSONObject

object BilibiliParser {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val bvRegex = Regex("(BV[0-9A-Za-z]+)")
    private val avRegex = Regex("av(\\d+)", RegexOption.IGNORE_CASE)

    fun parse(finalUrl: String): DirectMedia {
        val bvid = bvRegex.find(finalUrl)?.groupValues?.getOrNull(1)
        val aid = avRegex.find(finalUrl)?.groupValues?.getOrNull(1)
        if (bvid == null && aid == null) throw IllegalStateException("B站链接里没有作品 ID")
        val viewUrl = if (bvid != null) {
            "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        } else {
            "https://api.bilibili.com/x/web-interface/view?aid=$aid"
        }
        val view = JSONObject(PageFetcher.fetch(viewUrl, referer = "https://www.bilibili.com/", userAgent = UA).html)
        if (view.optInt("code", -1) != 0) throw IllegalStateException("B站详情接口失败：" + view.optString("message"))
        val data = view.optJSONObject("data") ?: throw IllegalStateException("B站详情为空")
        val realBvid = data.optString("bvid")
        val cid = data.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid")
            ?: data.optLong("cid")
        if (cid <= 0) throw IllegalStateException("B站没有找到 cid")
        val title = data.optString("title", "B站视频")
        val playUrl = "https://api.bilibili.com/x/player/playurl?bvid=$realBvid&cid=$cid&qn=80&fnval=1&fourk=1&platform=html5&high_quality=1"
        val play = JSONObject(PageFetcher.fetch(playUrl, referer = "https://www.bilibili.com/video/$realBvid", userAgent = UA).html)
        val durl = play.optJSONObject("data")?.optJSONArray("durl")
            ?: throw IllegalStateException("B站没有返回可下载地址")
        val direct = durl.optJSONObject(0)?.optString("url").orEmpty()
        if (direct.isBlank()) throw IllegalStateException("B站播放地址为空")
        return DirectMedia(
            title = title,
            url = direct,
            headers = mapOf(
                "Referer" to "https://www.bilibili.com/",
                "User-Agent" to UA,
            ),
            site = "哔哩哔哩",
        )
    }
}
