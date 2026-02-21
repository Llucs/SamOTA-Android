package com.llucs.samota.core.fus

import com.llucs.samota.core.FusConstants
import com.llucs.samota.core.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class FusSession(
    val nonce: String,
    val nkey: ByteArray,
    val authHeader: String
)

class FusClient {

    private val client = OkHttpProvider.client
    private val xmlType = "application/xml".toMediaType()

    suspend fun openSession(): FusSession = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(FusConstants.URL_NONCE)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        client.newCall(req).execute().use { resp ->
            val nonce = resp.header("NONCE") ?: resp.header("Nonce")
            if (nonce.isNullOrBlank()) {
                throw IllegalStateException("Falha ao obter NONCE")
            }
            val nkey = FusCrypto.deriveNKey(nonce)
            val auth = FusCrypto.authHeader(nonce, nkey)
            FusSession(nonce = nonce, nkey = nkey, authHeader = auth)
        }
    }

    suspend fun queryFirmware(
        session: FusSession,
        model: String,
        firmware: FirmwareParts,
        cscCode: String,
        imei: String
    ): FirmwareInfo = withContext(Dispatchers.IO) {
        val lc = FusCrypto.logicCheck(session.nonce, firmware.full)
        val xml = FusXml.buildInformXml(
            model = model,
            cscCode = cscCode,
            imei = imei,
            fw = firmware,
            logicCheck = lc
        )

        val req = Request.Builder()
            .url(FusConstants.URL_INFORM)
            .header("Authorization", session.authHeader)
            .header("Content-Type", "application/xml")
            .post(xml.toRequestBody(xmlType))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IllegalStateException("Erro HTTP ${resp.code} ao consultar firmware")
            }
            FusXml.parseInformResponse(body)
        }
    }

    suspend fun initBinary(
        session: FusSession,
        binaryName: String,
        cscCode: String
    ): Unit = withContext(Dispatchers.IO) {
        val lc = FusCrypto.logicCheck(session.nonce, binaryName)
        val xml = FusXml.buildInitXml(
            binaryName = binaryName,
            cscCode = cscCode,
            logicCheck = lc
        )

        val req = Request.Builder()
            .url(FusConstants.URL_INIT)
            .header("Authorization", session.authHeader)
            .header("Content-Type", "application/xml")
            .post(xml.toRequestBody(xmlType))
            .build()

        client.newCall(req).execute().close()
    }
}
