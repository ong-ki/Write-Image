package com.usbbootable.creator.core.writer

/**
 * Real-time rolling-window throughput (bytes/sec) and ETA calculator based solely on actual
 * USB I/O byte counts and monotonic timestamps.
 */
class SpeedTracker(
    private val windowNanos: Long = 2_500_000_000L, // 2.5 second smoothing window
    private val clockNanos: () -> Long = { System.nanoTime() }
) {
    private data class Sample(val timeNanos: Long, val totalBytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var startTimeNanos: Long = 0L
    private var started = false

    fun reset() {
        samples.clear()
        startTimeNanos = clockNanos()
        samples.addLast(Sample(startTimeNanos, 0L))
        started = true
    }

    fun record(totalBytesProcessed: Long, totalExpectedBytes: Long): Pair<Double, Long> {
        val now = clockNanos()
        if (!started) {
            startTimeNanos = now
            samples.addLast(Sample(now, 0L))
            started = true
        }

        samples.addLast(Sample(now, totalBytesProcessed))
        while (samples.size > 2 && (now - samples.first().timeNanos) > windowNanos) {
            samples.removeFirst()
        }

        val oldest = samples.first()
        val dtNanos = now - oldest.timeNanos
        val dBytes = totalBytesProcessed - oldest.totalBytes

        val speedBytesPerSec = if (dtNanos >= 200_000_000L) {
            if (dBytes > 0L) {
                dBytes.toDouble() / (dtNanos.toDouble() / 1_000_000_000.0)
            } else {
                0.0
            }
        } else {
            val totalDt = now - startTimeNanos
            if (totalDt >= 200_000_000L && totalBytesProcessed > 0L) {
                totalBytesProcessed.toDouble() / (totalDt.toDouble() / 1_000_000_000.0)
            } else {
                0.0
            }
        }

        val remainingBytes = (totalExpectedBytes - totalBytesProcessed).coerceAtLeast(0L)
        val etaSec = when {
            remainingBytes == 0L -> 0L
            speedBytesPerSec > 1.0 -> kotlin.math.ceil(remainingBytes.toDouble() / speedBytesPerSec)
                .toLong()
                .coerceAtLeast(1L)
            else -> -1L
        }

        return Pair(speedBytesPerSec, etaSec)
    }
}
