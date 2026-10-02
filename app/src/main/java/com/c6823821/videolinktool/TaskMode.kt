package com.c6823821.videolinktool

enum class TaskMode(val key: String) {
    VIDEO("video"),
    AUDIO("audio"),
    TEXT("text");

    companion object {
        fun fromKey(value: String?): TaskMode = values().firstOrNull { it.key == value } ?: VIDEO
    }
}
