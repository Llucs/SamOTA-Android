package com.llucs.samota.core.fus

import com.llucs.samota.core.FusConstants
import com.llucs.samota.core.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class FusSession(
    val nonce: String,
    val nkey: ByteArray,
    val authHeader: String
)

class FusClient {

    private val client = OkHttpProvider.client
    private val xmlType = "application/xml".toMediaType()

    suspend fun openSession(): FusSession = requestWithRetry("openSession") {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(FusConstants.URL_NONCE)
                .post(ByteArray(0).toRequestBody(null))
                .build()

            withTimeout(SMALL_REQUEST_TIMEOUT_MS) {
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
        }
    }

    suspend fun queryFirmware(
        session: FusSession,
        model: String,
        firmware: FirmwareParts,
        cscCode: String,
        imei: String
    ): FirmwareInfo = requestWithRetry("queryFirmware") {
        withContext(Dispatchers.IO) {
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

            withTimeout(SMALL_REQUEST_TIMEOUT_MS) {
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        if (resp.code >= 500) {
                            throw IOException("Erro HTTP ${resp.code} ao consultar firmware")
                        }
                        throw IllegalStateException("Erro HTTP ${resp.code} ao consultar firmware")
                    }
                    FusXml.parseInformResponse(body)
                }
            }
        }
    }

    suspend fun initBinary(
        session: FusSession,
        binaryName: String,
        cscCode: String
    ): Unit = requestWithRetry("initBinary") {
        withContext(Dispatchers.IO) {
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

            withTimeout(SMALL_REQUEST_TIMEOUT_MS) {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code >= 500) {
                        throw IOException("Erro HTTP ${resp.code} ao iniciar binário")
                    }
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Erro HTTP ${resp.code} ao iniciar binário")
                    }
                }
            }
        }
    }

    private suspend fun <T> requestWithRetry(label: String, block: suspend () -> T): T {
        var lastError: Throwable? = null

        for (index in RETRY_BACKOFF_MS.indices) {
            try {
                return block()
            } catch (t: Throwable) {
                lastError = t
                if (!isTransientRequestError(t) || index == RETRY_BACKOFF_MS.lastIndex) {
                    break
                }
                delay(RETRY_BACKOFF_MS[index])
            }
        }

        throw lastError ?: IllegalStateException("Falha em $label")
    }

    private fun isTransientRequestError(t: Throwable): Boolean {
        return t is IOException ||
            t is kotlinx.coroutines.TimeoutCancellationException
    }

    companion object {
        private const val SMALL_REQUEST_TIMEOUT_MS = 30_000L
        private val RETRY_BACKOFF_MS = longArrayOf(1_000L, 3_000L, 10_000L)
    }
}
