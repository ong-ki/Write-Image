package com.usbbootable.creator.service

import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class PendingWriteJob(
    val isoInfo: SelectedIsoInfo,
    val driveInfo: UsbDriveInfo,
    val requestedBufferBytes: Int = 0,
    val verifyAfterWrite: Boolean = true
)

/**
 * Process-scoped shared state bridge between [UsbWriteService] and UI ViewModels.
 * Enforces instant UI transition to CANCELLED and suppresses any trailing active progress
 * emissions the moment the user taps Cancel.
 */
class UsbWriteStateStore {
    private val _progress = MutableStateFlow(WriteProgress())
    val progress: StateFlow<WriteProgress> = _progress.asStateFlow()

    val cancelRequested = AtomicBoolean(false)

    @Volatile
    var pendingJob: PendingWriteJob? = null

    fun updateProgress(value: WriteProgress) {
        if (cancelRequested.get() && value.phase.isActive) {
            return
        }
        _progress.value = value
    }

    fun requestCancel() {
        cancelRequested.set(true)
        val current = _progress.value
        if (current.phase.isActive) {
            _progress.value = current.copy(
                phase = WritePhase.CANCELLED,
                speedBytesPerSec = 0.0,
                etaSeconds = -1L,
                currentItemLabel = "Stopping transfer...",
                statusMessage = "Cancelled. Stopping active USB I/O and releasing drive safely..."
            )
        }
    }

    fun resetToIdle() {
        if (!_progress.value.phase.isActive) {
            cancelRequested.set(false)
            _progress.value = WriteProgress()
        }
    }
}
