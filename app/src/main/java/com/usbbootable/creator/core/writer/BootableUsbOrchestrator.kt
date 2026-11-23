package com.usbbootable.creator.core.writer

import com.usbbootable.creator.core.image.DetectedOsFamily
import com.usbbootable.creator.core.image.ImageSource
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.image.WriteStrategy
import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.usb.UsbDisconnectedException
import com.usbbootable.creator.core.util.OperationLogger
import kotlinx.coroutines.CancellationException

/**
 * Coordinates strategy dispatch, non-swallowing coroutine cancellation, USB disconnect
 * handling, and final verification reporting.
 */
class BootableUsbOrchestrator(
    private val logger: OperationLogger
) {
    private val rawWriter = RawImageWriter(logger)
    private val windowsWriter = WindowsUefiWriter(logger)

    suspend fun execute(
        source: ImageSource,
        device: BlockDevice,
        isoInfo: SelectedIsoInfo,
        targetDeviceName: String,
        bufferSizeBytes: Int = RawImageWriter.DEFAULT_BUFFER_BYTES,
        verifyAfterWrite: Boolean = true,
        windowsBypassOptions: WindowsBypassOptions = WindowsBypassOptions.NONE,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean,
        onProgress: (WriteProgress) -> Unit
    ): WriteProgress {
        var latestProgress: WriteProgress? = null
        var confirmedWrittenBytes = 0L
        val reportProgress: (WriteProgress) -> Unit = { p ->
            val candidateWritten = when {
                p.bytesWritten > 0L -> p.bytesWritten
                p.phase == WritePhase.WRITING || p.phase == WritePhase.FLUSHING_CACHE -> p.bytesProcessed
                else -> 0L
            }
            if (candidateWritten > confirmedWrittenBytes) {
                confirmedWrittenBytes = candidateWritten
            }
            val enriched = if (p.bytesWritten >= confirmedWrittenBytes) {
                p
            } else {
                p.copy(bytesWritten = confirmedWrittenBytes)
            }
            latestProgress = enriched
            onProgress(enriched)
        }
        return try {
            val effectiveWindowsBypass = if (isoInfo.detectedOsFamily == DetectedOsFamily.WINDOWS) {
                windowsBypassOptions
            } else {
                WindowsBypassOptions.NONE
            }
            val report = when (isoInfo.writeStrategy) {
                WriteStrategy.RAW_SECTOR_WRITE -> rawWriter.writeAndVerify(
                    source = source,
                    device = device,
                    isoInfo = isoInfo,
                    targetDeviceName = targetDeviceName,
                    bufferSizeBytes = bufferSizeBytes,
                    verifyAfterWrite = verifyAfterWrite,
                    isCancelled = isCancelled,
                    isDisconnected = isDisconnected,
                    onProgress = reportProgress
                )
                WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32,
                WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS,
                WriteStrategy.WINDOWS_UEFI_DUAL_GPT_FAT32_EXFAT -> windowsWriter.writeAndVerify(
                    source = source,
                    device = device,
                    isoInfo = isoInfo,
                    targetDeviceName = targetDeviceName,
                    bufferSizeBytes = bufferSizeBytes,
                    verifyAfterWrite = verifyAfterWrite,
                    windowsBypassOptions = effectiveWindowsBypass,
                    isCancelled = isCancelled,
                    isDisconnected = isDisconnected,
                    onProgress = reportProgress
                )
            }

            if (report.verified) {
                val finalMessage = when {
                    !verifyAfterWrite && isoInfo.bootSupport.isGuaranteedBootable ->
                        "Bootable USB created (verification skipped per Settings). Safe to disconnect USB drive."
                    !verifyAfterWrite ->
                        "Raw image written (verification skipped per Settings). Note: this image was NOT guaranteed bootable on USB."
                    isoInfo.bootSupport.isGuaranteedBootable ->
                        "Bootable USB created and verified! Safe to disconnect USB drive."
                    else ->
                        "Raw image written and verified. Note: this image was NOT guaranteed bootable on USB."
                }
                val done = WriteProgress(
                    phase = WritePhase.COMPLETED,
                    bytesProcessed = report.bytesVerified,
                    bytesWritten = maxOf(confirmedWrittenBytes, report.bytesVerified),
                    totalBytes = maxOf(confirmedWrittenBytes, report.bytesVerified),
                    phaseProgress = 1f,
                    overallProgress = 1f,
                    speedBytesPerSec = 0.0,
                    etaSeconds = 0L,
                    currentItemLabel = if (verifyAfterWrite) {
                        "Verified (${report.filesVerified} item${if (report.filesVerified == 1) "" else "s"})"
                    } else {
                        "Completed (verification off)"
                    },
                    statusMessage = finalMessage,
                    targetDeviceName = targetDeviceName,
                    sourceIsoName = isoInfo.displayName,
                    bootSupport = isoInfo.bootSupport,
                    verificationReport = report
                )
                reportProgress(done)
                done
            } else {
                val preserved = latestProgress
                val failed = WriteProgress(
                    phase = WritePhase.FAILED,
                    bytesProcessed = report.bytesVerified,
                    bytesWritten = confirmedWrittenBytes,
                    totalBytes = maxOf(preserved?.totalBytes ?: 0L, confirmedWrittenBytes, report.bytesVerified, isoInfo.sizeBytes),
                    phaseProgress = (preserved?.phaseProgress ?: 0f).coerceIn(0f, 0.99f),
                    overallProgress = (preserved?.overallProgress ?: 0.72f).coerceIn(0f, 0.99f),
                    currentItemLabel = "Verification Mismatch",
                    statusMessage = "Data verification failed after writing.",
                    targetDeviceName = targetDeviceName,
                    sourceIsoName = isoInfo.displayName,
                    bootSupport = isoInfo.bootSupport,
                    verificationReport = report,
                    errorMessage = report.mismatchDetail ?: "Read-back verification failed."
                )
                reportProgress(failed)
                failed
            }
        } catch (e: CancellationException) {
            // Do NOT swallow CancellationException — update terminal Cancelled state immediately
            // and rethrow so coroutine structured concurrency cancels cleanly.
            logger.warn(TAG, "Operation cancelled immediately; releasing USB session safely.")
            val preserved = latestProgress
            val cancelled = WriteProgress(
                phase = WritePhase.CANCELLED,
                bytesProcessed = preserved?.bytesProcessed ?: 0L,
                bytesWritten = confirmedWrittenBytes,
                totalBytes = preserved?.totalBytes ?: isoInfo.sizeBytes,
                phaseProgress = preserved?.phaseProgress ?: 0f,
                overallProgress = (preserved?.overallProgress ?: 0f).coerceIn(0f, 0.99f),
                statusMessage = "Cancelled. No further writes were issued. USB drive was partially modified and must be re-flashed or reformatted before use.",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
            reportProgress(cancelled)
            throw e
        } catch (e: UsbDisconnectedException) {
            logger.error(TAG, "USB drive disconnected mid-operation: ${e.message}")
            val preserved = latestProgress
            val failed = WriteProgress(
                phase = WritePhase.FAILED,
                bytesProcessed = preserved?.bytesProcessed ?: 0L,
                bytesWritten = confirmedWrittenBytes,
                totalBytes = preserved?.totalBytes ?: isoInfo.sizeBytes,
                phaseProgress = preserved?.phaseProgress ?: 0f,
                overallProgress = (preserved?.overallProgress ?: 0f).coerceIn(0f, 0.99f),
                statusMessage = "USB drive was disconnected during flashing.",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport,
                errorMessage = "USB drive disconnected mid-write. Reconnect the drive and re-flash from the beginning."
            )
            reportProgress(failed)
            failed
        } catch (e: Exception) {
            logger.error(TAG, "Flashing error: ${e.message}")
            try {
                if (!isDisconnected() && !isCancelled()) device.syncCache()
            } catch (_: Exception) {
            }
            val preserved = latestProgress
            val failed = WriteProgress(
                phase = WritePhase.FAILED,
                bytesProcessed = preserved?.bytesProcessed ?: 0L,
                bytesWritten = confirmedWrittenBytes,
                totalBytes = preserved?.totalBytes ?: isoInfo.sizeBytes,
                phaseProgress = preserved?.phaseProgress ?: 0f,
                overallProgress = (preserved?.overallProgress ?: 0f).coerceIn(0f, 0.99f),
                statusMessage = "USB creation failed safely.",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport,
                errorMessage = e.message ?: "Unexpected I/O error occurred."
            )
            reportProgress(failed)
            failed
        }
    }

    companion object {
        private const val TAG = "Orchestrator"
    }
}
