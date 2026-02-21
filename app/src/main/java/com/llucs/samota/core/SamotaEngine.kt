package com.llucs.samota.core

import com.llucs.samota.core.crypto.FirmwareDecryptor
import com.llucs.samota.core.download.DownloadProgress
import com.llucs.samota.core.download.SegmentedDownloader
import com.llucs.samota.core.fus.FirmwareInfo
import com.llucs.samota.core.fus.FirmwareParts
import com.llucs.samota.core.fus.FusClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        onProgress: (DownloadProgress) -> Unit
    ): SamotaResult = withContext(Dispatchers.IO) {
        validate(request)
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

        val url = FusConstants.URL_DOWNLOAD + info.modelPath + info.binaryName
        val target = File(outputDir, info.binaryName)

        downloader.download(
            url = url,
            authHeader = session.authHeader,
            target = target,
            totalBytes = info.totalBytes,
            maxConnections = request.connections,
            maxSpeedMiB = request.maxSpeedMiB,
            onProgress = onProgress
        )

        val decrypted = if (request.decrypt) {
            FirmwareDecryptor.decryptIfNeeded(
                inputFile = target,
                binaryName = info.binaryName,
                logicValueHome = info.logicValueHome,
                firmwareFull = fw.full
            )
        } else null

        SamotaResult(
            firmwareInfo = info,
            downloadedFile = target,
            decryptedFile = decrypted
        )
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
