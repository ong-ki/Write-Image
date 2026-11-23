package com.usbbootable.creator

import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.service.UsbWriteStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationTimerAndFormatTest {

    @Test
    fun formatElapsedClockFormatsMmSsAndHhMmSs() {
        assertEquals("00:00", ByteUtils.formatElapsedClock(-5L))
        assertEquals("00:00", ByteUtils.formatElapsedClock(0L))
        assertEquals("00:09", ByteUtils.formatElapsedClock(9L))
        assertEquals("01:05", ByteUtils.formatElapsedClock(65L))
        assertEquals("59:59", ByteUtils.formatElapsedClock(3599L))
        assertEquals("01:00:00", ByteUtils.formatElapsedClock(3600L))
        assertEquals("02:03:04", ByteUtils.formatElapsedClock(7384L))
    }

    @Test
    fun operationTimerRunsContinuouslyAcrossPhasesAndStopsOnCompletion() {
        var fakeNanos = 1_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        // Starts immediately when Start/Create is pressed
        store.startTimer()
        assertTrue(store.timerState.value.isRunning)
        assertEquals(0L, store.timerState.value.elapsedSeconds(fakeNanos))

        // Continues through PREPARING, PARTITIONING, WRITING, FLUSHING_CACHE, VERIFYING without resetting
        fakeNanos += 5_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.PREPARING))
        assertEquals(5L, store.timerState.value.elapsedSeconds(fakeNanos))

        fakeNanos += 10_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.PARTITIONING))
        assertEquals(15L, store.timerState.value.elapsedSeconds(fakeNanos))

        fakeNanos += 45_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, overallProgress = 0.5f))
        assertEquals(60L, store.timerState.value.elapsedSeconds(fakeNanos))

        fakeNanos += 10_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.FLUSHING_CACHE))
        assertEquals(70L, store.timerState.value.elapsedSeconds(fakeNanos))

        fakeNanos += 25_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.VERIFYING, overallProgress = 0.85f))
        assertTrue(store.timerState.value.isRunning)
        assertEquals(95L, store.timerState.value.elapsedSeconds(fakeNanos))

        // Stops when completed and freezes elapsed time
        fakeNanos += 5_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.COMPLETED, overallProgress = 1f))
        assertFalse(store.timerState.value.isRunning)
        assertEquals(100L, store.timerState.value.elapsedSeconds(fakeNanos))

        // Advancing clock after completion must not change stopped elapsed time
        fakeNanos += 30_000_000_000L
        assertEquals(100L, store.timerState.value.elapsedSeconds(fakeNanos))

        // Resetting status resets timer and chart progress history to empty
        store.resetToIdle()
        assertFalse(store.timerState.value.isRunning)
        assertEquals(0L, store.timerState.value.elapsedSeconds(fakeNanos))
        assertTrue(store.progressSamples.value.isEmpty())
    }

    @Test
    fun progressSamplesRecordRealMonotonicHistoryAndReach100OnlyOnCompleted() {
        var fakeNanos = 1_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        // Starts at 0% on Create
        store.startTimer()
        assertEquals(1, store.progressSamples.value.size)
        assertEquals(0f, store.progressSamples.value.first().progressFraction, 0.0001f)

        // Preserves history across PREPARING -> WRITING -> VERIFYING and never hits 1.0 before COMPLETED
        fakeNanos += 1_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.PREPARING, overallProgress = 0.05f))
        fakeNanos += 2_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, overallProgress = 0.45f))
        fakeNanos += 2_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.VERIFYING, overallProgress = 1.0f))

        val midSamples = store.progressSamples.value
        assertEquals(4, midSamples.size)
        assertTrue("Active phase must stay below 100% until COMPLETED", midSamples.last().progressFraction < 1.0f)

        // Reaches 100% only upon COMPLETED
        fakeNanos += 1_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.COMPLETED, overallProgress = 1.0f))
        val finalSamples = store.progressSamples.value
        assertEquals(5, finalSamples.size)
        assertEquals(1.0f, finalSamples.last().progressFraction, 0.0001f)
        for (i in 1 until finalSamples.size) {
            assertTrue(finalSamples[i].elapsedMs >= finalSamples[i - 1].elapsedMs)
            assertTrue(finalSamples[i].progressFraction >= finalSamples[i - 1].progressFraction)
        }
    }

    @Test
    fun progressSamplesFreezeBelow100OnCancelOrFailure() {
        var fakeNanos = 5_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        store.startTimer()
        fakeNanos += 2_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, overallProgress = 0.35f))
        fakeNanos += 1_000_000_000L
        store.requestCancel()

        val samples = store.progressSamples.value
        assertTrue(samples.isNotEmpty())
        assertEquals(0.35f, samples.last().progressFraction, 0.0001f)
    }

    @Test
    fun operationTimerStopsImmediatelyOnCancelAndFailure() {
        var fakeNanos = 10_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        // Cancel flow
        store.startTimer()
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING))
        fakeNanos += 12_000_000_000L
        store.requestCancel()
        assertFalse(store.timerState.value.isRunning)
        assertEquals(12L, store.timerState.value.elapsedSeconds(fakeNanos))

        fakeNanos += 20_000_000_000L
        assertEquals(12L, store.timerState.value.elapsedSeconds(fakeNanos))

        // Failure flow
        store.resetToIdle()
        store.startTimer()
        store.updateProgress(WriteProgress(phase = WritePhase.PREPARING))
        fakeNanos += 7_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.FAILED, errorMessage = "I/O error"))
        assertFalse(store.timerState.value.isRunning)
        assertEquals(7L, store.timerState.value.elapsedSeconds(fakeNanos))
    }

    @Test
    fun formatWrittenTotalFormatsAcrossScalesAndClampsBounds() {
        val gb = 1024L * 1024L * 1024L
        val mb = 1024L * 1024L
        val total54Gb = (5.4 * gb).toLong()

        assertEquals("-- / --", ByteUtils.formatWrittenTotal(0L, 0L))
        assertEquals("-- / --", ByteUtils.formatWrittenTotal(1024L, -1L))
        assertEquals("0.0 GB / 5.4 GB", ByteUtils.formatWrittenTotal(0L, total54Gb))
        assertEquals("2.7 GB / 5.4 GB", ByteUtils.formatWrittenTotal(total54Gb / 2L, total54Gb))
        assertEquals("5.4 GB / 5.4 GB", ByteUtils.formatWrittenTotal(total54Gb, total54Gb))
        assertEquals("5.4 GB / 5.4 GB", ByteUtils.formatWrittenTotal(total54Gb + gb, total54Gb))
        assertEquals("0.0 GB / 5.4 GB", ByteUtils.formatWrittenTotal(-500L, total54Gb))
        assertEquals("256.0 MB / 700.0 MB", ByteUtils.formatWrittenTotal(256L * mb, 700L * mb))
        assertEquals("512.0 KB / 1024.0 KB", ByteUtils.formatWrittenTotal(512L * 1024L, 1024L * 1024L - 1L))
        assertEquals("128 B / 512 B", ByteUtils.formatWrittenTotal(128L, 512L))
    }

    @Test
    fun confirmedWrittenBytesPersistThroughVerifyingCancelAndReset() {
        var fakeNanos = 1_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })
        val total = 5L * 1024L * 1024L * 1024L

        store.prepareForNewWrite()
        store.startTimer()

        fakeNanos += 1_000_000_000L
        store.updateProgress(
            WriteProgress(
                phase = WritePhase.WRITING,
                bytesProcessed = 2L * 1024L * 1024L * 1024L,
                bytesWritten = 2L * 1024L * 1024L * 1024L,
                totalBytes = total,
                overallProgress = 0.30f
            )
        )
        assertEquals(2L * 1024L * 1024L * 1024L, store.progress.value.bytesWritten)

        fakeNanos += 1_000_000_000L
        store.updateProgress(
            WriteProgress(
                phase = WritePhase.WRITING,
                bytesProcessed = total,
                bytesWritten = total,
                totalBytes = total,
                overallProgress = 0.70f
            )
        )
        assertEquals(total, store.progress.value.bytesWritten)

        // Verification resets bytesProcessed to 0, but confirmed bytesWritten must remain at total
        fakeNanos += 1_000_000_000L
        store.updateProgress(
            WriteProgress(
                phase = WritePhase.VERIFYING,
                bytesProcessed = 0L,
                totalBytes = total,
                overallProgress = 0.72f
            )
        )
        assertEquals(total, store.progress.value.bytesWritten)

        // Cancelling during verification preserves confirmed bytesWritten
        fakeNanos += 1_000_000_000L
        store.requestCancel()
        assertEquals(WritePhase.CANCELLED, store.progress.value.phase)
        assertEquals(total, store.progress.value.bytesWritten)

        // Reset clears confirmed written bytes
        store.resetToIdle()
        assertEquals(0L, store.progress.value.bytesWritten)
    }

    @Test
    fun operationTimerWorksWithNegativeOrZeroNanoTimeOrigin() {
        var fakeNanos = -5_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        store.startTimer()
        assertTrue(store.timerState.value.isRunning)
        assertTrue(store.timerState.value.hasStarted)
        assertEquals(0L, store.timerState.value.elapsedSeconds(fakeNanos))

        fakeNanos += 5_000_000_000L // Exactly 0L
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, overallProgress = 0.25f))
        assertEquals(5L, store.timerState.value.elapsedSeconds(fakeNanos))

        fakeNanos += 4_000_000_000L // Positive 4s
        store.updateProgress(WriteProgress(phase = WritePhase.COMPLETED, overallProgress = 1f))
        assertFalse(store.timerState.value.isRunning)
        assertEquals(9L, store.timerState.value.elapsedSeconds(fakeNanos))
    }

    @Test
    fun nonFiniteProgressAndDisconnectResetRecoveryAreSanitized() {
        var fakeNanos = 1_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        store.startTimer()
        fakeNanos += 1_000_000_000L
        store.updateProgress(
            WriteProgress(
                phase = WritePhase.WRITING,
                overallProgress = Float.NaN,
                speedBytesPerSec = Double.NaN
            )
        )
        assertTrue(store.progress.value.overallProgress.isFinite())
        assertTrue(store.progress.value.speedBytesPerSec.isFinite())
        assertEquals(0f, store.progress.value.overallProgress, 0.0001f)
        assertEquals(0.0, store.progress.value.speedBytesPerSec, 0.0001)
        assertTrue(store.progressSamples.value.all { it.progressFraction.isFinite() })

        // Mid-write disconnect sets disconnectResetRequested, and resetToIdle clears it cleanly
        store.resetForDisconnect(wasActiveWrite = true)
        assertTrue(store.disconnectResetRequested.get())
        store.resetToIdle()
        assertFalse(store.disconnectResetRequested.get())
        assertFalse(store.cancelRequested.get())
    }

    @Test
    fun progressSamplesDecimateAndPreserveEndpointsUnderLongOperations() {
        var fakeNanos = 1_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        store.startTimer()
        for (i in 1..240) {
            fakeNanos += 500_000_000L
            store.updateProgress(
                WriteProgress(
                    phase = WritePhase.WRITING,
                    overallProgress = i / 250f
                )
            )
        }
        val samples = store.progressSamples.value
        assertTrue("Samples must remain bounded <= 180, got ${samples.size}", samples.size <= 180)
        assertEquals(0L, samples.first().elapsedMs)
        assertEquals(0f, samples.first().progressFraction, 0.0001f)
        assertEquals(240 * 500L, samples.last().elapsedMs)
        assertEquals(240 / 250f, samples.last().progressFraction, 0.001f)
        for (i in 1 until samples.size) {
            assertTrue(samples[i].elapsedMs >= samples[i - 1].elapsedMs)
            assertTrue(samples[i].progressFraction >= samples[i - 1].progressFraction)
        }
    }
}
