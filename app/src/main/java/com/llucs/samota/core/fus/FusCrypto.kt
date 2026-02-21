package com.llucs.samota.core.fus

import android.util.Base64
import com.llucs.samota.core.FusConstants
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

object FusCrypto {

    fun deriveNKey(nonce: String): ByteArray {
        val sb = StringBuilder()
        for (i in 0 until 16) {
            val idx = nonce[i].code % 16
            sb.append(FusConstants.K1[idx])
        }
        sb.append(FusConstants.K2)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun logicCheck(nonce: String, target: String): String {
        val tb = target.toByteArray(Charsets.UTF_8)
        if (tb.isEmpty()) return ""
        val out = CharArray(16)
        for (i in 0 until 16) {
            val pos = (nonce[i].code and 0xF) % tb.size
            out[i] = (tb[pos].toInt() and 0xFF).toChar()
        }
        return String(out)
    }

    fun authHeader(nonce: String, nkey: ByteArray): String {
        val key = SecretKeySpec(nkey, "AES")
        val iv = IvParameterSpec(nkey.copyOfRange(0, 16))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val sigBytes = cipher.doFinal(padPkcs7(nonce.toByteArray(Charsets.UTF_8)))
        val sigB64 = Base64.encodeToString(sigBytes, Base64.NO_WRAP)
        return "FUS nonce=\"$nonce\", signature=\"$sigB64\", nc=\"\", type=\"\", realm=\"\", newauth=\"1\""
    }

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    private fun padPkcs7(data: ByteArray, block: Int = 16): ByteArray {
        val padLen = block - (data.size % block)
        val out = ByteArray(data.size + padLen)
        System.arraycopy(data, 0, out, 0, data.size)
        for (i in data.size until out.size) out[i] = padLen.toByte()
        return out
    }
}
