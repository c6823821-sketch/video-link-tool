package com.c6823821.videolinktool

data class ImageItem(
    val url: String,
    val ext: String = "jpg",
    val liveUrl: String = "",
    val liveExt: String = "mp4",
)

data class DirectMedia(
    val title: String,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val ext: String = "mp4",
    val site: String = "平台",
    val quality: String = "",
    val images: List<ImageItem> = emptyList(),
    val audioUrl: String = "",
    val audioExt: String = "mp3",
)

sealed class ResolvedSource {
    data class Direct(val media: DirectMedia) : ResolvedSource()
    data class YoutubeDl(val titleHint: String? = null, val site: String = "通用") : ResolvedSource()
}
