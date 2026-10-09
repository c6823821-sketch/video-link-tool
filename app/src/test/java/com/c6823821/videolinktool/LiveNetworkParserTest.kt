package com.c6823821.videolinktool

import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveNetworkParserTest {
    @Test
    fun kuaishouSharedLinkParsesAndDownloads() {
        assumeTrue(System.getenv("VIDEO_LINK_LIVE_TEST") == "1")
        val media = KuaishouParser.parse("https://v.kuaishou.com/nrLPDeU3")
        assertFalse(media.url.isBlank())

        val request = Request.Builder()
            .url(media.url)
            .header("User-Agent", media.headers["User-Agent"] ?: HttpClient.MOBILE_UA)
            .header("Referer", media.headers["Referer"] ?: "https://v.kuaishou.com/")
            .header("Range", "bytes=0-65535")
            .get()
            .build()
        HttpClient.client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            val read = response.body?.byteStream()?.read(ByteArray(65536)) ?: -1
            assertTrue(read > 0)
        }
    }

    @Test
    fun douyinVideoLinkStillParsesAndDownloads() {
        assumeTrue(System.getenv("VIDEO_LINK_LIVE_TEST") == "1")
        val media = DouyinParser.parse("https://www.douyin.com/video/7041831510181268771")
        assertFalse(media.url.isBlank())

        val request = Request.Builder()
            .url(media.url)
            .header("User-Agent", media.headers["User-Agent"] ?: HttpClient.MOBILE_UA)
            .header("Referer", media.headers["Referer"] ?: "https://www.douyin.com/")
            .header("Range", "bytes=0-65535")
            .get()
            .build()
        HttpClient.client.newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            val read = response.body?.byteStream()?.read(ByteArray(65536)) ?: -1
            assertTrue(read > 0)
        }
    }
}