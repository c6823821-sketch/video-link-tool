package com.c6823821.videolinktool

import okhttp3.Request

object PageFetcher {
    data class Page(val finalUrl: String, val html: String)

    fun fetch(url: String, referer: String = "", headers: Map<String, String> = emptyMap(), userAgent: String = HttpClient.MOBILE_UA): Page {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (referer.isNotBlank()) builder.header("Referer", referer)
        headers.forEach { (key, value) -> builder.header(key, value) }
        HttpClient.client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("网页请求失败：HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return Page(response.request.url.toString(), body)
        }
    }

    fun follow(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClient.MOBILE_UA)
            .get()
            .build()
        HttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("链接跳转失败：HTTP ${response.code}")
            return response.request.url.toString()
        }
    }
}

object JsonText {
    private val unicodeRegex = Regex("\\u([0-9a-fA-F]{4})")

    fun decode(value: String): String {
        var text = value
            .replace("\\/", "/")
            .replace("&amp;", "&")
        text = unicodeRegex.replace(text) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return text.replace("\\\"", "\"").trim()
    }

    fun first(regex: Regex, text: String): String? =
        regex.find(text)?.groupValues?.getOrNull(1)?.let(::decode)
}
