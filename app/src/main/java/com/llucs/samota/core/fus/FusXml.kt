package com.llucs.samota.core.fus

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

object FusXml {

    fun buildInformXml(
        model: String,
        cscCode: String,
        imei: String,
        fw: FirmwareParts,
        logicCheck: String
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<FUSMsg>
    <FUSHdr><ProtoVer>1.0</ProtoVer></FUSHdr>
    <FUSBody>
        <Put>
            <ACCESS_MODE><Data>2</Data></ACCESS_MODE>
            <BINARY_NATURE><Data>1</Data></BINARY_NATURE>
            <CLIENT_PRODUCT><Data>Smart Switch</Data></CLIENT_PRODUCT>
            <CLIENT_VERSION><Data>4.3.23123_1</Data></CLIENT_VERSION>
            <DEVICE_FW_VERSION><Data>${fw.full}</Data></DEVICE_FW_VERSION>
            <DEVICE_LOCAL_CODE><Data>${cscCode}</Data></DEVICE_LOCAL_CODE>
            <DEVICE_MODEL_NAME><Data>${model}</Data></DEVICE_MODEL_NAME>
            <DEVICE_IMEI_PUSH><Data>${imei}</Data></DEVICE_IMEI_PUSH>
            <DEVICE_VER_COUNT><Data>4</Data></DEVICE_VER_COUNT>
            <DEVICE_PDA_CODE1_VERSION><Data>${fw.pda}</Data></DEVICE_PDA_CODE1_VERSION>
            <DEVICE_CSC_CODE2_VERSION><Data>${fw.csc}</Data></DEVICE_CSC_CODE2_VERSION>
            <DEVICE_PHONE_FONT_VERSION><Data>${fw.phone}</Data></DEVICE_PHONE_FONT_VERSION>
            <DEVICE_CONTENTS_DATA_VERSION><Data>${fw.pda}</Data></DEVICE_CONTENTS_DATA_VERSION>
            <LOGIC_CHECK><Data>${logicCheck}</Data></LOGIC_CHECK>
        </Put>
        <Get><CmdID>2</CmdID><LATEST_FW_VERSION/></Get>
    </FUSBody>
</FUSMsg>"""

    fun buildInitXml(
        binaryName: String,
        cscCode: String,
        logicCheck: String
    ): String = """<?xml version="1.0" encoding="UTF-8"?>
<FUSMsg>
    <FUSHdr><ProtoVer>1.0</ProtoVer></FUSHdr>
    <FUSBody>
        <Put>
            <BINARY_FILE_NAME><Data>${binaryName}</Data></BINARY_FILE_NAME>
            <BINARY_NATURE><Data>1</Data></BINARY_NATURE>
            <DEVICE_LOCAL_CODE><Data>${cscCode}</Data></DEVICE_LOCAL_CODE>
            <DEVICE_MODEL_TYPE><Data>1</Data></DEVICE_MODEL_TYPE>
            <LOGIC_CHECK><Data>${logicCheck}</Data></LOGIC_CHECK>
        </Put>
        <Get><CmdID>2</CmdID></Get>
    </FUSBody>
</FUSMsg>"""

    fun parseInformResponse(xmlText: String): FirmwareInfo {
        val doc = parse(xmlText)
        val status = firstText(doc, "Status")?.trim()
        if (status != "200") {
            throw IllegalStateException("Falha na consulta do firmware (Status ${status ?: "desconhecido"})")
        }
        val binName = firstDataText(doc, "BINARY_NAME")
        val modelPath = firstDataText(doc, "MODEL_PATH")
        val logicValue = firstDataText(doc, "LOGIC_VALUE_HOME")
        val size = firstDataText(doc, "BINARY_BYTE_SIZE").toLong()
        val foundVersion = firstDataTextOrNull(doc, "LATEST_FW_VERSION")
        val expectedMd5 = readExpectedMd5(doc)

        return FirmwareInfo(
            binaryName = binName,
            modelPath = modelPath,
            logicValueHome = logicValue,
            totalBytes = size,
            expectedMd5 = expectedMd5,
            foundVersion = foundVersion
        )
    }

    private fun parse(xml: String): Document {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = false
        dbf.isExpandEntityReferences = false
        val builder = dbf.newDocumentBuilder()
        return builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun firstText(doc: Document, tag: String): String? {
        val node = doc.getElementsByTagName(tag).item(0) ?: return null
        return node.textContent
    }

    private fun firstDataText(doc: Document, outerTag: String): String {
        val node = doc.getElementsByTagName(outerTag).item(0) as? Element
            ?: throw IllegalStateException("Campo ausente: $outerTag")
        val data = node.getElementsByTagName("Data").item(0)
            ?: throw IllegalStateException("Campo ausente: $outerTag/Data")
        return data.textContent.trim()
    }

    private fun firstDataTextOrNull(doc: Document, outerTag: String): String? {
        val node = doc.getElementsByTagName(outerTag).item(0) as? Element ?: return null
        val data = node.getElementsByTagName("Data").item(0) ?: return null
        return data.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun readExpectedMd5(doc: Document): String? {
        val candidates = listOf(
            "BINARY_MD5",
            "BINARY_FILE_MD5",
            "FILE_MD5",
            "MD5"
        )

        for (tag in candidates) {
            val normalized = normalizeMd5(firstDataTextOrNull(doc, tag) ?: firstText(doc, tag))
            if (normalized != null) return normalized
        }

        return null
    }

    private fun normalizeMd5(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim().trim('"')

        if (trimmed.length == 32 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return trimmed.uppercase()
        }

        return try {
            val bytes = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
            if (bytes.size == 16) bytes.joinToString("") { b -> "%02X".format(b.toInt() and 0xFF) } else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
