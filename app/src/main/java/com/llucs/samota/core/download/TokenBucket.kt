package com.llucs.samota.core.download

import kotlinx.coroutines.delay
import kotlin.math.min

class TokenBucket(rateMiBPerSec: Double) {

    private val rateBytesPerSec: Double = rateMiBPerSec * 1024.0 * 1024.0
    private val capacity: Double = rateBytesPerSec
    private val lock = Any()

    private var tokens: Double = capacity
    private var lastNs: Long = System.nanoTime()

    suspend fun consume(bytes: Int) {
        if (rateBytesPerSec <= 0) return
        var remaining = bytes.toDouble()
        while (remaining > 0) {
            val waitMs = synchronized(lock) {
                refillLocked()
                if (tokens >= remaining) {
                    tokens -= remaining
                    remaining = 0.0
                    0L
                } else {
                    val needed = remaining - tokens
                    tokens = 0.0
                    val waitSec = needed / rateBytesPerSec
                    (waitSec * 1000.0).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMs > 0) delay(waitMs)
        }
    }

    private fun refillLocked() {
        val now = System.nanoTime()
        val elapsedSec = (now - lastNs) / 1_000_000_000.0
        lastNs = now
        tokens = min(capacity, tokens + elapsedSec * rateBytesPerSec)
    }
}
