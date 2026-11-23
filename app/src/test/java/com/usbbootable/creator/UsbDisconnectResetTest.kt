package com.usbbootable.creator

import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.service.UsbWriteStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDisconnectResetTest {

    @Test
    fun disconnectAfterCompletedOrFailedWriteRestoresIdleStateImmediately() {
        var fakeNanos = 1_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        store.startTimer()
        fakeNanos += 4_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, overallProgress = 0.5f))
        fakeNanos += 2_000_000_000L
        store.updateProgress(
            WriteProgress(
                phase = WritePhase.COMPLETED,
                overallProgress = 1f,
                statusMessage = "Bootable USB created and verified!",
                targetDeviceName = "SanDisk USB"
            )
        )

        store.resetForDisconnect(wasActiveWrite = false)

        assertFalse(store.cancelRequested.get())
        assertFalse(store.disconnectResetRequested.get())
        assertNull(store.pendingJob)
        assertEquals(WriteProgress(), store.progress.value)
        assertFalse(store.timerState.value.isRunning)
        assertEquals(0L, store.timerState.value.elapsedSeconds(fakeNanos))
        assertTrue(store.progressSamples.value.isEmpty())
    }

    @Test
    fun disconnectDuringActiveWriteResetsToIdleAndSuppressesTrailingServiceEmissions() {
        var fakeNanos = 2_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        store.prepareForNewWrite()
        store.startTimer()
        fakeNanos += 3_000_000_000L
        store.updateProgress(
            WriteProgress(
                phase = WritePhase.WRITING,
                overallProgress = 0.42f,
                statusMessage = "Writing ISO...",
                targetDeviceName = "SanDisk USB"
            )
        )

        // USB unplugged mid-operation
        store.resetForDisconnect(wasActiveWrite = true)

        assertTrue(store.cancelRequested.get())
        assertTrue(store.disconnectResetRequested.get())
        assertEquals(WriteProgress(), store.progress.value)
        assertFalse(store.timerState.value.isRunning)
        assertEquals(0L, store.timerState.value.elapsedSeconds(fakeNanos))
        assertTrue(store.progressSamples.value.isEmpty())

        // Trailing active/cancelled/failed emissions from terminating service must not dirty the UI
        fakeNanos += 1_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, overallProgress = 0.50f))
        store.updateProgress(WriteProgress(phase = WritePhase.CANCELLED, statusMessage = "Cancelled"))
        store.updateProgress(WriteProgress(phase = WritePhase.FAILED, errorMessage = "USB disconnected"))

        assertEquals(WriteProgress(), store.progress.value)
        assertFalse(store.timerState.value.isRunning)
        assertEquals(0L, store.timerState.value.elapsedSeconds(fakeNanos))
        assertTrue(store.progressSamples.value.isEmpty())
    }

    @Test
    fun reconnectionAndNewWriteResumeProgressTrackingCleanly() {
        var fakeNanos = 10_000_000_000L
        val store = UsbWriteStateStore(clockNanos = { fakeNanos })

        store.prepareForNewWrite()
        store.startTimer()
        fakeNanos += 1_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, overallProgress = 0.25f))
        store.resetForDisconnect(wasActiveWrite = true)

        // Reconnect USB and start a new operation
        fakeNanos += 5_000_000_000L
        store.prepareForNewWrite()
        store.startTimer()
        fakeNanos += 2_000_000_000L
        store.updateProgress(WriteProgress(phase = WritePhase.WRITING, overallProgress = 0.30f))

        assertFalse(store.cancelRequested.get())
        assertFalse(store.disconnectResetRequested.get())
        assertEquals(WritePhase.WRITING, store.progress.value.phase)
        assertEquals(2L, store.timerState.value.elapsedSeconds(fakeNanos))
        assertEquals(2, store.progressSamples.value.size)
        assertEquals(0.30f, store.progressSamples.value.last().progressFraction, 0.0001f)
    }
}
