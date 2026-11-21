package com.usbbootable.creator.core.writer

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
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean,
        onProgress: (WriteProgress) -> Unit
    ): WriteProgress {
        return try {
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
                    onProgress = onProgress
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
                    isCancelled = isCancelled,
                    isDisconnected = isDisconnected,
                    onProgress = onProgress
                )
            }

            if (report.verified) {
                val finalMessage = when {
                    !verifyAfterWrite && isoInfo.bootSupport.isGuaranteedBootable ->
                        "Bootable USB created (verification skipped per Settings). Safe to disconnect USB drive."
                    isoInfo.bootSupport.isGuaranteedBootable ->
                        "Bootable USB created and verified! Safe to disconnect USB drive."
                    else ->
                        "Raw image written and verified. Note: this image was NOT guaranteed bootable on USB."
                }
                val done = WriteProgress(
                    phase = WritePhase.COMPLETED,
                    bytesProcessed = report.bytesVerified,
                    totalBytes = report.bytesVerified,
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
                onProgress(done)
                done
            } else {
                val failed = WriteProgress(
                    phase = WritePhase.FAILED,
                    bytesProcessed = report.bytesVerified,
                    totalBytes = isoInfo.sizeBytes,
                    phaseProgress = 1f,
                    overallProgress = 1f,
                    currentItemLabel = "Verification Mismatch",
                    statusMessage = "Data verification failed after writing.",
                    targetDeviceName = targetDeviceName,
                    sourceIsoName = isoInfo.displayName,
                    bootSupport = isoInfo.bootSupport,
                    verificationReport = report,
                    errorMessage = report.mismatchDetail ?: "Read-back verification failed."
                )
                onProgress(failed)
                failed
            }
        } catch (e: CancellationException) {
            // Do NOT swallow CancellationException — update terminal Cancelled state immediately
            // and rethrow so coroutine structured concurrency cancels cleanly.
            logger.warn(TAG, "Operation cancelled immediately; releasing USB session safely.")
            val cancelled = WriteProgress(
                phase = WritePhase.CANCELLED,
                statusMessage = "Cancelled. No further writes were issued. USB drive was partially modified and must be re-flashed or reformatted before use.",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
            onProgress(cancelled)
            throw e
        } catch (e: UsbDisconnectedException) {
            logger.error(TAG, "USB drive disconnected mid-operation: ${e.message}")
            val failed = WriteProgress(
                phase = WritePhase.FAILED,
                statusMessage = "USB drive was disconnected during flashing.",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport,
                errorMessage = "USB drive disconnected mid-write. Reconnect the drive and re-flash from the beginning."
            )
            onProgress(failed)
            failed
        } catch (e: Exception) {
            logger.error(TAG, "Flashing error: ${e.message}")
            try {
                if (!isDisconnected() && !isCancelled()) device.syncCache()
            } catch (_: Exception) {
            }
            val failed = WriteProgress(
                phase = WritePhase.FAILED,
                statusMessage = "USB creation failed safely.",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport,
                errorMessage = e.message ?: "Unexpected I/O error occurred."
            )
            onProgress(failed)
            failed
        }
    }

    companion object {
        private const val TAG = "Orchestrator"
    }
}
