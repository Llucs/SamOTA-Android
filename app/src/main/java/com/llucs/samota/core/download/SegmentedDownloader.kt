package com.llucs.samota.core.download

import com.llucs.samota.core.OkHttpProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class SegmentedDownloader {

    private val client = OkHttpProvider.client

    suspend fun download(
        url: String,
        authHeader: String,
        target: File,
        totalBytes: Long,
        maxConnections: Int,
        maxSpeedMiB: Double,
        onProgress: (DownloadProgress) -> Unit
    ) = withContext(Dispatchers.IO) {

        if (totalBytes <= 0) throw IllegalArgumentException("Tamanho inválido")

        val existing = if (target.exists()) target.length() else 0L
        var resumePos = existing

        if (existing == totalBytes) {
            onProgress(DownloadProgress(downloadedBytes = totalBytes, totalBytes = totalBytes, bytesPerSecond = 0))
            return@withContext
        }

        if (existing > totalBytes) {
            target.delete()
            resumePos = 0L
        }

        if (!target.exists()) target.parentFile?.mkdirs()

        RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val remaining = totalBytes - resumePos
        if (remaining <= 0) return@withContext

        val minSegment = 4L * 1024L * 1024L
        val segmentCount = min(maxConnections.coerceAtLeast(1), max(1, (remaining / minSegment).toInt()))
        val segmentSize = remaining / segmentCount

        val ranges = ArrayList<LongRange>(segmentCount)
        var pos = resumePos
        for (i in 0 until segmentCount) {
            val end = if (i < segmentCount - 1) pos + segmentSize - 1 else totalBytes - 1
            ranges.add(pos..end)
            pos = end + 1
        }

        val bucket = TokenBucket(maxSpeedMiB)
        val downloadedNew = AtomicLong(0L)
        val windowBytes = AtomicLong(0L)
        val semaphore = Semaphore(maxConnections.coerceAtLeast(1))

        coroutineScope {
            launch {
                var last = System.currentTimeMillis()
                var lastWindow = 0L
                while (isActive) {
                    delay(350)
                    val now = System.currentTimeMillis()
                    val wb = windowBytes.getAndSet(0L)
                    val dt = max(1L, now - last)
                    val bps = (wb * 1000L) / dt
                    last = now
                    lastWindow = wb
                    val totalDownloaded = resumePos + downloadedNew.get()
                    onProgress(DownloadProgress(downloadedBytes = totalDownloaded, totalBytes = totalBytes, bytesPerSecond = bps))
                }
            }

            val jobs = ranges.map { range ->
                async {
                    semaphore.withPermit {
                        downloadRangeWithRetry(
                            url = url,
                            authHeader = authHeader,
                            file = target,
                            start = range.first,
                            end = range.last,
                            bucket = bucket,
                            onBytes = { n ->
                                downloadedNew.addAndGet(n.toLong())
                                windowBytes.addAndGet(n.toLong())
                            }
                        )
                    }
                }
            }
            jobs.forEach { it.await() }
        }

        onProgress(DownloadProgress(downloadedBytes = totalBytes, totalBytes = totalBytes, bytesPerSecond = 0))
    }

    private suspend fun downloadRangeWithRetry(
        url: String,
        authHeader: String,
        file: File,
        start: Long,
        end: Long,
        bucket: TokenBucket,
        onBytes: (Int) -> Unit,
        maxRetries: Int = 5
    ) {
        var backoffMs = 1000L
        var attempt = 1
        while (true) {
            try {
                downloadRange(url, authHeader, file, start, end, bucket, onBytes)
                return
            } catch (e: Exception) {
                if (attempt >= maxRetries) throw RuntimeException("Falha no segmento $start-$end: ${e.message}", e)
                delay(backoffMs)
                backoffMs = min(backoffMs * 2, 15000L)
                attempt++
            }
        }
    }

    private suspend fun downloadRange(
        url: String,
        authHeader: String,
        file: File,
        start: Long,
        end: Long,
        bucket: TokenBucket,
        onBytes: (Int) -> Unit
    ) {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Range", "bytes=$start-$end")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!(resp.isSuccessful && (resp.code == 206 || resp.code == 200))) {
                throw IllegalStateException("HTTP ${resp.code}")
            }

            val body = resp.body ?: throw IllegalStateException("Resposta vazia")
            body.byteStream().use { input ->
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(4 * 1024 * 1024)
                    var totalRead = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        bucket.consume(n)
                        onBytes(n)
                        totalRead += n
                    }
                }
            }
        }
    }
}
