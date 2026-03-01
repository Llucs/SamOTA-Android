package com.llucs.samota.core

import com.llucs.samota.core.crypto.FirmwareDecryptor
import com.llucs.samota.core.download.DownloadProgress
import com.llucs.samota.core.download.SegmentedDownloader
import com.llucs.samota.core.fus.FirmwareInfo
import com.llucs.samota.core.fus.FirmwareParts
import com.llucs.samota.core.fus.FusClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class SamotaRequest(
    val model: String,
    val firmware: String,
    val csc: String,
    val imei: String,
    val connections: Int,
    val maxSpeedMiB: Double,
    val decrypt: Boolean
)

data class SamotaResult(
    val firmwareInfo: FirmwareInfo,
    val downloadedFile: File,
    val decryptedFile: File?
)

class SamotaEngine(
    private val fus: FusClient = FusClient(),
    private val downloader: SegmentedDownloader = SegmentedDownloader()
) {

    suspend fun check(request: SamotaRequest): FirmwareInfo {
        validate(request)
        val fw = FirmwareParts.parse(request.firmware)
        val session = fus.openSession()
        return fus.queryFirmware(
            session = session,
            model = request.model,
            firmware = fw,
            cscCode = request.csc,
            imei = request.imei
        )
    }

    suspend fun download(
        request: SamotaRequest,
        outputDir: File,
        onProgress: (DownloadProgress) -> Unit,
        onStage: (String) -> Unit = {}
    ): SamotaResult = withContext(Dispatchers.IO) {
        validate(request)
        onStage(EngineStage.CHECKING)
        val fw = FirmwareParts.parse(request.firmware)

        val session = fus.openSession()
        val info = fus.queryFirmware(
            session = session,
            model = request.model,
            firmware = fw,
            cscCode = request.csc,
            imei = request.imei
        )
        fus.initBinary(session, info.binaryName, request.csc)

        onStage(EngineStage.DOWNLOADING)

        val url = FusConstants.URL_DOWNLOAD + info.modelPath + info.binaryName
        val target = File(outputDir, sanitizeFileName(info.binaryName))

        saveLastMetadata(
            outputDir = outputDir,
            info = info,
            url = url,
            md5Override = info.expectedMd5
        )

        val downloadResult = downloader.download(
            url = url,
            authHeader = session.authHeader,
            target = target,
            totalBytes = info.totalBytes,
            maxConnections = request.connections,
            maxSpeedMiB = request.maxSpeedMiB,
            expectedMd5 = info.expectedMd5,
            onProgress = onProgress
        )

        if (info.expectedMd5.isNullOrBlank() && !downloadResult.headerMd5.isNullOrBlank()) {
            saveLastMetadata(
                outputDir = outputDir,
                info = info,
                url = url,
                md5Override = downloadResult.headerMd5
            )
        }

        val decrypted = if (request.decrypt) {
            onStage(EngineStage.DECRYPTING)
            FirmwareDecryptor.decryptIfNeeded(
                inputFile = target,
                binaryName = info.binaryName,
                logicValueHome = info.logicValueHome,
                firmwareFull = fw.full
            )
        } else null

        onStage(EngineStage.DONE)

        SamotaResult(
            firmwareInfo = info,
            downloadedFile = target,
            decryptedFile = decrypted
        )
    }

    object EngineStage {
        const val CHECKING = "CHECKING"
        const val DOWNLOADING = "DOWNLOADING"
        const val DECRYPTING = "DECRYPTING"
        const val DONE = "DONE"
    }

    private fun validate(request: SamotaRequest) {
        val imei = request.imei.trim()
        if (imei.length < 15 || !imei.all { it.isDigit() }) {
            throw IllegalArgumentException("IMEI precisa ter pelo menos 15 dígitos")
        }
        if (request.model.isBlank()) throw IllegalArgumentException("Modelo é obrigatório")
        if (request.csc.isBlank()) throw IllegalArgumentException("CSC é obrigatório")
        if (request.firmware.isBlank()) throw IllegalArgumentException("Firmware é obrigatório")
    }

    private fun sanitizeFileName(rawName: String): String {
        if (rawName.isBlank()) return "firmware.bin.enc4"

        val base = File(rawName).name
        val sb = StringBuilder(base.length)
        for (ch in base) {
            val safe = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '.' || ch == '_' || ch == '-'
            if (safe) {
                sb.append(ch)
            } else if (sb.isEmpty() || sb.last() != '_') {
                sb.append('_')
            }
        }

        val sanitized = sb.toString().trim('_', '.')
        return if (sanitized.isBlank()) "firmware.bin.enc4" else sanitized
    }

    private fun saveLastMetadata(
        outputDir: File,
        info: FirmwareInfo,
        url: String,
        md5Override: String?
    ) {
        outputDir.mkdirs()
        val payload = JSONObject().apply {
            put("foundVersion", info.foundVersion ?: JSONObject.NULL)
            put("md5", (md5Override ?: info.expectedMd5) ?: JSONObject.NULL)
            put("size", info.totalBytes)
            put("url", url)
            put("timestamp", Instant.now().toString())
        }
        File(outputDir, "last.json").writeText(payload.toString(2))
    }

    companion object {
        fun maskImei(imei: String): String {
            val t = imei.trim()
            if (t.length <= 6) return "******"
            val pre = t.take(2)
            val suf = t.takeLast(2)
            return pre + "*".repeat(t.length - 4) + suf
        }
    }
}
