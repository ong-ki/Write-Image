package com.usbbootable.creator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.usbbootable.creator.MainActivity
import com.usbbootable.creator.R
import com.usbbootable.creator.UsbBootableApp
import com.usbbootable.creator.core.image.SafImageSource
import com.usbbootable.creator.core.storage.OperationHistoryRecord
import com.usbbootable.creator.core.usb.UsbDeviceSession
import com.usbbootable.creator.core.usb.UsbEvent
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.writer.BootableUsbOrchestrator
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val app: UsbBootableApp
        get() = application as UsbBootableApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                app.logger.warn(TAG, "Instant cancel requested from UI/notification.")
                app.writeStateStore.requestCancel()
                activeJob?.cancel(CancellationException("Cancelled by user"))
                return START_NOT_STICKY
            }

            ACTION_START_WRITE -> {
                if (activeJob?.isActive == true) {
                    app.logger.warn(TAG, "Write job already in progress; ignoring duplicate start.")
                    return START_NOT_STICKY
                }
                val jobSpec = app.writeStateStore.pendingJob
                if (jobSpec == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                ensureNotificationChannel()
                val initialProgress = WriteProgress(
                    phase = WritePhase.PREPARING,
                    overallProgress = 0f,
                    statusMessage = "Opening USB OTG session and ISO image...",
                    targetDeviceName = jobSpec.driveInfo.displayName,
                    sourceIsoName = jobSpec.isoInfo.displayName,
                    bootSupport = jobSpec.isoInfo.bootSupport
                )
                app.writeStateStore.cancelRequested.set(false)
                app.writeStateStore.updateProgress(initialProgress)

                val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildProgressNotification(initialProgress),
                    fgType
                )

                startWriteWorker(jobSpec)
            }
        }
        return START_NOT_STICKY
    }

    private fun startWriteWorker(jobSpec: PendingWriteJob) {
        val targetDeviceId = jobSpec.driveInfo.deviceId
        val disconnectedFlag = AtomicBoolean(false)
        val startedAtMs = System.currentTimeMillis()
        var lastWrittenBytes = 0L

        // Observe live USB disconnect broadcasts for immediate abort
        val disconnectWatcher = serviceScope.launch {
            app.usbMonitor.events.collect { event ->
                if (event is UsbEvent.DeviceDetached && event.deviceId == targetDeviceId) {
                    disconnectedFlag.set(true)
                }
            }
        }

        activeJob = serviceScope.launch {
            acquireWakeLock()
            app.usbMonitor.setWriteActive(true)
            var lastNotificationMs = 0L

            try {
                val usbDevice = app.usbMonitor.getUsbDevice(targetDeviceId)
                    ?: throw IllegalStateException("Selected USB drive is no longer connected.")

                val uri = Uri.parse(jobSpec.isoInfo.uriString)
                val orchestrator = BootableUsbOrchestrator(app.logger)
                val isCancelledCheck = { app.writeStateStore.cancelRequested.get() }
                val isDisconnectedCheck = {
                    disconnectedFlag.get() || !app.usbMonitor.isDeviceConnected(targetDeviceId)
                }

                SafImageSource(applicationContext, uri, jobSpec.isoInfo.sizeBytes).use { isoSource ->
                    UsbDeviceSession.open(
                        usbManager = app.usbMonitor.usbManager,
                        device = usbDevice,
                        isDisconnected = isDisconnectedCheck,
                        isCancelled = isCancelledCheck,
                        requestedBufferBytes = jobSpec.requestedBufferBytes,
                        logger = app.logger
                    ).use { session ->
                        val finalProgress = orchestrator.execute(
                            source = isoSource,
                            device = session.blockDevice,
                            isoInfo = jobSpec.isoInfo,
                            targetDeviceName = jobSpec.driveInfo.displayName,
                            bufferSizeBytes = session.recommendedBufferBytes,
                            verifyAfterWrite = jobSpec.verifyAfterWrite,
                            isCancelled = isCancelledCheck,
                            isDisconnected = isDisconnectedCheck,
                            onProgress = { progress ->
                                if (progress.phase == WritePhase.WRITING && progress.bytesProcessed > lastWrittenBytes) {
                                    lastWrittenBytes = progress.bytesProcessed
                                }
                                app.writeStateStore.updateProgress(progress)
                                val nowMs = System.currentTimeMillis()
                                if (!progress.phase.isActive || nowMs - lastNotificationMs >= NOTIFICATION_THROTTLE_MS) {
                                    lastNotificationMs = nowMs
                                    notifyUpdate(progress)
                                }
                            }
                        )
                        recordHistory(jobSpec, finalProgress, startedAtMs, maxOf(lastWrittenBytes, finalProgress.bytesProcessed))
                        postCompletionNotification(finalProgress)
                    }
                }
            } catch (e: CancellationException) {
                val cancelled = WriteProgress(
                    phase = WritePhase.CANCELLED,
                    bytesProcessed = lastWrittenBytes,
                    totalBytes = jobSpec.isoInfo.sizeBytes,
                    statusMessage = "Cancelled. USB drive was safely released; re-flash or reformat before use.",
                    targetDeviceName = jobSpec.driveInfo.displayName,
                    sourceIsoName = jobSpec.isoInfo.displayName,
                    bootSupport = jobSpec.isoInfo.bootSupport
                )
                app.writeStateStore.updateProgress(cancelled)
                recordHistory(jobSpec, cancelled, startedAtMs, lastWrittenBytes)
                postCompletionNotification(cancelled)
                // Do not swallow CancellationException
                throw e
            } catch (e: Exception) {
                app.logger.error(TAG, "USB write service error: ${e.message}")
                val failed = WriteProgress(
                    phase = WritePhase.FAILED,
                    bytesProcessed = lastWrittenBytes,
                    totalBytes = jobSpec.isoInfo.sizeBytes,
                    statusMessage = "Unable to start or complete USB flashing.",
                    targetDeviceName = jobSpec.driveInfo.displayName,
                    sourceIsoName = jobSpec.isoInfo.displayName,
                    bootSupport = jobSpec.isoInfo.bootSupport,
                    errorMessage = e.message ?: "Unknown USB or I/O error."
                )
                app.writeStateStore.updateProgress(failed)
                recordHistory(jobSpec, failed, startedAtMs, lastWrittenBytes)
                postCompletionNotification(failed)
            } finally {
                disconnectWatcher.cancel()
                app.usbMonitor.setWriteActive(false)
                app.writeStateStore.pendingJob = null
                releaseWakeLock()
                ServiceCompat.stopForeground(this@UsbWriteService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun recordHistory(
        jobSpec: PendingWriteJob,
        finalProgress: WriteProgress,
        startedAtMs: Long,
        bytesTransferred: Long
    ) {
        val endMs = System.currentTimeMillis()
        val durationMs = (endMs - startedAtMs).coerceAtLeast(1L)
        val avgSpeed = if (bytesTransferred > 0L) {
            bytesTransferred.toDouble() / (durationMs.toDouble() / 1000.0)
        } else {
            0.0
        }
        val note = when (finalProgress.phase) {
            WritePhase.COMPLETED -> finalProgress.verificationReport?.methodDescription ?: finalProgress.statusMessage
            WritePhase.CANCELLED -> "Cancelled after ${ByteUtils.formatBytes(bytesTransferred)}"
            else -> finalProgress.errorMessage ?: finalProgress.statusMessage
        }
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
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbBootableCreator::UsbWriteWakeLock")
                .apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L) // 2-hour safety ceiling
                }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

        val percent = (progress.overallProgress * 100f).toInt().coerceIn(0, 100)
        val title = when (progress.phase) {
            WritePhase.PREPARING -> "Preparing USB Flash..."
            WritePhase.PARTITIONING -> "Partitioning USB Drive..."
            WritePhase.WRITING -> "Writing USB ($percent%)"
            WritePhase.FLUSHING_CACHE -> "Flushing USB Cache..."
            WritePhase.VERIFYING -> "Verifying USB ($percent%)"
            else -> "USB Bootable Creator"
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
            .setProgress(100, percent, progress.phase == WritePhase.PREPARING || progress.phase == WritePhase.FLUSHING_CACHE)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
            .build()
    }

    private fun notifyUpdate(progress: WriteProgress) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildProgressNotification(progress))
    }

    private fun postCompletionNotification(progress: WriteProgress) {
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

        val (title, text) = when (progress.phase) {
            WritePhase.COMPLETED -> "Bootable USB Ready" to progress.statusMessage
            WritePhase.CANCELLED -> "USB Creation Cancelled" to progress.statusMessage
            else -> "USB Creation Failed" to (progress.errorMessage ?: progress.statusMessage)
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

        const val ACTION_START_WRITE = "com.usbbootable.creator.action.START_WRITE"
        const val ACTION_CANCEL = "com.usbbootable.creator.action.CANCEL_WRITE"

        fun startWrite(context: Context) {
            val intent = Intent(context, UsbWriteService::class.java).apply {
                action = ACTION_START_WRITE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelWrite(context: Context) {
            val intent = Intent(context, UsbWriteService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
