package com.c6823821.videolinktool

import kotlinx.coroutines.flow.MutableStateFlow

enum class RunState { IDLE, RUNNING, SUCCESS, ERROR, NEED_COOKIE }

data class TaskUiState(
    val mode: TaskMode = TaskMode.VIDEO,
    val state: RunState = RunState.IDLE,
    val progress: Int = 0,
    val title: String = "就绪",
    val detail: String = "一次处理一个链接。完成后会在系统“下载”目录看到文件。",
    val outputUri: String? = null,
    val outputPath: String? = null,
    val text: String? = null,
)

object TaskBus {
    val state = MutableStateFlow(TaskUiState())

    fun reset(mode: TaskMode) {
        state.value = TaskUiState(mode = mode)
    }

    fun update(
        mode: TaskMode,
        stateValue: RunState,
        progress: Int,
        title: String,
        detail: String,
        outputUri: String? = null,
        outputPath: String? = null,
        text: String? = null,
    ) {
        state.value = TaskUiState(
            mode = mode,
            state = stateValue,
            progress = progress.coerceIn(0, 100),
            title = title,
            detail = detail,
            outputUri = outputUri,
            outputPath = outputPath,
            text = text,
        )
    }
}