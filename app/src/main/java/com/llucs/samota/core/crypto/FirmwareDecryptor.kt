package com.llucs.samota.core.crypto

import com.llucs.samota.core.fus.FusCrypto
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object FirmwareDecryptor {

    fun needsDecrypt(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".enc4") || lower.endsWith(".enc2")
    }

    fun decryptIfNeeded(
        inputFile: File,
        binaryName: String,
        logicValueHome: String,
        firmwareFull: String
    ): File? {
        if (!needsDecrypt(binaryName)) return null

        val keyStr = FusCrypto.logicCheck(logicValueHome, firmwareFull)
        val key = FusCrypto.md5(keyStr.toByteArray(Charsets.UTF_8))

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))

        var outName = binaryName
            .replace(".enc4", "", ignoreCase = true)
            .replace(".enc2", "", ignoreCase = true)
        if (!outName.lowercase().endsWith(".zip")) outName += ".zip"

        val outFile = File(inputFile.parentFile, outName)

        inputFile.inputStream().buffered(1024 * 1024).use { fin ->
            outFile.outputStream().buffered(1024 * 1024).use { fout ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val n = fin.read(buf)
                    if (n <= 0) break
                    if (n % 16 != 0) throw IllegalStateException("Tamanho inválido para AES (não múltiplo de 16)")
                    val block = if (n == buf.size) buf else buf.copyOf(n)
                    val dec = cipher.doFinal(block)
                    fout.write(dec)
                }
                fout.flush()
            }
        }

        return outFile
    }
}
