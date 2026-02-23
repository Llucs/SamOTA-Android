package com.llucs.samota.core.work

object DownloadWork {
    const val UNIQUE_NAME = "samota_download"
    const val TAG = "samota_download"

    const val CHANNEL_ID = "samota_download"
    const val NOTIF_ID = 1001

    const val KEY_MODEL = "model"
    const val KEY_FIRMWARE = "firmware"
    const val KEY_CSC = "csc"
    const val KEY_IMEI = "imei"
    const val KEY_CONNECTIONS = "connections"
    const val KEY_MAX_SPEED_MIB = "maxSpeedMiB"
    const val KEY_DECRYPT = "decrypt"

    const val KEY_STAGE = "stage"
    const val KEY_DOWNLOADED_BYTES = "downloadedBytes"
    const val KEY_TOTAL_BYTES = "totalBytes"
    const val KEY_BYTES_PER_SECOND = "bytesPerSecond"

    const val KEY_DOWNLOADED_FILE = "downloadedFile"
    const val KEY_DECRYPTED_FILE = "decryptedFile"
    const val KEY_ERROR = "error"

    const val STAGE_CHECKING = "CHECKING"
    const val STAGE_DOWNLOADING = "DOWNLOADING"
    const val STAGE_DECRYPTING = "DECRYPTING"
    const val STAGE_DONE = "DONE"
    const val STAGE_ERROR = "ERROR"
}
