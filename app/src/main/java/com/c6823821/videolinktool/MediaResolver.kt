package com.c6823821.videolinktool

import android.content.Context

object MediaResolver {
    fun resolve(context: Context, rawInput: String): ResolvedSource {
        val original = LinkExtractor.extract(rawInput) ?: throw IllegalArgumentException("没有识别到 http/https 链接")
        val finalUrl = runCatching { PageFetcher.follow(original) }.getOrDefault(original)
        val host = LinkExtractor.host(finalUrl)
        return when {
            host.contains("douyin") || host.contains("iesdouyin") ->
                ResolvedSource.Direct(DouyinParser.parse(finalUrl))
            host.contains("kuaishou") || host.contains("chenzhongtech") ->
                ResolvedSource.Direct(KuaishouParser.parse(original))
            host.contains("xiaohongshu") || host.contains("xhslink") ->
                ResolvedSource.Direct(XhsParser.parse(finalUrl))
            host.contains("baidu") || host.contains("mbd.baidu") || host.contains("mr.baidu") ->
                ResolvedSource.Direct(BaiduParser.parse(finalUrl))
            host.contains("bilibili") ->
                runCatching { ResolvedSource.Direct(BilibiliParser.parse(finalUrl)) }
                    .getOrElse { ResolvedSource.YoutubeDl(site = "哔哩哔哩") }
            host.contains("ixigua") ->
                ResolvedSource.YoutubeDl(site = "西瓜视频")
            host.contains("toutiao") ->
                ResolvedSource.YoutubeDl(site = "今日头条")
            host.contains("v.qq.com") ->
                ResolvedSource.YoutubeDl(site = "腾讯视频")
            host.contains("iqiyi") ->
                ResolvedSource.YoutubeDl(site = "爱奇艺")
            host.contains("youku") ->
                ResolvedSource.YoutubeDl(site = "优酷")
            host.contains("mgtv") ->
                ResolvedSource.YoutubeDl(site = "芒果TV")
            host.contains("weibo") ->
                ResolvedSource.YoutubeDl(site = "微博")
            host.contains("youtube") || host.contains("youtu.be") ->
                ResolvedSource.YoutubeDl(site = "YouTube")
            else -> ResolvedSource.YoutubeDl()
        }
    }
}
