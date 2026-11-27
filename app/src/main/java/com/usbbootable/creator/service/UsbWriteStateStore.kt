package com.usbbootable.creator.service

import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.writer.TransferSpeedSnapshot
import com.usbbootable.creator.core.writer.TransferSpeedTracker
import com.usbbootable.creator.core.writer.WindowsBypassOptions
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

data class PendingWriteJob(
    val isoInfo: SelectedIsoInfo,
    val driveInfo: UsbDriveInfo,
    val requestedBufferBytes: Int = 0,
    val verifyAfterWrite: Boolean = true,
    val autoEjectAfterWrite: Boolean = false,
    val windowsBypassOptions: WindowsBypassOptions = WindowsBypassOptions.NONE
)

/**
 * Single real-time progress data point for the Smooth Area Chart:
 * X = [elapsedMs], Y = [progressFraction] in [0f, 1f].
 */
data class ProgressSample(
    val elapsedMs: Long,
    val progressFraction: Float
)

data class OperationTimerState(
    val isRunning: Boolean = false,
    val startedAtNanos: Long = 0L,
    val stoppedElapsedMs: Long = 0L,
    val hasStarted: Boolean = false
) {
    fun elapsedMillis(nowNanos: Long = System.nanoTime()): Long = when {
        isRunning && hasStarted -> ((nowNanos - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        else -> stoppedElapsedMs.coerceAtLeast(0L)
    }

    fun elapsedSeconds(nowNanos: Long = System.nanoTime()): Long = elapsedMillis(nowNanos) / 1_000L
}

/**
 * Process-scoped shared state bridge between [UsbWriteService] and UI ViewModels.
 * Enforces instant UI transition to CANCELLED and suppresses any trailing active progress
 * emissions the moment the user taps Cancel.
 */
class UsbWriteStateStore(
    private val clockNanos: () -> Long = System::nanoTime
) {
    private val stateLock = Any()

    private val _progress = MutableStateFlow(WriteProgress())
    val progress: StateFlow<WriteProgress> = _progress.asStateFlow()

    private val _timerState = MutableStateFlow(OperationTimerState())
    val timerState: StateFlow<OperationTimerState> = _timerState.asStateFlow()

    private val _progressSamples = MutableStateFlow<List<ProgressSample>>(emptyList())
    val progressSamples: StateFlow<List<ProgressSample>> = _progressSamples.asStateFlow()

    private val speedTracker = TransferSpeedTracker(clockNanos = clockNanos)
    private val _speedSnapshot = MutableStateFlow(TransferSpeedSnapshot.EMPTY)
    /** Live write-throughput history, derived only from confirmed write bytes. */
    val speedSnapshot: StateFlow<TransferSpeedSnapshot> = _speedSnapshot.asStateFlow()

    @Volatile
    private var confirmedWrittenBytes: Long = 0L

    val cancelRequested = AtomicBoolean(false)
    val disconnectResetRequested = AtomicBoolean(false)

    @Volatile
    var pendingJob: PendingWriteJob? = null

    fun prepareForNewWrite() {
        synchronized(stateLock) {
            cancelRequested.set(false)
            disconnectResetRequested.set(false)
            confirmedWrittenBytes = 0L
            resetSpeedTrackingLocked()
        }
    }

    fun resetForDisconnect(wasActiveWrite: Boolean = _progress.value.phase.isActive) {
        synchronized(stateLock) {
            if (wasActiveWrite) {
                cancelRequested.set(true)
                disconnectResetRequested.set(true)
            } else {
                cancelRequested.set(false)
                disconnectResetRequested.set(false)
            }
            pendingJob = null
            confirmedWrittenBytes = 0L
            resetTimerLocked()
            resetSpeedTrackingLocked()
            _progress.value = WriteProgress()
        }
    }

    fun startTimer() {
        synchronized(stateLock) {
            startTimerLocked(clockNanos())
        }
    }

    private fun startTimerLocked(nowNanos: Long) {
        resetSpeedTrackingLocked()
        _timerState.value = OperationTimerState(
            isRunning = true,
            startedAtNanos = nowNanos,
            stoppedElapsedMs = 0L,
            hasStarted = true
        )
        _progressSamples.value = listOf(ProgressSample(elapsedMs = 0L, progressFraction = 0f))
    }

    fun stopTimer() {
        synchronized(stateLock) {
            stopTimerLocked(clockNanos())
        }
    }

    private fun stopTimerLocked(nowNanos: Long) {
        val current = _timerState.value
        if (current.isRunning) {
            _timerState.value = OperationTimerState(
                isRunning = false,
                startedAtNanos = current.startedAtNanos,
                stoppedElapsedMs = current.elapsedMillis(nowNanos),
                hasStarted = current.hasStarted
            )
        }
    }

    fun resetTimer() {
        synchronized(stateLock) {
            resetTimerLocked()
        }
    }

    private fun resetTimerLocked() {
        _timerState.value = OperationTimerState()
        _progressSamples.value = emptyList()
    }

    private fun resetSpeedTrackingLocked() {
        speedTracker.reset()
        _speedSnapshot.value = TransferSpeedSnapshot.EMPTY
    }

    fun updateProgress(value: WriteProgress) {
        synchronized(stateLock) {
            if (disconnectResetRequested.get()) {
                return
            }
            if (cancelRequested.get() && value.phase.isActive) {
                return
            }
            val nowNanos = clockNanos()
            val previousPhase = _progress.value.phase
            val normalized = normalizeProgressLocked(value)
            // Publish the new phase first so speed sampling sees the current (WRITING) state.
            _progress.value = normalized
            if (normalized.phase.isActive) {
                val currentTimer = _timerState.value
                if (!currentTimer.isRunning && !currentTimer.hasStarted) {
                    startTimerLocked(nowNanos)
                }
                val elapsedMs = _timerState.value.elapsedMillis(nowNanos)
                // Cap active progress just below 100% so chart completes at 100% only on COMPLETED
                val fraction = sanitizeActiveFraction(normalized.overallProgress)
                recordSampleLocked(elapsedMs, fraction, force = false)

                // Only the WRITING phase carries confirmed USB write bytes. Verification resets
                // bytesProcessed to zero, so it must never be mixed into the write-speed graph.
                if (normalized.phase == WritePhase.WRITING && !speedTracker.isRunning) {
                    speedTracker.start(bytes = normalized.bytesWritten, nowNanos = nowNanos)
                }
                if (normalized.phase == WritePhase.WRITING) {
                    sampleSpeedLocked(nowNanos)
                } else if (previousPhase == WritePhase.WRITING && speedTracker.isRunning) {
                    _speedSnapshot.value = speedTracker.finish(nowNanos)
                }
            } else if (normalized.phase != WritePhase.IDLE) {
                stopTimerLocked(nowNanos)
                if (previousPhase == WritePhase.WRITING && speedTracker.isRunning) {
                    _speedSnapshot.value = speedTracker.finish(nowNanos)
                }
                val elapsedMs = _timerState.value.elapsedMillis(nowNanos)
                if (normalized.phase == WritePhase.COMPLETED) {
                    recordSampleLocked(elapsedMs, 1f, force = true)
                } else {
                    val lastFrac = _progressSamples.value.lastOrNull()?.progressFraction
                        ?: sanitizeActiveFraction(normalized.overallProgress)
                    recordSampleLocked(elapsedMs, lastFrac, force = true)
                }
            }
        }
    }

    private fun sanitizeActiveFraction(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 0.995f) else 0f

    private fun normalizeProgressLocked(value: WriteProgress): WriteProgress {
        val safeProgress = if (value.overallProgress.isFinite()) {
            value.overallProgress.coerceIn(0f, 1f)
        } else {
            0f
        }
        val safeSpeed = if (value.speedBytesPerSec.isFinite() && value.speedBytesPerSec >= 0.0) {
            value.speedBytesPerSec
        } else {
            0.0
        }
        if (value.phase == WritePhase.IDLE) {
            confirmedWrittenBytes = 0L
            return if (safeProgress == value.overallProgress && safeSpeed == value.speedBytesPerSec) {
                value
            } else {
                value.copy(overallProgress = safeProgress, speedBytesPerSec = safeSpeed)
            }
        }
        val incomingWritten = when {
            value.bytesWritten > 0L -> value.bytesWritten
            value.phase == WritePhase.WRITING || value.phase == WritePhase.FLUSHING_CACHE ->
                value.bytesProcessed.coerceAtLeast(0L)
            value.phase == WritePhase.COMPLETED ->
                maxOf(value.bytesProcessed, value.totalBytes).coerceAtLeast(0L)
            else -> 0L
        }
        val effectiveWritten = maxOf(confirmedWrittenBytes, incomingWritten)
        confirmedWrittenBytes = effectiveWritten
        return if (
            value.bytesWritten == effectiveWritten &&
            safeProgress == value.overallProgress &&
            safeSpeed == value.speedBytesPerSec
        ) {
            value
        } else {
            value.copy(
                bytesWritten = effectiveWritten,
                overallProgress = safeProgress,
                speedBytesPerSec = safeSpeed
            )
        }
    }

    /**
     * Take a speed sample from the most recent confirmed write byte count. Called by the UI's
     * polling coroutine so a stalled write still produces zero-throughput samples and the graph
     * falls toward zero instead of freezing. Cheap, off the main thread, and bounded by the
     * tracker's internal interval/window limits.
     */
    fun sampleSpeed(nowNanos: Long = clockNanos()) {
        synchronized(stateLock) {
            sampleSpeedLocked(nowNanos)
        }
    }

    private fun sampleSpeedLocked(nowNanos: Long) {
        val current = _progress.value
        if (current.phase != WritePhase.WRITING || !speedTracker.isRunning) return
        _speedSnapshot.value = speedTracker.record(current.bytesWritten, nowNanos)
    }

    fun requestCancel() {
        synchronized(stateLock) {
            val nowNanos = clockNanos()
            cancelRequested.set(true)
            stopTimerLocked(nowNanos)
            if (speedTracker.isRunning) {
                _speedSnapshot.value = speedTracker.finish(nowNanos)
            }
            val current = _progress.value
            if (current.phase.isActive) {
                val elapsedMs = _timerState.value.elapsedMillis(nowNanos)
                val lastFrac = _progressSamples.value.lastOrNull()?.progressFraction
                    ?: sanitizeActiveFraction(current.overallProgress)
                recordSampleLocked(elapsedMs, lastFrac, force = true)
                _progress.value = current.copy(
                    phase = WritePhase.CANCELLED,
                    bytesWritten = maxOf(confirmedWrittenBytes, current.bytesWritten),
                    overallProgress = lastFrac,
                    speedBytesPerSec = 0.0,
                    etaSeconds = -1L,
                    currentItemLabel = "Stopping transfer...",
                    statusMessage = "Cancelled. Stopping active USB I/O and releasing drive safely..."
                )
            }
        }
    }

    fun resetToIdle() {
        synchronized(stateLock) {
            if (!_progress.value.phase.isActive) {
                cancelRequested.set(false)
                disconnectResetRequested.set(false)
                confirmedWrittenBytes = 0L
                resetTimerLocked()
                resetSpeedTrackingLocked()
                _progress.value = WriteProgress()
            }
        }
    }

    private fun recordSampleLocked(elapsedMs: Long, fraction: Float, force: Boolean) {
        val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val current = _progressSamples.value
        if (current.isEmpty()) {
            _progressSamples.value = listOf(
                ProgressSample(0L, 0f),
                ProgressSample(elapsedMs.coerceAtLeast(0L), safeFraction)
            ).distinctBy { it.elapsedMs to it.progressFraction }
            return
        }
        val last = current.last()
        val monotonicElapsed = maxOf(elapsedMs, last.elapsedMs)
        val monotonicFraction = if (safeFraction >= last.progressFraction) safeFraction else last.progressFraction

        if (!force &&
            (monotonicElapsed - last.elapsedMs) < MIN_SAMPLE_INTERVAL_MS &&
            abs(monotonicFraction - last.progressFraction) < MIN_PROGRESS_DELTA
        ) {
            return
        }

        val next = if (monotonicElapsed == last.elapsedMs) {
            if (monotonicFraction == last.progressFraction) return
            current.dropLast(1) + ProgressSample(monotonicElapsed, monotonicFraction)
        } else {
            current + ProgressSample(monotonicElapsed, monotonicFraction)
        }

        _progressSamples.value = if (next.size > MAX_PROGRESS_SAMPLES) {
            downsamplePreservingEndpoints(next, DOWNSAMPLED_PROGRESS_SAMPLES)
        } else {
            next
        }
    }

    private fun downsamplePreservingEndpoints(
        samples: List<ProgressSample>,
        targetCount: Int
    ): List<ProgressSample> {
        if (samples.size <= targetCount || targetCount < 2) return samples
        val result = ArrayList<ProgressSample>(targetCount)
        result.add(samples.first())
        val step = (samples.size - 1).toDouble() / (targetCount - 1).toDouble()
        for (i in 1 until targetCount - 1) {
            val idx = (i * step).toInt().coerceIn(1, samples.size - 2)
            result.add(samples[idx])
        }
        result.add(samples.last())
        return result
    }

    companion object {
        private const val MIN_SAMPLE_INTERVAL_MS = 400L
        private const val MIN_PROGRESS_DELTA = 0.008f
        private const val MAX_PROGRESS_SAMPLES = 180
        private const val DOWNSAMPLED_PROGRESS_SAMPLES = 90
    }
}
