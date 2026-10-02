package com.c6823821.videolinktool

data class DirectMedia(
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val ext: String = "mp4",
    val site: String = "??",
)

sealed class ResolvedSource {
    data class Direct(val media: DirectMedia) : ResolvedSource()
    data class YoutubeDl(val titleHint: String? = null, val site: String = "??") : ResolvedSource()
}
