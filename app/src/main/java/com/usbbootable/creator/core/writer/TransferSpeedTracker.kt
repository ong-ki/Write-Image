package com.usbbootable.creator.core.writer

/**
 * One real USB write-throughput sample.
 *
 * X = [elapsedMs] since the write started, Y = [bytesPerSec] measured from bytes that the
 * write pipeline reported as **successfully written**, never from queued or simulated bytes.
 */
data class SpeedSample(
    val elapsedMs: Long,
    val bytesPerSec: Double
)

/**
 * Snapshot of the live transfer-speed history used by the Home screen graph.
 */
data class TransferSpeedSnapshot(
    val currentBytesPerSec: Double = 0.0,
    val averageBytesPerSec: Double = 0.0,
    val peakBytesPerSec: Double = 0.0,
    val samples: List<SpeedSample> = emptyList(),
    val isLive: Boolean = false,
    val elapsedMs: Long = 0L
) {
    val isEmpty: Boolean get() = samples.isEmpty()

    companion object {
        val EMPTY = TransferSpeedSnapshot()
    }
}

/**
 * Rolling-window throughput calculator driven exclusively by real completed-write byte counts.
 *
 * The tracker is clock-injectable so its behaviour can be verified deterministically in unit
 * tests. All mutations are synchronized because progress arrives on the USB writer thread while
 * the UI polls for pause/zero-throughput samples on a dispatcher thread.
 */
class TransferSpeedTracker(
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxSamples: Int = MAX_SAMPLES,
    private val clockNanos: () -> Long = System::nanoTime
) {
    private val samples = ArrayDeque<SpeedSample>()

    private var startNanos: Long = 0L
    private var lastSampleNanos: Long = 0L
    private var startBytes: Long = 0L
    private var lastBytes: Long = 0L
    private var peakBytesPerSec: Double = 0.0
    private var running: Boolean = false

    /** True between [start] and [finish]/[reset]. */
    val isRunning: Boolean get() = synchronized(this) { running }

    /** Begin a new measurement window at the given cumulative byte count. */
    @Synchronized
    fun start(bytes: Long = 0L, nowNanos: Long = clockNanos()) {
        samples.clear()
        startNanos = nowNanos
        lastSampleNanos = nowNanos
        startBytes = bytes.coerceAtLeast(0L)
        lastBytes = startBytes
        peakBytesPerSec = 0.0
        running = true
    }

    /** Discard all measurements (new operation, disconnect, reset). */
    @Synchronized
    fun reset() {
        samples.clear()
        startNanos = 0L
        lastSampleNanos = 0L
        startBytes = 0L
        lastBytes = 0L
        peakBytesPerSec = 0.0
        running = false
    }

    /**
     * Record the latest cumulative completed-write byte count. Returns a snapshot that is only
     * appended to when at least [sampleIntervalMs] have elapsed, so UI updates stay in the
     * 250-500 ms range. Bytes are monotonic-guarded: a stalled write produces a zero sample,
     * which makes the graph fall toward zero instead of freezing.
     */
    @Synchronized
    fun record(bytes: Long, nowNanos: Long = clockNanos()): TransferSpeedSnapshot {
        if (!running) start(bytes, nowNanos)

        val monotonicBytes = maxOf(bytes.coerceAtLeast(0L), lastBytes)
        val elapsedMs = ((nowNanos - startNanos) / 1_000_000L).coerceAtLeast(0L)
        val dtNanos = nowNanos - lastSampleNanos

        if (dtNanos < sampleIntervalMs * 1_000_000L) {
            return snapshot(current = lastSampleSpeed(), elapsedMs = elapsedMs, live = true)
        }

        // Real completed bytes divided by real elapsed time. No smoothing of the value itself.
        val bytesSinceLastSample = monotonicBytes - lastBytes
        val speed = (bytesSinceLastSample.toDouble() * 1_000_000_000.0 / dtNanos.toDouble())
            .coerceAtLeast(0.0)

        samples.addLast(SpeedSample(elapsedMs = elapsedMs, bytesPerSec = speed))
        lastBytes = monotonicBytes
        lastSampleNanos = nowNanos
        if (speed > peakBytesPerSec) peakBytesPerSec = speed

        while (samples.size > 1 &&
            (samples.size > maxSamples || elapsedMs - samples.first().elapsedMs > windowMs)
        ) {
            samples.removeFirst()
        }

        return snapshot(current = speed, elapsedMs = elapsedMs, live = true)
    }

    /** Stop live sampling while preserving the final waveform and statistics. */
    @Synchronized
    fun finish(nowNanos: Long = clockNanos()): TransferSpeedSnapshot {
        if (!running) return TransferSpeedSnapshot.EMPTY
        running = false
        val elapsedMs = ((nowNanos - startNanos) / 1_000_000L).coerceAtLeast(0L)
        return snapshot(current = lastSampleSpeed(), elapsedMs = elapsedMs, live = false)
    }

    @Synchronized
    private fun lastSampleSpeed(): Double = samples.lastOrNull()?.bytesPerSec ?: 0.0

    private fun snapshot(current: Double, elapsedMs: Long, live: Boolean): TransferSpeedSnapshot {
        val totalBytes = (lastBytes - startBytes).coerceAtLeast(0L)
        val average = if (elapsedMs > 0L) {
            totalBytes.toDouble() * 1_000.0 / elapsedMs.toDouble()
        } else {
            0.0
        }
        return TransferSpeedSnapshot(
            currentBytesPerSec = current,
            averageBytesPerSec = average,
            peakBytesPerSec = peakBytesPerSec,
            samples = samples.toList(),
            isLive = live,
            elapsedMs = elapsedMs
        )
    }

    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MS = 300L
        const val DEFAULT_WINDOW_MS = 20_000L
        const val MAX_SAMPLES = 80
    }
}
