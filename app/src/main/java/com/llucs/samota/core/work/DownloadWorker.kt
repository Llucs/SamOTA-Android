package com.llucs.samota.core.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.llucs.samota.R
import com.llucs.samota.core.SamotaEngine
import com.llucs.samota.core.SamotaRequest
import java.io.File

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val model = inputData.getString(DownloadWork.KEY_MODEL).orEmpty()
        val firmware = inputData.getString(DownloadWork.KEY_FIRMWARE).orEmpty()
        val csc = inputData.getString(DownloadWork.KEY_CSC).orEmpty()
        val imei = inputData.getString(DownloadWork.KEY_IMEI).orEmpty()
        val connections = inputData.getInt(DownloadWork.KEY_CONNECTIONS, 8)
        val maxSpeedMiB = inputData.getDouble(DownloadWork.KEY_MAX_SPEED_MIB, 0.0)
        val decrypt = inputData.getBoolean(DownloadWork.KEY_DECRYPT, true)

        val outDir = File(applicationContext.getExternalFilesDir(null), "downloads")
        outDir.mkdirs()

        val engine = SamotaEngine()

        try {
            ensureChannel()
            setForeground(createForeground("Preparando…", 0L, 0L))

            val request = SamotaRequest(
                model = model.trim(),
                firmware = firmware.trim(),
                csc = csc.trim(),
                imei = imei.trim(),
                connections = connections.coerceIn(1, 32),
                maxSpeedMiB = maxSpeedMiB.coerceAtLeast(0.0),
                decrypt = decrypt
            )

            var lastNotifUpdate = 0L
            var lastStage = DownloadWork.STAGE_CHECKING
            var lastDownloaded = 0L
            var lastTotal = 0L
            val result = engine.download(
                request = request,
                outputDir = outDir,
                onStage = { stage ->
                    lastStage = stage
                    setProgressAsync(
                        Data.Builder()
                            .putString(DownloadWork.KEY_STAGE, stage)
                            .build()
                    )

                    val title = when (stage) {
                        DownloadWork.STAGE_CHECKING -> "Verificando…"
                        DownloadWork.STAGE_DOWNLOADING -> "Baixando…"
                        DownloadWork.STAGE_DECRYPTING -> "Decriptando…"
                        else -> "Processando…"
                    }
                    setForegroundAsync(createForeground(title, lastDownloaded, lastTotal))
                },
                onProgress = { p ->
                    val now = System.currentTimeMillis()
                    lastDownloaded = p.downloadedBytes
                    lastTotal = p.totalBytes
                    setProgressAsync(
                        Data.Builder()
                            .putString(DownloadWork.KEY_STAGE, lastStage)
                            .putLong(DownloadWork.KEY_DOWNLOADED_BYTES, p.downloadedBytes)
                            .putLong(DownloadWork.KEY_TOTAL_BYTES, p.totalBytes)
                            .putLong(DownloadWork.KEY_BYTES_PER_SECOND, p.bytesPerSecond)
                            .build()
                    )

                    if (now - lastNotifUpdate >= 1200L) {
                        lastNotifUpdate = now
                        val title = when (lastStage) {
                            DownloadWork.STAGE_DOWNLOADING -> "Baixando…"
                            else -> "Processando…"
                        }
                        setForegroundAsync(createForeground(title, p.downloadedBytes, p.totalBytes))
                    }
                }
            )

            setForeground(createForeground("Concluído", result.firmwareInfo.totalBytes, result.firmwareInfo.totalBytes))

            val output = Data.Builder()
                .putString(DownloadWork.KEY_STAGE, DownloadWork.STAGE_DONE)
                .putLong(DownloadWork.KEY_DOWNLOADED_BYTES, result.firmwareInfo.totalBytes)
                .putLong(DownloadWork.KEY_TOTAL_BYTES, result.firmwareInfo.totalBytes)
                .putString(DownloadWork.KEY_DOWNLOADED_FILE, result.downloadedFile.absolutePath)
                .putString(DownloadWork.KEY_DECRYPTED_FILE, result.decryptedFile?.absolutePath)
                .build()

            return Result.success(output)
        } catch (e: Exception) {
            val msg = e.message ?: "Erro"
            val output = Data.Builder()
                .putString(DownloadWork.KEY_STAGE, DownloadWork.STAGE_ERROR)
                .putString(DownloadWork.KEY_ERROR, msg)
                .build()
            return Result.failure(output)
        }
    }

    private fun createForeground(title: String, downloaded: Long, total: Long): ForegroundInfo {
        val notificationId = DownloadWork.NOTIF_ID
        val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        val indeterminate = total <= 0L

        val notification = NotificationCompat.Builder(applicationContext, DownloadWork.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("SamOTA")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(notificationId, notification, serviceType)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(DownloadWork.CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            DownloadWork.CHANNEL_ID,
            applicationContext.getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = applicationContext.getString(R.string.download_channel_desc)
        nm.createNotificationChannel(channel)
    }
}
