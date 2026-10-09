package com.c6823821.videolinktool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinParserTest {
    @Test
    fun parsesNewImagePostLivePhotosAndMusic() {
        val detail = JSONObject(
            """
            {
              "desc": "实况图集",
              "image_post_info": {
                "images": [
                  {
                    "display_image": {
                      "url_list": ["https://cdn.example.com/01.webp"]
                    },
                    "video": {
                      "play_addr": {
                        "url_list": ["https://cdn.example.com/01_live.mp4"],
                        "width": 1080,
                        "height": 1920
                      }
                    }
                  },
                  {
                    "video": {
                      "play_addr": {
                        "url_list": ["https://cdn.example.com/02_live.mp4"]
                      }
                    }
                  }
                ]
              },
              "music": {
                "play_url": {
                  "url_list": ["https://cdn.example.com/background.mp3"]
                }
              }
            }
            """.trimIndent()
        )

        val media = DouyinParser.buildForTest(detail)

        assertEquals("图集", media.quality)
        assertEquals(2, media.images.size)
        assertEquals("https://cdn.example.com/01.webp", media.images[0].url)
        assertEquals("webp", media.images[0].ext)
        assertEquals("https://cdn.example.com/01_live.mp4", media.images[0].liveUrl)
        assertEquals("https://cdn.example.com/02_live.mp4", media.images[1].liveUrl)
        assertEquals("https://cdn.example.com/background.mp3", media.audioUrl)
        assertEquals("mp3", media.audioExt)
    }

    @Test
    fun keepsSupportForLegacyImagesArray() {
        val detail = JSONObject(
            """
            {
              "desc": "旧图集",
              "images": [
                {
                  "url_list": ["https://cdn.example.com/legacy-1.jpg"],
                  "download_url_list": ["https://cdn.example.com/legacy-1-original.jpg"]
                },
                {
                  "url_list": ["https://cdn.example.com/legacy-2.png"]
                }
              ]
            }
            """.trimIndent()
        )

        val media = DouyinParser.buildForTest(detail)

        assertEquals(2, media.images.size)
        assertEquals("https://cdn.example.com/legacy-1.jpg", media.images[0].url)
        assertEquals("png", media.images[1].ext)
        assertTrue(media.images.all { it.liveUrl.isBlank() })
    }
}