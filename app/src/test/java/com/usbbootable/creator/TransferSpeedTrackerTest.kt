package com.usbbootable.creator

import com.usbbootable.creator.core.writer.TransferSpeedTracker
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.service.UsbWriteStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the live speed graph is driven only by real completed-write byte counts and
 * real elapsed time, including stalls, completion, cancellation and USB disconnects.
 */
class TransferSpeedTrackerTest {

    private var nowNanos = 1_000_000_000L
    private val clock: () -> Long = { nowNanos }

    private fun advance(ms: Long) {
        nowNanos += ms * 1_000_000L
    }

    @Test
    fun speedIsCalculatedFromActualBytesAndElapsedTime() {
        val tracker = TransferSpeedTracker(
            sampleIntervalMs = 300L,
            windowMs = 5_000L,
            maxSamples = 20,
            clockNanos = clock
        )
        tracker.start(bytes = 0L, nowNanos = nowNanos)

        val bytes = 10L * 1024 * 1024
        advance(300L)
        val snapshot = tracker.record(bytes, nowNanos)

        assertEquals(bytes.toDouble() / 0.3, snapshot.currentBytesPerSec, 1.0)
        assertEquals(1, snapshot.samples.size)
        assertEquals(bytes.toDouble() / 0.3, snapshot.samples.single().bytesPerSec, 1.0)
        assertTrue(snapshot.isLive)
    }

    @Test
    fun graphRisesAndFallsWithRealThroughputAndDropsToZeroOnStall() {
        val tracker = TransferSpeedTracker(
            sampleIntervalMs = 300L,
            windowMs = 5_000L,
            maxSamples = 20,
            clockNanos = clock
        )
        tracker.start(bytes = 0L, nowNanos = nowNanos)

        advance(300L)
        val fast = tracker.record(30L * 1024 * 1024, nowNanos)

        advance(300L)
        val slow = tracker.record(40L * 1024 * 1024, nowNanos)

        advance(300L)
        val stalled = tracker.record(40L * 1024 * 1024, nowNanos)

        advance(300L)
        val resumed = tracker.record(50L * 1024 * 1024, nowNanos)

        assertTrue(slow.currentBytesPerSec < fast.currentBytesPerSec)
        assertEquals(0.0, stalled.currentBytesPerSec, 0.0)
        assertTrue(resumed.currentBytesPerSec > stalled.currentBytesPerSec)
        assertEquals(fast.currentBytesPerSec, stalled.peakBytesPerSec, 1.0)
        assertEquals(4, resumed.samples.size)
        assertEquals(fast.currentBytesPerSec, resumed.samples[0].bytesPerSec, 1.0)
        assertEquals(slow.currentBytesPerSec, resumed.samples[1].bytesPerSec, 1.0)
        assertEquals(0.0, resumed.samples[2].bytesPerSec, 0.0)
        assertEquals(resumed.currentBytesPerSec, resumed.samples[3].bytesPerSec, 1.0)
    }

    @Test
    fun samplesWithinIntervalAreThrottled() {
        val tracker = TransferSpeedTracker(sampleIntervalMs = 300L, clockNanos = clock)
        tracker.start(bytes = 0L, nowNanos = nowNanos)

        advance(100L)
        tracker.record(1L * 1024 * 1024, nowNanos)
        advance(100L)
        val snapshot = tracker.record(2L * 1024 * 1024, nowNanos)

        assertTrue(snapshot.samples.isEmpty())
        assertTrue(snapshot.isLive)
    }

    @Test
    fun averageAndPeakAreDerivedOnlyFromRecordedBytes() {
        val tracker = TransferSpeedTracker(sampleIntervalMs = 300L, clockNanos = clock)
        tracker.start(bytes = 0L, nowNanos = nowNanos)

        advance(300L)
        tracker.record(30L * 1024 * 1024, nowNanos)
        advance(300L)
        val snapshot = tracker.record(36L * 1024 * 1024, nowNanos)

        assertEquals(36L * 1024 * 1024 * 1000.0 / 600.0, snapshot.averageBytesPerSec, 1.0)
        assertEquals(30L * 1024 * 1024 / 0.3, snapshot.peakBytesPerSec, 1.0)
    }

    @Test
    fun historyIsBoundedBySampleCountAndTimeWindow() {
        val tracker = TransferSpeedTracker(
            sampleIntervalMs = 300L,
            windowMs = 1_200L,
            maxSamples = 5,
            clockNanos = clock
        )
        tracker.start(bytes = 0L, nowNanos = nowNanos)

        var total = 0L
        repeat(20) {
            advance(300L)
            total += 1024 * 1024
            tracker.record(total, nowNanos)
        }
        val snapshot = tracker.record(total, nowNanos)

        assertTrue(snapshot.samples.size <= 5)
        val oldestElapsed = snapshot.samples.first().elapsedMs
        val newestElapsed = snapshot.samples.last().elapsedMs
        assertTrue(newestElapsed - oldestElapsed <= 1_200L)
    }

    @Test
    fun byteCounterRegressionDoesNotProduceNegativeSpeed() {
        val tracker = TransferSpeedTracker(sampleIntervalMs = 300L, clockNanos = clock)
        tracker.start(bytes = 100L, nowNanos = nowNanos)

        advance(300L)
        val snapshot = tracker.record(10L, nowNanos)

        assertEquals(0.0, snapshot.currentBytesPerSec, 0.0)
        assertEquals(0.0, snapshot.averageBytesPerSec, 0.0)
    }

    @Test
    fun finishStopsLiveGraphButPreservesHistoryAndStatistics() {
        val tracker = TransferSpeedTracker(sampleIntervalMs = 300L, clockNanos = clock)
        tracker.start(bytes = 0L, nowNanos = nowNanos)
        advance(300L)
        tracker.record(10L * 1024 * 1024, nowNanos)

        val finished = tracker.finish(nowNanos)

        assertFalse(finished.isLive)
        assertEquals(1, finished.samples.size)
        assertEquals(10L * 1024 * 1024 / 0.3, finished.peakBytesPerSec, 1.0)
    }

    @Test
    fun resetClearsAllSpeedHistory() {
        val tracker = TransferSpeedTracker(sampleIntervalMs = 300L, clockNanos = clock)
        tracker.start(bytes = 0L, nowNanos = nowNanos)
        advance(300L)
        tracker.record(10L * 1024 * 1024, nowNanos)

        tracker.reset()

        assertFalse(tracker.isRunning)
        assertTrue(tracker.finish(nowNanos).isEmpty)
    }

    @Test
    fun storeSamplesOnlyWritingPhaseAndStopsOnTerminalStates() {
        val store = UsbWriteStateStore(clockNanos = clock)
        assertTrue(store.speedSnapshot.value.isEmpty)

        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, bytesProcessed = 0L))
        assertTrue(store.speedSnapshot.value.isLive)

        advance(300L)
        store.updateProgress(
            WriteProgress(
                phase = WritePhase.WRITING,
                bytesProcessed = 20L * 1024 * 1024,
                totalBytes = 100L * 1024 * 1024
            )
        )
        val writing = store.speedSnapshot.value
        assertEquals(20L * 1024 * 1024 / 0.3, writing.currentBytesPerSec, 1.0)
        assertEquals(1, writing.samples.size)

        advance(300L)
        store.sampleSpeed()
        assertEquals(0.0, store.speedSnapshot.value.currentBytesPerSec, 0.0)

        store.updateProgress(WriteProgress(phase = WritePhase.VERIFYING, bytesProcessed = 99L * 1024 * 1024))
        val stopped = store.speedSnapshot.value
        assertFalse(stopped.isLive)
        assertEquals(writing.peakBytesPerSec, stopped.peakBytesPerSec, 0.0)
        assertEquals(2, stopped.samples.size)

        // Verification byte counts must not leak into the write-speed graph.
        advance(300L)
        store.sampleSpeed()
        assertEquals(stopped.samples, store.speedSnapshot.value.samples)
    }

    @Test
    fun storeResetsSpeedHistoryOnDisconnectAndCancel() {
        val store = UsbWriteStateStore(clockNanos = clock)
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, bytesProcessed = 0L))
        advance(300L)
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, bytesProcessed = 10L * 1024 * 1024))
        assertTrue(store.speedSnapshot.value.samples.isNotEmpty())

        store.resetForDisconnect(wasActiveWrite = true)
        assertTrue(store.speedSnapshot.value.isEmpty)

        store.prepareForNewWrite()
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, bytesProcessed = 0L))
        advance(300L)
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, bytesProcessed = 5L * 1024 * 1024))
        assertTrue(store.speedSnapshot.value.samples.isNotEmpty())

        store.requestCancel()
        assertFalse(store.speedSnapshot.value.isLive)
        assertTrue(store.speedSnapshot.value.samples.isNotEmpty())
    }
}
