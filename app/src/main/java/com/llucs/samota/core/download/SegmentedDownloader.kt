package com.llucs.samota.core.download

import com.llucs.samota.core.OkHttpProvider
import kotlinx.coroutines.CancellationException
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.coroutines.coroutineContext

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

        val finalFile = target
        if (finalFile.exists() && finalFile.length() == totalBytes) {
            onProgress(DownloadProgress(downloadedBytes = totalBytes, totalBytes = totalBytes, bytesPerSecond = 0))
            return@withContext
        }

        finalFile.parentFile?.mkdirs()

        val partFile = File(finalFile.absolutePath + PART_SUFFIX)
        val mapFile = File(partFile.absolutePath + MAP_SUFFIX)

        RandomAccessFile(partFile, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val chunkCount = ceil(totalBytes.toDouble() / CHUNK_SIZE.toDouble()).toInt().coerceAtLeast(1)
        val mapBytes = ByteArray(chunkCount)

        if (mapFile.exists()) {
            try {
                val read = mapFile.inputStream().use { it.read(mapBytes) }
                if (read < chunkCount) {
                    for (i in read.coerceAtLeast(0) until chunkCount) mapBytes[i] = 0
                }
            } catch (_: Exception) {
                mapFile.delete()
            }
        }

        RandomAccessFile(mapFile, "rw").use { mapRaf ->
            mapRaf.setLength(chunkCount.toLong())
            mapRaf.seek(0L)
            mapRaf.write(mapBytes)

            val progressLock = Any()
            val windowBytes = AtomicLong(0L)
            val totalDownloaded = AtomicLong(0L)
            val perChunkDownloaded = LongArray(chunkCount)
            val bucket = TokenBucket(maxSpeedMiB)

            var doneBytes = 0L
            val chunksToDownload = ArrayList<Int>(chunkCount)
            for (i in 0 until chunkCount) {
                if (mapBytes[i].toInt() == 1) {
                    doneBytes += chunkSizeForIndex(i, totalBytes)
                } else {
                    chunksToDownload.add(i)
                }
            }
            totalDownloaded.set(doneBytes)

            if (chunksToDownload.isEmpty()) {
                finishDownload(partFile, mapFile, finalFile)
                onProgress(DownloadProgress(downloadedBytes = totalBytes, totalBytes = totalBytes, bytesPerSecond = 0))
                return@withContext
            }

            val semaphore = Semaphore(maxConnections.coerceIn(1, 32))

            coroutineScope {
                launch {
                    var last = System.currentTimeMillis()
                    while (isActive) {
                        delay(350)
                        val now = System.currentTimeMillis()
                        val wb = windowBytes.getAndSet(0L)
                        val dt = max(1L, now - last)
                        val bps = (wb * 1000L) / dt
                        last = now

                        val cur = totalDownloaded.get().coerceIn(0L, totalBytes)
                        onProgress(DownloadProgress(downloadedBytes = cur, totalBytes = totalBytes, bytesPerSecond = bps))
                    }
                }

                val jobs = chunksToDownload.map { idx ->
                    async {
                        semaphore.withPermit {
                            downloadChunkWithRetry(
                                chunkIndex = idx,
                                url = url,
                                authHeader = authHeader,
                                file = partFile,
                                totalBytes = totalBytes,
                                bucket = bucket,
                                onRetryStart = {
                                    synchronized(progressLock) {
                                        val old = perChunkDownloaded[idx]
                                        if (old > 0L) {
                                            perChunkDownloaded[idx] = 0L
                                            totalDownloaded.addAndGet(-old)
                                        }
                                    }
                                },
                                onBytes = { n ->
                                    synchronized(progressLock) {
                                        perChunkDownloaded[idx] += n.toLong()
                                        totalDownloaded.addAndGet(n.toLong())
                                    }
                                    windowBytes.addAndGet(n.toLong())
                                }
                            )

                            synchronized(progressLock) {
                                val expected = chunkSizeForIndex(idx, totalBytes)
                                val got = perChunkDownloaded[idx]
                                if (got != expected) {
                                    val diff = expected - got
                                    perChunkDownloaded[idx] = expected
                                    totalDownloaded.addAndGet(diff)
                                }
                            }

                            synchronized(progressLock) {
                                if (mapBytes[idx].toInt() != 1) {
                                    mapBytes[idx] = 1
                                    mapRaf.seek(idx.toLong())
                                    mapRaf.write(1)
                                }
                            }
                        }
                    }
                }

                jobs.forEach { it.await() }
            }
        }

        finishDownload(partFile, mapFile, finalFile)
        onProgress(DownloadProgress(downloadedBytes = totalBytes, totalBytes = totalBytes, bytesPerSecond = 0))
    }

    private suspend fun downloadChunkWithRetry(
        chunkIndex: Int,
        url: String,
        authHeader: String,
        file: File,
        totalBytes: Long,
        bucket: TokenBucket,
        onRetryStart: () -> Unit,
        onBytes: (Int) -> Unit,
        maxRetries: Int = 5
    ) {
        val range = chunkRange(chunkIndex, totalBytes)
        var backoffMs = 1000L
        var attempt = 1

        while (true) {
            onRetryStart()
            try {
                downloadRange(url, authHeader, file, range.first, range.last, bucket, onBytes)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= maxRetries) throw RuntimeException("Falha no segmento ${range.first}-${range.last}: ${e.message}", e)
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

        val call = client.newCall(req)
        call.execute().use { resp ->
            if (!(resp.isSuccessful && (resp.code == 206 || resp.code == 200))) {
                throw IllegalStateException("HTTP ${resp.code}")
            }

            val body = resp.body ?: throw IllegalStateException("Resposta vazia")
            body.byteStream().use { input ->
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (!coroutineContext.isActive) {
                            call.cancel()
                            throw CancellationException("Cancelado")
                        }

                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        bucket.consume(n)
                        onBytes(n)
                    }
                }
            }
        }
    }

    private fun chunkRange(index: Int, totalBytes: Long): LongRange {
        val start = index.toLong() * CHUNK_SIZE
        val end = min(totalBytes - 1L, start + CHUNK_SIZE - 1L)
        return start..end
    }

    private fun chunkSizeForIndex(index: Int, totalBytes: Long): Long {
        val r = chunkRange(index, totalBytes)
        return (r.last - r.first + 1L)
    }

    private fun finishDownload(partFile: File, mapFile: File, finalFile: File) {
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        mapFile.delete()
    }

    companion object {
        private const val CHUNK_SIZE = 4L * 1024L * 1024L
        private const val PART_SUFFIX = ".part"
        private const val MAP_SUFFIX = ".map"
    }
}
