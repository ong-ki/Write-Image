package com.usbbootable.creator.core.writer

import com.usbbootable.creator.core.image.ImageSource
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.usb.UsbDisconnectedException
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.OperationLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.MessageDigest

/**
 * High-throughput raw sector writer using a double-buffered producer-consumer pipeline
 * (fixed 3-buffer pool, zero per-chunk allocations) to overlap storage reads & SHA-256 hashing
 * with USB SCSI block writes, while checking coroutine & flag cancellation on every chunk.
 */
class RawImageWriter(
    private val logger: OperationLogger
) {
    private data class IoChunk(
        val buffer: ByteArray,
        val validBytes: Int,
        val sectorCount: Int
    )

    @Throws(IOException::class, CancellationException::class)
    suspend fun writeAndVerify(
        source: ImageSource,
        device: BlockDevice,
        isoInfo: SelectedIsoInfo,
        targetDeviceName: String,
        bufferSizeBytes: Int = DEFAULT_BUFFER_BYTES,
        verifyAfterWrite: Boolean = true,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean,
        onProgress: (WriteProgress) -> Unit
    ): VerificationReport {
        val totalBytes = source.sizeBytes
        if (totalBytes <= 0L) {
            throw IOException("Selected image is empty (0 bytes).")
        }
        val blockSize = device.blockSize
        val requiredBlocks = (totalBytes + blockSize - 1L) / blockSize.toLong()
        if (requiredBlocks > device.totalBlocks) {
            throw IOException(
                "Image requires $requiredBlocks blocks (${ByteUtils.formatBytes(totalBytes)}), " +
                    "but USB drive only has ${device.totalBlocks} blocks."
            )
        }

        checkState(isCancelled, isDisconnected)

        // Clear any stale backup GPT header at the tail of the USB drive when the raw image
        // is smaller than the full USB drive, so UEFI firmware won't parse leftover GPT tables.
        val tailClearBlocks = minOf(34L, device.totalBlocks - requiredBlocks).toInt()
        if (tailClearBlocks > 0) {
            checkState(isCancelled, isDisconnected)
            logger.info(TAG, "Clearing $tailClearBlocks trailing sectors to remove stale backup GPT headers")
            val zeros = ByteArray(tailClearBlocks * blockSize)
            device.writeBlocks(device.totalBlocks - tailClearBlocks, tailClearBlocks, zeros)
        }

        val clampedBuf = bufferSizeBytes.coerceIn(64 * 1024, 1024 * 1024)
        val blocksPerChunk = (clampedBuf / blockSize).coerceAtLeast(1)
        val chunkBytes = blocksPerChunk * blockSize

        val writeDigest = if (verifyAfterWrite) MessageDigest.getInstance("SHA-256") else null
        val speedTracker = SpeedTracker()
        speedTracker.reset()

        logger.info(
            TAG,
            "Starting pipelined raw write: ${isoInfo.displayName} (${ByteUtils.formatBytes(totalBytes)}) " +
                "-> $targetDeviceName ($requiredBlocks sectors @ ${blockSize}B, buffer=${ByteUtils.formatBytes(chunkBytes.toLong())})"
        )

        onProgress(
            WriteProgress(
                phase = WritePhase.WRITING,
                bytesProcessed = 0L,
                totalBytes = totalBytes,
                phaseProgress = 0f,
                overallProgress = 0.02f,
                currentItemLabel = isoInfo.displayName,
                statusMessage = "Writing image sectors to USB...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )

        val writeWeight = if (verifyAfterWrite) 0.68f else 0.94f
        var bytesWritten = 0L

        // Double-buffered producer-consumer pipeline with fixed 3-buffer pool (zero GC churn)
        coroutineScope {
            val freePool = Channel<ByteArray>(POOL_SIZE)
            repeat(POOL_SIZE) { freePool.trySend(ByteArray(chunkBytes)) }
            val readyQueue = Channel<IoChunk>(POOL_SIZE - 1)

            val readerJob = launch(Dispatchers.IO) {
                var offset = 0L
                var failure: Throwable? = null
                try {
                    while (offset < totalBytes) {
                        checkState(isCancelled, isDisconnected)
                        val buf = freePool.receive()
                        val toRead = minOf(chunkBytes.toLong(), totalBytes - offset).toInt()
                        source.readExact(offset, buf, 0, toRead)
                        writeDigest?.update(buf, 0, toRead)

                        val sectorsThisChunk = (toRead + blockSize - 1) / blockSize
                        val paddedBytes = sectorsThisChunk * blockSize
                        if (paddedBytes > toRead) {
                            buf.fill(0, toRead, paddedBytes)
                        }
                        offset += toRead
                        readyQueue.send(IoChunk(buf, toRead, sectorsThisChunk))
                    }
                } catch (t: Throwable) {
                    failure = t
                    throw t
                } finally {
                    readyQueue.close(failure)
                }
            }

            try {
                var currentLba = 0L
                var lastEmitNanos = System.nanoTime() - PROGRESS_INTERVAL_NANOS
                for (chunk in readyQueue) {
                    checkState(isCancelled, isDisconnected)
                    device.writeBlocks(currentLba, chunk.sectorCount, chunk.buffer, 0)
                    bytesWritten += chunk.validBytes
                    currentLba += chunk.sectorCount
                    freePool.trySend(chunk.buffer)

                    val now = System.nanoTime()
                    if (!isCancelled() && (now - lastEmitNanos >= PROGRESS_INTERVAL_NANOS || bytesWritten == totalBytes)) {
                        lastEmitNanos = now
                        val (speed, eta) = speedTracker.record(bytesWritten, totalBytes)
                        val phaseFrac = (bytesWritten.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                        onProgress(
                            WriteProgress(
                                phase = WritePhase.WRITING,
                                bytesProcessed = bytesWritten,
                                bytesWritten = bytesWritten,
                                totalBytes = totalBytes,
                                phaseProgress = phaseFrac,
                                overallProgress = 0.02f + phaseFrac * writeWeight,
                                speedBytesPerSec = speed,
                                etaSeconds = eta,
                                currentItemLabel = "LBA $currentLba / $requiredBlocks",
                                statusMessage = "Writing sectors to USB drive...",
                                targetDeviceName = targetDeviceName,
                                sourceIsoName = isoInfo.displayName,
                                bootSupport = isoInfo.bootSupport
                            )
                        )
                    }
                }
                readerJob.join()
            } catch (e: Throwable) {
                readerJob.cancel()
                readyQueue.cancel()
                freePool.close()
                throw e
            } finally {
                freePool.close()
            }
        }

        val sourceSha256 = writeDigest?.let { ByteUtils.toHex(it.digest()) }
        logger.success(
            TAG,
            "Raw write complete (${ByteUtils.formatBytes(bytesWritten)})" +
                if (sourceSha256 != null) ". Source SHA-256: $sourceSha256" else ""
        )

        // Flush USB drive write cache
        checkState(isCancelled, isDisconnected)
        onProgress(
            WriteProgress(
                phase = WritePhase.FLUSHING_CACHE,
                bytesProcessed = bytesWritten,
                bytesWritten = bytesWritten,
                totalBytes = totalBytes,
                phaseProgress = 1f,
                overallProgress = if (verifyAfterWrite) 0.71f else 0.98f,
                currentItemLabel = "SCSI SYNCHRONIZE CACHE",
                statusMessage = "Flushing USB drive cache...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )
        device.syncCache()

        if (!verifyAfterWrite) {
            logger.warn(TAG, "Post-write SHA-256 verification skipped per user setting.")
            return VerificationReport(
                verified = true,
                methodDescription = "Post-write verification disabled in Settings",
                bytesVerified = bytesWritten,
                filesVerified = 1
            )
        }

        // Full pipelined SCSI read-back SHA-256 verification pass
        logger.info(TAG, "Starting pipelined SCSI read-back SHA-256 verification...")
        val verifyDigest = MessageDigest.getInstance("SHA-256")
        speedTracker.reset()
        var bytesVerified = 0L

        onProgress(
            WriteProgress(
                phase = WritePhase.VERIFYING,
                bytesProcessed = 0L,
                bytesWritten = bytesWritten,
                totalBytes = totalBytes,
                phaseProgress = 0f,
                overallProgress = 0.72f,
                currentItemLabel = "Verifying LBA 0 / $requiredBlocks",
                statusMessage = "Reading back written data from USB for SHA-256 verification...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )

        coroutineScope {
            val freePool = Channel<ByteArray>(POOL_SIZE)
            repeat(POOL_SIZE) { freePool.trySend(ByteArray(chunkBytes)) }
            val readQueue = Channel<IoChunk>(POOL_SIZE - 1)

            val usbReaderJob = launch(Dispatchers.IO) {
                var readOffset = 0L
                var readLba = 0L
                var failure: Throwable? = null
                try {
                    while (readOffset < totalBytes) {
                        checkState(isCancelled, isDisconnected)
                        val buf = freePool.receive()
                        val validBytes = minOf(chunkBytes.toLong(), totalBytes - readOffset).toInt()
                        val sectorsToRead = (validBytes + blockSize - 1) / blockSize
                        device.readBlocks(readLba, sectorsToRead, buf, 0)
                        readOffset += validBytes
                        readLba += sectorsToRead
                        readQueue.send(IoChunk(buf, validBytes, sectorsToRead))
                    }
                } catch (t: Throwable) {
                    failure = t
                    throw t
                } finally {
                    readQueue.close(failure)
                }
            }

            try {
                var verifiedLba = 0L
                var lastEmitNanos = System.nanoTime() - PROGRESS_INTERVAL_NANOS
                for (chunk in readQueue) {
                    checkState(isCancelled, isDisconnected)
                    verifyDigest.update(chunk.buffer, 0, chunk.validBytes)
                    bytesVerified += chunk.validBytes
                    verifiedLba += chunk.sectorCount
                    freePool.trySend(chunk.buffer)

                    val now = System.nanoTime()
                    if (!isCancelled() && (now - lastEmitNanos >= PROGRESS_INTERVAL_NANOS || bytesVerified == totalBytes)) {
                        lastEmitNanos = now
                        val (speed, eta) = speedTracker.record(bytesVerified, totalBytes)
                        val phaseFrac = (bytesVerified.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                        onProgress(
                            WriteProgress(
                                phase = WritePhase.VERIFYING,
                                bytesProcessed = bytesVerified,
                                bytesWritten = bytesWritten,
                                totalBytes = totalBytes,
                                phaseProgress = phaseFrac,
                                overallProgress = 0.72f + phaseFrac * 0.27f,
                                speedBytesPerSec = speed,
                                etaSeconds = eta,
                                currentItemLabel = "Verifying LBA $verifiedLba / $requiredBlocks",
                                statusMessage = "Verifying written sectors against SHA-256 checksum...",
                                targetDeviceName = targetDeviceName,
                                sourceIsoName = isoInfo.displayName,
                                bootSupport = isoInfo.bootSupport
                            )
                        )
                    }
                }
                usbReaderJob.join()
            } catch (e: Throwable) {
                usbReaderJob.cancel()
                readQueue.cancel()
                freePool.close()
                throw e
            } finally {
                freePool.close()
            }
        }

        val driveSha256 = ByteUtils.toHex(verifyDigest.digest())
        val matched = sourceSha256 == driveSha256
        if (matched) {
            logger.success(TAG, "Verification PASSED! Drive SHA-256 matches source: $driveSha256")
        } else {
            logger.error(
                TAG,
                "Verification FAILED! Source SHA-256=$sourceSha256 vs Drive SHA-256=$driveSha256"
            )
        }

        return VerificationReport(
            verified = matched,
            methodDescription = "Full SCSI sector read-back SHA-256 verification",
            bytesVerified = bytesVerified,
            filesVerified = 1,
            sourceSha256 = sourceSha256,
            driveSha256 = driveSha256,
            mismatchDetail = if (matched) null else {
                "Written data checksum mismatch! USB flash memory may be failing, counterfeit, or suffered an I/O error."
            }
        )
    }

    private suspend fun checkState(isCancelled: () -> Boolean, isDisconnected: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (isDisconnected()) throw UsbDisconnectedException()
        if (isCancelled()) throw CancellationException("Write cancelled by user.")
    }

    companion object {
        private const val TAG = "RawWriter"
        const val DEFAULT_BUFFER_BYTES = 256 * 1024
        private const val POOL_SIZE = 3
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    }
}
