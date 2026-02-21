package com.llucs.samota.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llucs.samota.core.SamotaEngine
import com.llucs.samota.core.SamotaRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = SamotaEngine()
    private var job: Job? = null

    var state by mutableStateOf(DownloadUiState())
        private set

    private fun update(block: (DownloadUiState) -> DownloadUiState) {
        state = block(state)
    }

    fun setModel(v: String) = update { it.copy(model = v) }
    fun setFirmware(v: String) = update { it.copy(firmware = v) }
    fun setCsc(v: String) = update { it.copy(csc = v) }
    fun setImei(v: String) = update { it.copy(imei = v) }
    fun setConnections(v: Int) = update { it.copy(connections = v) }
    fun setMaxSpeed(v: Double) = update { it.copy(maxSpeedMiB = v) }
    fun setDecrypt(v: Boolean) = update { it.copy(decrypt = v) }

    fun cancel() {
        job?.cancel()
        job = null
        update { it.copy(busy = false, stage = Stage.Idle, message = "Cancelado") }
    }

    fun check() {
        if (state.busy) return
        job?.cancel()
        update { it.copy(busy = true, stage = Stage.Checking, message = null, lastOutput = null) }
        job = viewModelScope.launch {
            try {
                val info = engine.check(state.toRequest())
                update {
                    it.copy(
                        busy = false,
                        stage = Stage.Done,
                        totalBytes = info.totalBytes,
                        downloadedBytes = info.totalBytes,
                        message = "Encontrado: ${info.binaryName}",
                        lastOutput = "MODEL_PATH: ${info.modelPath}\nTamanho: ${formatBytes(info.totalBytes)}"
                    )
                }
            } catch (e: Exception) {
                update { it.copy(busy = false, stage = Stage.Error, message = e.message ?: "Erro") }
            }
        }
    }

    fun download() {
        if (state.busy) return
        job?.cancel()
        val outDir = File(getApplication<Application>().getExternalFilesDir(null), "downloads")
        outDir.mkdirs()

        update {
            it.copy(
                busy = true,
                stage = Stage.Downloading,
                message = "Baixando para: ${outDir.absolutePath}",
                lastOutput = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                bytesPerSecond = 0L
            )
        }

        job = viewModelScope.launch {
            try {
                val result = engine.download(
                    request = state.toRequest(),
                    outputDir = outDir,
                    onProgress = { p ->
                        update {
                            it.copy(
                                stage = Stage.Downloading,
                                downloadedBytes = p.downloadedBytes,
                                totalBytes = p.totalBytes,
                                bytesPerSecond = p.bytesPerSecond
                            )
                        }
                    }
                )

                val outText = buildString {
                    append("Arquivo: ").append(result.downloadedFile.name).append('\n')
                    append("Pasta: ").append(result.downloadedFile.parentFile?.absolutePath ?: "").append('\n')
                    if (result.decryptedFile != null) {
                        append("Decriptado: ").append(result.decryptedFile.name).append('\n')
                    }
                    append("IMEI: ").append(SamotaEngine.maskImei(state.imei))
                }

                update {
                    it.copy(
                        busy = false,
                        stage = Stage.Done,
                        message = "Concluído",
                        lastOutput = outText,
                        downloadedBytes = result.firmwareInfo.totalBytes,
                        totalBytes = result.firmwareInfo.totalBytes,
                        bytesPerSecond = 0L
                    )
                }
            } catch (e: Exception) {
                update { it.copy(busy = false, stage = Stage.Error, message = e.message ?: "Erro") }
            }
        }
    }

    private fun DownloadUiState.toRequest(): SamotaRequest = SamotaRequest(
        model = model.trim(),
        firmware = firmware.trim(),
        csc = csc.trim(),
        imei = imei.trim(),
        connections = connections.coerceIn(1, 32),
        maxSpeedMiB = maxSpeedMiB.coerceAtLeast(0.0),
        decrypt = decrypt
    )

    companion object {
        fun formatBytes(bytes: Long): String {
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            return when {
                bytes >= gb -> String.format("%.2f GiB", bytes / gb)
                bytes >= mb -> String.format("%.1f MiB", bytes / mb)
                bytes >= kb -> String.format("%.1f KiB", bytes / kb)
                else -> "$bytes B"
            }
        }

        fun formatSpeed(bps: Long): String {
            if (bps <= 0) return "0 B/s"
            val kb = 1024.0
            val mb = kb * 1024.0
            return when {
                bps >= mb -> String.format("%.1f MiB/s", bps / mb)
                bps >= kb -> String.format("%.1f KiB/s", bps / kb)
                else -> "$bps B/s"
            }
        }
    }
}
