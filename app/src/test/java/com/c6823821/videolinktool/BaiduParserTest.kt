package com.c6823821.videolinktool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduParserTest {
    @Test
    fun haokanPageFallbackFindsTitleAndPlayUrl() {
        val html = """
            {"title":"\u597d\u770b\u6d4b\u8bd5",
             "playurl":"https:\/\/vd2.bdstatic.com\/demo.mp4?auth_key=1"}
        """.trimIndent()

        val media = BaiduParser.parseHaokanHtml(html)

        assertEquals("好看测试", media.title)
        assertEquals("https://vd2.bdstatic.com/demo.mp4?auth_key=1", media.url)
        assertEquals("好看视频", media.site)
        assertTrue(media.headers["Referer"]!!.contains("haokan.baidu.com"))
    }
}
