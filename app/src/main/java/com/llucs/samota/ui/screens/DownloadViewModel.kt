package com.llucs.samota.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.llucs.samota.core.SamotaEngine
import com.llucs.samota.core.SamotaRequest
import com.llucs.samota.core.work.DownloadWork
import com.llucs.samota.core.work.DownloadWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = SamotaEngine()
    private val wm = WorkManager.getInstance(app)
    private var checkJob: Job? = null

    var state by mutableStateOf(DownloadUiState())
        private set

    init {
        observeDownloadWork()
    }

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

    fun setUserMessage(msg: String?) = update { it.copy(message = msg) }

    fun cancel() {
        checkJob?.cancel()
        checkJob = null
        wm.cancelUniqueWork(DownloadWork.UNIQUE_NAME)
    }

    fun check() {
        if (state.busy) return
        checkJob?.cancel()
        update { it.copy(busy = true, stage = Stage.Checking, message = null, lastOutput = null) }
        checkJob = viewModelScope.launch {
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

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val input = workDataOf(
            DownloadWork.KEY_MODEL to state.model.trim(),
            DownloadWork.KEY_FIRMWARE to state.firmware.trim(),
            DownloadWork.KEY_CSC to state.csc.trim(),
            DownloadWork.KEY_IMEI to state.imei.trim(),
            DownloadWork.KEY_CONNECTIONS to state.connections.coerceIn(1, 32),
            DownloadWork.KEY_MAX_SPEED_MIB to state.maxSpeedMiB.coerceAtLeast(0.0),
            DownloadWork.KEY_DECRYPT to state.decrypt
        )

        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(DownloadWork.TAG)
            .setConstraints(constraints)
            .setInputData(input)
            .build()

        val outDir = File(getApplication<Application>().getExternalFilesDir(null), "downloads")
        outDir.mkdirs()

        update {
            it.copy(
                busy = true,
                stage = Stage.Downloading,
                message = "Download em segundo plano: ${outDir.absolutePath}",
                lastOutput = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                bytesPerSecond = 0L
            )
        }

        wm.enqueueUniqueWork(DownloadWork.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    private fun observeDownloadWork() {
        viewModelScope.launch {
            wm.getWorkInfosForUniqueWorkFlow(DownloadWork.UNIQUE_NAME).collectLatest { infos ->
                val info = infos.firstOrNull() ?: return@collectLatest
                applyWorkInfo(info)
            }
        }
    }

    private fun applyWorkInfo(info: WorkInfo) {
        val progress = info.progress
        val output = info.outputData

        fun stageFrom(str: String?): Stage = when (str) {
            DownloadWork.STAGE_CHECKING -> Stage.Checking
            DownloadWork.STAGE_DECRYPTING -> Stage.Decrypting
            DownloadWork.STAGE_DOWNLOADING -> Stage.Downloading
            DownloadWork.STAGE_DONE -> Stage.Done
            DownloadWork.STAGE_ERROR -> Stage.Error
            else -> Stage.Downloading
        }

        when (info.state) {
            WorkInfo.State.ENQUEUED -> {
                update { it.copy(busy = true, stage = Stage.Downloading, message = "Aguardando início…") }
            }
            WorkInfo.State.RUNNING -> {
                val stage = stageFrom(progress.getString(DownloadWork.KEY_STAGE))
                val downloaded = progress.getLong(DownloadWork.KEY_DOWNLOADED_BYTES, state.downloadedBytes)
                val total = progress.getLong(DownloadWork.KEY_TOTAL_BYTES, state.totalBytes)
                val bps = progress.getLong(DownloadWork.KEY_BYTES_PER_SECOND, state.bytesPerSecond)
                update {
                    it.copy(
                        busy = true,
                        stage = stage,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        bytesPerSecond = bps,
                        message = null
                    )
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                val stage = stageFrom(output.getString(DownloadWork.KEY_STAGE))
                val downloadedFile = output.getString(DownloadWork.KEY_DOWNLOADED_FILE)
                val decryptedFile = output.getString(DownloadWork.KEY_DECRYPTED_FILE)
                val total = output.getLong(DownloadWork.KEY_TOTAL_BYTES, state.totalBytes)
                val downloaded = output.getLong(DownloadWork.KEY_DOWNLOADED_BYTES, total)

                val outText = buildString {
                    if (!downloadedFile.isNullOrBlank()) {
                        val f = File(downloadedFile)
                        append("Arquivo: ").append(f.name).append('\n')
                        append("Pasta: ").append(f.parentFile?.absolutePath ?: "").append('\n')
                    }
                    if (!decryptedFile.isNullOrBlank()) {
                        val df = File(decryptedFile)
                        append("Decriptado: ").append(df.name).append('\n')
                    }
                    append("IMEI: ").append(SamotaEngine.maskImei(state.imei))
                }

                update {
                    it.copy(
                        busy = false,
                        stage = stage,
                        message = "Concluído",
                        lastOutput = outText,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        bytesPerSecond = 0L
                    )
                }
            }
            WorkInfo.State.FAILED -> {
                val msg = output.getString(DownloadWork.KEY_ERROR) ?: "Erro"
                update { it.copy(busy = false, stage = Stage.Error, message = msg) }
            }
            WorkInfo.State.CANCELLED -> {
                update { it.copy(busy = false, stage = Stage.Idle, message = "Cancelado", bytesPerSecond = 0L) }
            }
            WorkInfo.State.BLOCKED -> Unit
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
