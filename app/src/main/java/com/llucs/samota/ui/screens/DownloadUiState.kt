package com.llucs.samota.ui.screens

data class DownloadUiState(
    val model: String = "",
    val firmware: String = "",
    val csc: String = "",
    val imei: String = "",
    val connections: Int = 8,
    val maxSpeedMiB: Double = 0.0,
    val decrypt: Boolean = true,

    val busy: Boolean = false,
    val stage: Stage = Stage.Idle,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,

    val message: String? = null,
    val lastOutput: String? = null
)

enum class Stage {
    Idle, Checking, Downloading, Decrypting, Done, Error
}
