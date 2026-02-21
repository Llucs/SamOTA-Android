package com.llucs.samota.core.fus

data class FirmwareInfo(
    val binaryName: String,
    val modelPath: String,
    val logicValueHome: String,
    val totalBytes: Long
)
