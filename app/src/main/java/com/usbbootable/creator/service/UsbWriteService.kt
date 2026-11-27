package com.usbbootable.creator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import com.usbbootable.creator.MainActivity
import com.usbbootable.creator.R
import com.usbbootable.creator.UsbBootableApp
import com.usbbootable.creator.core.image.SafImageSource
import com.usbbootable.creator.core.storage.OperationHistoryRecord
import com.usbbootable.creator.core.usb.UsbDeviceSession
import com.usbbootable.creator.core.usb.UsbDisconnectedException
import com.usbbootable.creator.core.usb.UsbEjectResult
import com.usbbootable.creator.core.usb.UsbEvent
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.writer.BootableUsbOrchestrator
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service executing real USB SCSI block writes & SHA-256 verification so multi-GB
 * operations are never suspended when the user switches apps or turns off the screen.
 * Supports instant cooperative coroutine cancellation + immediate resource teardown.
 */
class UsbWriteService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobLock = Any()
    private val wakeLockLock = Any()
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val isDestroyed = AtomicBoolean(false)
    private val timeoutRequested = AtomicBoolean(false)
    @Volatile private var latestStartId = 0
    @Volatile private var activeSession: UsbDeviceSession? = null

    private val app: UsbBootableApp
        get() = application as UsbBootableApp

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                handleCancelInternal(startId)
                return START_NOT_STICKY
            }

            ACTION_START_WRITE -> {
                val alreadyActive = synchronized(jobLock) { activeJob?.isActive == true }
                if (alreadyActive) {
                    app.logger.warn(TAG, "Write job already in progress; ignoring duplicate start.")
                    return START_NOT_STICKY
                }
                ensureNotificationChannel()
                val jobSpec = app.writeStateStore.pendingJob
                if (jobSpec == null) {
                    // startWrite() uses startForegroundService(); satisfy Android's foreground
                    // contract before stopping when the pending job was cleared before dispatch.
                    try {
                        ServiceCompat.startForeground(
                            this,
                            NOTIFICATION_ID,
                            buildProgressNotification(
                                WriteProgress(
                                    phase = WritePhase.PREPARING,
                                    statusMessage = "Stopping USB write service..."
                                )
                            ),
                            foregroundServiceType()
                        )
                    } catch (_: Exception) {
                    }
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                val initialProgress = WriteProgress(
                    phase = WritePhase.PREPARING,
                    overallProgress = 0f,
                    statusMessage = "Opening USB OTG session and ISO image...",
                    targetDeviceName = jobSpec.driveInfo.displayName,
                    sourceIsoName = jobSpec.isoInfo.displayName,
                    bootSupport = jobSpec.isoInfo.bootSupport
                )
                timeoutRequested.set(false)
                app.writeStateStore.prepareForNewWrite()
                app.writeStateStore.updateProgress(initialProgress)

                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildProgressNotification(initialProgress),
                        foregroundServiceType()
                    )
                } catch (e: Exception) {
                    app.logger.error(TAG, "Unable to enter foreground service state: ${e.message}")
                    app.writeStateStore.pendingJob = null
                    app.writeStateStore.updateProgress(
                        WriteProgress(
                            phase = WritePhase.FAILED,
                            totalBytes = jobSpec.isoInfo.sizeBytes,
                            statusMessage = "Unable to start the USB write foreground service.",
                            targetDeviceName = jobSpec.driveInfo.displayName,
                            sourceIsoName = jobSpec.isoInfo.displayName,
                            bootSupport = jobSpec.isoInfo.bootSupport,
                            errorMessage = e.message ?: "Foreground service start was blocked by Android."
                        )
                    )
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                startWriteWorker(jobSpec)
            }

            else -> {
                val hasActiveJob = synchronized(jobLock) { activeJob?.isActive == true }
                if (!hasActiveJob) {
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        app.logger.error(TAG, "Foreground service dataSync time limit reached; stopping service and aborting USB transfer.")
        timeoutRequested.set(true)
        releaseWakeLock()
        val job = synchronized(jobLock) { activeJob }
        if (job != null && job.isActive) {
            app.writeStateStore.updateProgress(
                app.writeStateStore.progress.value.copy(
                    phase = WritePhase.FAILED,
                    speedBytesPerSec = 0.0,
                    etaSeconds = -1L,
                    statusMessage = TIMEOUT_MESSAGE,
                    errorMessage = TIMEOUT_MESSAGE
                )
            )
        }
        // Satisfy Android's dataSync timeout contract synchronously; never wait for blocked USB I/O.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        // Abort the transfer and close the USB session off the main thread.
        Thread({
            job?.cancel(CancellationException("Android dataSync time limit reached"))
            try {
                activeSession?.close()
            } catch (_: Exception) {
            }
        }, "UsbWriteTimeoutTeardown").start()
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    private fun handleCancelInternal(startId: Int? = null) {
        app.logger.warn(TAG, "Instant cancel requested from UI/notification.")
        app.writeStateStore.requestCancel()
        releaseWakeLock()
        val job = synchronized(jobLock) { activeJob }
        if (job != null && !job.isCompleted) {
            job.cancel(CancellationException("Cancelled by user"))
        } else {
            // No write worker is running: release the started service instead of leaking it.
            app.writeStateStore.pendingJob = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (startId != null) stopSelf(startId) else stopSelf()
        }
    }

    private fun startWriteWorker(jobSpec: PendingWriteJob) {
        val targetDeviceId = jobSpec.driveInfo.deviceId
        val disconnectedFlag = AtomicBoolean(false)
        val startedAtNanos = System.nanoTime()
        var lastWrittenBytes = 0L
        var lastOverallProgress = 0f

        fun finishTimedOut() {
            val failed = WriteProgress(
                phase = WritePhase.FAILED,
                bytesProcessed = lastWrittenBytes,
                bytesWritten = lastWrittenBytes,
                totalBytes = jobSpec.isoInfo.sizeBytes,
                overallProgress = lastOverallProgress,
                statusMessage = TIMEOUT_MESSAGE,
                targetDeviceName = jobSpec.driveInfo.displayName,
                sourceIsoName = jobSpec.isoInfo.displayName,
                bootSupport = jobSpec.isoInfo.bootSupport,
                errorMessage = TIMEOUT_MESSAGE
            )
            app.writeStateStore.updateProgress(failed)
            recordHistory(jobSpec, failed, startedAtNanos, lastWrittenBytes)
            postCompletionNotification(failed)
        }

        // Observe live USB disconnect broadcasts for immediate abort
        val disconnectWatcher = serviceScope.launch {
            app.usbMonitor.events.collect { event ->
                if (event is UsbEvent.DeviceDetached && event.deviceId == targetDeviceId) {
                    disconnectedFlag.set(true)
                }
            }
        }

        // Cleanup must run even if cancellation lands before the coroutine body is first dispatched,
        // and must only tear down shared service state when this job is still the active job.
        val cleanupDone = AtomicBoolean(false)
        val cleanup: (Job) -> Unit = { finishedJob ->
            if (cleanupDone.compareAndSet(false, true)) {
                disconnectWatcher.cancel()
                val isCurrentJob = synchronized(jobLock) {
                    if (activeJob === finishedJob) {
                        activeJob = null
                        true
                    } else {
                        false
                    }
                }
                if (isCurrentJob) {
                    app.usbMonitor.setWriteActive(false)
                    app.usbMonitor.setEjectActive(false)
                    if (app.writeStateStore.pendingJob === jobSpec) {
                        app.writeStateStore.pendingJob = null
                    }
                    releaseWakeLock()
                    ServiceCompat.stopForeground(this@UsbWriteService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(latestStartId)
                }
            }
        }

        lateinit var job: Job
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            acquireWakeLock()
            app.usbMonitor.setWriteActive(true)
            var lastNotificationMs = 0L

            try {
                val usbDevice = app.usbMonitor.getUsbDevice(targetDeviceId)
                    ?: throw IllegalStateException("Selected USB drive is no longer connected.")

                val uri = jobSpec.isoInfo.uriString.toUri()
                val orchestrator = BootableUsbOrchestrator(app.logger)
                val isCancelledCheck = { app.writeStateStore.cancelRequested.get() || timeoutRequested.get() }
                val isDisconnectedCheck = {
                    disconnectedFlag.get() || !app.usbMonitor.isDeviceConnected(targetDeviceId)
                }

                var finalProgressRef: WriteProgress? = null
                var shouldAutoEject = false
                var ejectResult: UsbEjectResult? = null
                var ejectError: Throwable? = null
                var ejectDisconnected = false

                SafImageSource(applicationContext, uri, jobSpec.isoInfo.sizeBytes).use { isoSource ->
                    UsbDeviceSession.open(
                        usbManager = app.usbMonitor.usbManager,
                        device = usbDevice,
                        isDisconnected = isDisconnectedCheck,
                        isCancelled = isCancelledCheck,
                        requestedBufferBytes = jobSpec.requestedBufferBytes,
                        logger = app.logger
                    ).use { session ->
                        activeSession = session
                        val finalProgress = orchestrator.execute(
                            source = isoSource,
                            device = session.blockDevice,
                            isoInfo = jobSpec.isoInfo,
                            targetDeviceName = jobSpec.driveInfo.displayName,
                            bufferSizeBytes = session.recommendedBufferBytes,
                            verifyAfterWrite = jobSpec.verifyAfterWrite,
                            windowsBypassOptions = jobSpec.windowsBypassOptions,
                            isCancelled = isCancelledCheck,
                            isDisconnected = isDisconnectedCheck,
                            onProgress = onProgress@{ progress ->
                                if (timeoutRequested.get()) return@onProgress
                                val written = maxOf(
                                    progress.bytesWritten,
                                    if (progress.phase == WritePhase.WRITING || progress.phase == WritePhase.FLUSHING_CACHE) {
                                        progress.bytesProcessed
                                    } else {
                                        0L
                                    }
                                )
                                if (written > lastWrittenBytes) {
                                    lastWrittenBytes = written
                                }
                                if (progress.overallProgress.isFinite() && progress.overallProgress > lastOverallProgress) {
                                    lastOverallProgress = progress.overallProgress.coerceIn(0f, 0.995f)
                                }
                                app.writeStateStore.updateProgress(progress)
                                if (progress.phase.isActive) {
                                    val nowMs = SystemClock.elapsedRealtime()
                                    if (nowMs - lastNotificationMs >= NOTIFICATION_THROTTLE_MS) {
                                        lastNotificationMs = nowMs
                                        notifyUpdate(progress)
                                    }
                                }
                            }
                        )
                        finalProgressRef = finalProgress
                        if (timeoutRequested.get() && finalProgress.phase != WritePhase.COMPLETED) {
                            finishTimedOut()
                        } else {
                            shouldAutoEject = finalProgress.phase == WritePhase.COMPLETED &&
                                jobSpec.autoEjectAfterWrite &&
                                !timeoutRequested.get() &&
                                !isCancelledCheck()
                            if (shouldAutoEject) {
                                // Clear pendingJob before auto-eject so an unplug mid-eject is treated
                                // as an interrupted eject rather than an aborted active write.
                                if (app.writeStateStore.pendingJob === jobSpec) {
                                    app.writeStateStore.pendingJob = null
                                }
                                app.usbMonitor.setEjectActive(true)
                                app.safeEjectStore.beginEject(targetDeviceId, jobSpec.driveInfo.displayName)
                                try {
                                    // Reuse the still-open write connection & claimed BOT interface.
                                    ejectResult = session.eject()
                                } catch (e: UsbDisconnectedException) {
                                    ejectDisconnected = true
                                    ejectError = e
                                } catch (e: CancellationException) {
                                    if (isDisconnectedCheck()) {
                                        ejectDisconnected = true
                                    }
                                    ejectError = e
                                } catch (e: Exception) {
                                    ejectError = e
                                }
                            } else {
                                recordHistory(jobSpec, finalProgress, startedAtNanos, maxOf(lastWrittenBytes, finalProgress.bytesWritten))
                                postCompletionNotification(finalProgress)
                            }
                        }
                    }
                    // UsbDeviceSession.close() has now released the interface and closed UsbDeviceConnection.
                    activeSession = null
                }

                if (shouldAutoEject) {
                    var ejectHistoryNote: String? = null
                    try {
                        val disconnectedNow = ejectDisconnected || isDisconnectedCheck()
                        when {
                            disconnectedNow -> {
                                app.logger.warn(TAG, "USB drive was unplugged before auto-eject completed.")
                                app.safeEjectStore.markDisconnectedWarning(
                                    targetDeviceId,
                                    jobSpec.driveInfo.displayName,
                                    getString(R.string.eject_banner_unplugged_warning),
                                    detailLog = ejectResult?.logText
                                )
                                ejectHistoryNote = "Auto-eject interrupted: USB unplugged"
                            }
                            ejectError is CancellationException -> {
                                if (timeoutRequested.get()) {
                                    app.safeEjectStore.markFailed(
                                        targetDeviceId,
                                        jobSpec.driveInfo.displayName,
                                        getString(R.string.eject_banner_failed_generic),
                                        detailLog = buildEjectExceptionDetailLog(ejectError)
                                    )
                                } else {
                                    app.safeEjectStore.reset()
                                }
                            }
                            ejectResult != null -> {
                                val failureSummary = ejectResult.formatFailingHistoryNote()
                                if (failureSummary != null) {
                                    ejectHistoryNote = "Eject diagnostics: $failureSummary"
                                }
                                if (ejectResult.allCommandsFailed) {
                                    app.usbMonitor.markDeviceEjected(targetDeviceId)
                                    app.safeEjectStore.markCompletedWithWarning(
                                        targetDeviceId,
                                        jobSpec.driveInfo.displayName,
                                        getString(R.string.eject_banner_all_commands_failed_warning),
                                        detailLog = ejectResult.logText
                                    )
                                    app.logger.warn(
                                        TAG,
                                        "Auto-eject commands failed best-effort for ${jobSpec.driveInfo.displayName}: ${failureSummary ?: "all commands failed"}"
                                    )
                                } else {
                                    app.usbMonitor.markDeviceEjected(targetDeviceId)
                                    app.safeEjectStore.markSucceeded(
                                        targetDeviceId,
                                        jobSpec.driveInfo.displayName,
                                        detailLog = if (failureSummary != null) ejectResult.logText else null
                                    )
                                    app.logger.success(TAG, "Auto-eject completed for ${jobSpec.driveInfo.displayName}")
                                }
                            }
                            ejectError != null -> {
                                val errMsg = resolveEjectErrorMessage(applicationContext, ejectError)
                                val detail = buildEjectExceptionDetailLog(ejectError)
                                app.logger.error(TAG, "Auto-eject failed: ${ejectError.message}")
                                app.safeEjectStore.markFailed(
                                    targetDeviceId,
                                    jobSpec.driveInfo.displayName,
                                    errMsg,
                                    detailLog = detail
                                )
                                ejectHistoryNote = "Auto-eject open error: ${ejectError.message ?: errMsg}"
                            }
                        }
                    } finally {
                        app.usbMonitor.setEjectActive(false)
                        finalProgressRef?.let {
                            recordHistory(
                                jobSpec,
                                it,
                                startedAtNanos,
                                maxOf(lastWrittenBytes, it.bytesWritten),
                                extraNote = ejectHistoryNote
                            )
                        }
                    }
                    finalProgressRef?.let { postCompletionNotification(it, autoEjected = true) }
                }
            } catch (e: CancellationException) {
                if (timeoutRequested.get()) {
                    finishTimedOut()
                    throw e
                }
                val disconnected = disconnectedFlag.get() ||
                    app.writeStateStore.disconnectResetRequested.get() ||
                    !app.usbMonitor.isDeviceConnected(targetDeviceId)
                if (disconnected) {
                    // The UI resets the drive on unplug and may cancel this job to stop I/O
                    // immediately. Report that terminal state as a disconnect failure, not as
                    // a user-requested cancellation, so history and notifications stay truthful.
                    val failed = WriteProgress(
                        phase = WritePhase.FAILED,
                        bytesProcessed = lastWrittenBytes,
                        bytesWritten = lastWrittenBytes,
                        totalBytes = jobSpec.isoInfo.sizeBytes,
                        overallProgress = lastOverallProgress,
                        statusMessage = "USB drive was disconnected during flashing.",
                        targetDeviceName = jobSpec.driveInfo.displayName,
                        sourceIsoName = jobSpec.isoInfo.displayName,
                        bootSupport = jobSpec.isoInfo.bootSupport,
                        errorMessage = "USB drive disconnected mid-write. Reconnect the drive and re-flash from the beginning."
                    )
                    app.logger.error(TAG, "USB write aborted because the drive was disconnected.")
                    app.writeStateStore.updateProgress(failed)
                    recordHistory(jobSpec, failed, startedAtNanos, lastWrittenBytes)
                    postCompletionNotification(failed)
                } else {
                    val cancelled = WriteProgress(
                        phase = WritePhase.CANCELLED,
                        bytesProcessed = lastWrittenBytes,
                        bytesWritten = lastWrittenBytes,
                        totalBytes = jobSpec.isoInfo.sizeBytes,
                        overallProgress = lastOverallProgress,
                        statusMessage = "Cancelled. USB drive was safely released; re-flash or reformat before use.",
                        targetDeviceName = jobSpec.driveInfo.displayName,
                        sourceIsoName = jobSpec.isoInfo.displayName,
                        bootSupport = jobSpec.isoInfo.bootSupport
                    )
                    app.writeStateStore.updateProgress(cancelled)
                    recordHistory(jobSpec, cancelled, startedAtNanos, lastWrittenBytes)
                    postCompletionNotification(cancelled)
                }
                // Do not swallow CancellationException
                throw e
            } catch (e: Exception) {
                app.logger.error(TAG, "USB write service error: ${e.message}")
                if (timeoutRequested.get()) {
                    finishTimedOut()
                    return@launch
                }
                val failed = WriteProgress(
                    phase = WritePhase.FAILED,
                    bytesProcessed = lastWrittenBytes,
                    bytesWritten = lastWrittenBytes,
                    totalBytes = jobSpec.isoInfo.sizeBytes,
                    overallProgress = lastOverallProgress,
                    statusMessage = "Unable to start or complete USB flashing.",
                    targetDeviceName = jobSpec.driveInfo.displayName,
                    sourceIsoName = jobSpec.isoInfo.displayName,
                    bootSupport = jobSpec.isoInfo.bootSupport,
                    errorMessage = e.message ?: "Unknown USB or I/O error."
                )
                app.writeStateStore.updateProgress(failed)
                recordHistory(jobSpec, failed, startedAtNanos, lastWrittenBytes)
                postCompletionNotification(failed)
            } finally {
                activeSession = null
                cleanup(job)
            }
        }
        synchronized(jobLock) {
            activeJob = job
        }
        job.invokeOnCompletion { cleanup(job) }
        job.start()
    }

    private fun recordHistory(
        jobSpec: PendingWriteJob,
        finalProgress: WriteProgress,
        startedAtNanos: Long,
        bytesTransferred: Long,
        extraNote: String? = null
    ) {
        val endMs = System.currentTimeMillis()
        val durationMs = ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(1L)
        val avgSpeed = if (bytesTransferred > 0L) {
            bytesTransferred.toDouble() / (durationMs.toDouble() / 1000.0)
        } else {
            0.0
        }
        val baseNote = when (finalProgress.phase) {
            WritePhase.COMPLETED -> finalProgress.verificationReport?.methodDescription ?: finalProgress.statusMessage
            WritePhase.CANCELLED -> "Cancelled after ${ByteUtils.formatBytes(bytesTransferred)}"
            else -> finalProgress.errorMessage ?: finalProgress.statusMessage
        }
        val note = if (!extraNote.isNullOrBlank()) "$baseNote | $extraNote" else baseNote
        app.preferencesStore.recordOperation(
            OperationHistoryRecord(
                id = endMs,
                timestampMs = endMs,
                isoName = jobSpec.isoInfo.displayName,
                isoSizeBytes = jobSpec.isoInfo.sizeBytes,
                driveName = jobSpec.driveInfo.displayName,
                driveCapacityBytes = jobSpec.driveInfo.capacityBytes,
                strategyLabel = jobSpec.isoInfo.bootSupport.headline,
                status = finalProgress.phase,
                averageSpeedBytesPerSec = avgSpeed,
                durationMs = durationMs,
                verified = finalProgress.verificationReport?.verified == true && jobSpec.verifyAfterWrite,
                summaryNote = note
            )
        )
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        isDestroyed.set(true)
        app.writeStateStore.requestCancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        synchronized(wakeLockLock) {
            if (isDestroyed.get()) return
            try {
                if (wakeLock?.isHeld == true) return
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbBootableCreator::UsbWriteWakeLock")
                    .apply {
                        setReferenceCounted(false)
                        acquire(2 * 60 * 60 * 1000L) // 2-hour safety ceiling
                    }
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockLock) {
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {
            } finally {
                wakeLock = null
            }
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildProgressNotification(progress: WriteProgress): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, UsbWriteService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPi = PendingIntent.getService(
            this,
            2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val safeFraction = if (progress.overallProgress.isFinite()) progress.overallProgress else 0f
        val percent = (safeFraction * 100f).toInt().coerceIn(0, 99)
        val title = when (progress.phase) {
            WritePhase.PREPARING -> "Preparing USB Flash..."
            WritePhase.PARTITIONING -> "Partitioning USB Drive..."
            WritePhase.WRITING -> "Writing USB ($percent%)"
            WritePhase.FLUSHING_CACHE -> "Flushing USB Cache..."
            WritePhase.VERIFYING -> "Verifying USB ($percent%)"
            else -> getString(R.string.app_name)
        }

        val speedText = if (progress.speedBytesPerSec > 0.0) {
            "${ByteUtils.formatSpeed(progress.speedBytesPerSec)} • ETA ${ByteUtils.formatDuration(progress.etaSeconds)}"
        } else {
            progress.statusMessage
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(speedText)
            .setSubText(progress.targetDeviceName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(
                100,
                percent,
                progress.phase == WritePhase.PREPARING ||
                    progress.phase == WritePhase.PARTITIONING ||
                    progress.phase == WritePhase.FLUSHING_CACHE
            )
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
            .build()
    }

    private fun notifyUpdate(progress: WriteProgress) {
        val store = app.writeStateStore
        if (store.cancelRequested.get() || store.disconnectResetRequested.get() || timeoutRequested.get()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildProgressNotification(progress))
    }

    private fun postCompletionNotification(progress: WriteProgress, autoEjected: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            3,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ejectState = app.safeEjectStore.state.value
        val (title, text) = when (progress.phase) {
            WritePhase.COMPLETED -> {
                val readyTitle = getString(R.string.notification_bootable_usb_ready)
                val body = if (autoEjected) {
                    when (ejectState.phase) {
                        UsbEjectPhase.SUCCEEDED -> getString(R.string.notification_auto_eject_ready, progress.statusMessage)
                        UsbEjectPhase.COMPLETED_WARNING -> getString(
                            R.string.notification_auto_eject_warning,
                            progress.statusMessage
                        )
                        UsbEjectPhase.FAILED -> getString(
                            R.string.notification_auto_eject_failed,
                            progress.statusMessage,
                            ejectState.errorMessage ?: getString(R.string.eject_banner_failed_generic)
                        )
                        UsbEjectPhase.DISCONNECTED_WARNING -> getString(R.string.notification_auto_eject_unplugged)
                        else -> progress.statusMessage
                    }
                } else {
                    progress.statusMessage
                }
                readyTitle to body
            }
            WritePhase.CANCELLED -> getString(R.string.notification_usb_creation_cancelled) to progress.statusMessage
            else -> getString(R.string.notification_usb_creation_failed) to (progress.errorMessage ?: progress.statusMessage)
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()

        nm.notify(COMPLETION_NOTIFICATION_ID, notif)
    }

    companion object {
        private const val TAG = "UsbWriteService"
        private const val CHANNEL_ID = "usb_write_progress_channel"
        private const val NOTIFICATION_ID = 4001
        private const val COMPLETION_NOTIFICATION_ID = 4002
        private const val NOTIFICATION_THROTTLE_MS = 750L
        private const val TIMEOUT_MESSAGE = "Android stopped the background transfer (time limit)"

        const val ACTION_START_WRITE = "com.usbbootable.creator.action.START_WRITE"
        const val ACTION_CANCEL = "com.usbbootable.creator.action.CANCEL_WRITE"

        @Volatile
        private var activeInstance: UsbWriteService? = null

        /** Returns false when Android blocks the foreground-service start. */
        fun startWrite(context: Context): Boolean {
            val intent = Intent(context, UsbWriteService::class.java).apply {
                action = ACTION_START_WRITE
            }
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                (context.applicationContext as? UsbBootableApp)?.logger?.error(
                    TAG,
                    "Unable to start USB write foreground service: ${e.message}"
                )
                false
            }
        }

        fun cancelWrite(context: Context) {
            val app = context.applicationContext as? UsbBootableApp ?: return
            val running = activeInstance
            if (running != null) {
                running.handleCancelInternal()
                return
            }
            // Nothing to cancel: do not start (and immediately stop) a service just to signal it.
            if (app.writeStateStore.pendingJob == null) return
            val intent = Intent(context, UsbWriteService::class.java).apply {
                action = ACTION_CANCEL
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Background-service restrictions can reject the delivery; the cooperative
                // cancel flag already set by the UI still stops the active transfer safely.
                app.logger.warn(TAG, "Unable to deliver cancel intent to service: ${e.message}")
            }
        }
    }
}
