package dev.warp.mobile

enum class ProcessState {
    INITIALIZING,
    RUNNING,
    EXITED,
    ERROR
}

data class SessionTab(
    val id: String,
    val title: String,
    val cwd: String = "~",
    val processState: ProcessState = ProcessState.INITIALIZING,
    val exitCode: Int? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val program: String = "/system/bin/sh",
    val env: Map<String, String> = emptyMap()
)
