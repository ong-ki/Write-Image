package com.usbbootable.creator.core.usb

import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.OperationLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

enum class UsbHealthStatus(val label: String) {
    HEALTHY("HEALTHY"),
    WARNING("WARNING"),
    FAILED("FAILED")
}

enum class UsbHealthPhase {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED;

    val isActive: Boolean
        get() = this == RUNNING
}

enum class IntegrityResult(val label: String) {
    PASSED("PASSED (SHA-256 Match)"),
    FAILED("FAILED (SHA-256 Mismatch)"),
    SKIPPED_READ_ONLY("SKIPPED (Safe Read-Only Mode)")
}

data class UsbHealthReport(
    val status: UsbHealthStatus,
    val driveName: String,
    val reportedCapacityBytes: Long,
    val capacityVerified: Boolean,
    val capacityNote: String,
    val sequentialReadBytesPerSec: Double,
    val sequentialWriteBytesPerSec: Double?,
    val integrityResult: IntegrityResult,
    val integrityNote: String,
    val ioErrorCount: Int,
    val connectionStable: Boolean,
    val disconnectedMidTest: Boolean,
    val stabilityNote: String,
    val tempFileCleanedUp: Boolean,
    val elapsedSeconds: Long,
    val summaryNotes: List<String>
)

data class UsbHealthProgress(
    val phase: UsbHealthPhase = UsbHealthPhase.IDLE,
    val progressFraction: Float = 0f,
    val currentTest: String = "",
    val statusMessage: String = "",
    val elapsedSeconds: Long = 0L,
    val driveName: String = "",
    val report: UsbHealthReport? = null,
    val errorMessage: String? = null
)

/**
 * Safe, non-destructive USB OTG health diagnostic runner operating over the existing
 * BOT/SCSI [BlockDevice] abstraction.
 *
 * - Never overwrites existing user data.
 * - On FAT32 drives with free space, allocates a temporary file (`USBHCHK.TMP`) strictly in
 *   verified unallocated clusters, runs sequential write/read speed + SHA-256 integrity verification,
 *   and restores the original FAT & directory sectors in `finally`.
 * - On non-FAT32, full, or write-protected drives, falls back automatically to read-only checks.
 */
class UsbHealthChecker(
    private val logger: OperationLogger? = null
) {

    @Throws(CancellationException::class)
    suspend fun runDiagnostics(
        device: BlockDevice,
        driveName: String,
        sampleSizeBytes: Long = DEFAULT_SAMPLE_BYTES,
        chunkBytes: Int = DEFAULT_CHUNK_BYTES,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean,
        onProgress: (fraction: Float, currentTest: String, statusMessage: String) -> Unit
    ): UsbHealthReport {
        val startNanos = System.nanoTime()
        val notes = mutableListOf<String>()
        var ioErrorCount = 0
        var maxLatencyMs = 0L
        var disconnected = false

        val blockSize = device.blockSize.coerceAtLeast(512)
        val effectiveChunkBytes = ((chunkBytes.coerceIn(blockSize, MAX_CHUNK_BYTES) / blockSize) * blockSize)
            .coerceAtLeast(blockSize)
        val reportedCapacityBytes = device.capacityBytes

        fun elapsedSec(): Long = ((System.nanoTime() - startNanos) / 1_000_000_000L).coerceAtLeast(0L)

        suspend fun checkAbort() {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw CancellationException("USB health check cancelled by user.")
            if (isDisconnected()) {
                disconnected = true
                throw UsbDisconnectedException()
            }
        }

        fun timedRead(lba: Long, blocks: Int, buf: ByteArray) {
            val t0 = System.nanoTime()
            device.readBlocks(lba, blocks, buf, 0)
            val dtMs = (System.nanoTime() - t0) / 1_000_000L
            if (dtMs > maxLatencyMs) maxLatencyMs = dtMs
        }

        fun timedWrite(lba: Long, blocks: Int, buf: ByteArray) {
            val t0 = System.nanoTime()
            device.writeBlocks(lba, blocks, buf, 0)
            val dtMs = (System.nanoTime() - t0) / 1_000_000L
            if (dtMs > maxLatencyMs) maxLatencyMs = dtMs
        }

        var capacityVerified = false
        var capacityNote = "Not checked"
        var readSpeed = 0.0
        var writeSpeed: Double? = null
        var integrityResult = IntegrityResult.SKIPPED_READ_ONLY
        var integrityNote = "Read-only mode"
        var tempFileCleanedUp = true

        try {
            // =====================================================================
            // Test 1: Actual Capacity Verification (Non-Destructive LBA Range Probe)
            // =====================================================================
            checkAbort()
            onProgress(0.08f, "1/6 Actual Capacity Verification", "Verifying reported capacity and boundary LBAs...")
            logger?.info(TAG, "Starting USB health check on '$driveName' (${ByteUtils.formatBytes(reportedCapacityBytes)})")

            if (device.totalBlocks <= 0L || reportedCapacityBytes <= 0L) {
                capacityVerified = false
                capacityNote = "Drive reported invalid capacity (0 bytes)."
                ioErrorCount++
            } else {
                val probeLbas = listOf(
                    0L,
                    device.totalBlocks / 4L,
                    device.totalBlocks / 2L,
                    (device.totalBlocks * 3L) / 4L,
                    (device.totalBlocks - 1L).coerceAtLeast(0L)
                ).distinct().filter { it in 0L until device.totalBlocks }

                val sectorBuf = ByteArray(blockSize)
                var failedProbes = 0
                for (lba in probeLbas) {
                    checkAbort()
                    try {
                        timedRead(lba, 1, sectorBuf)
                    } catch (e: UsbDisconnectedException) {
                        disconnected = true
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failedProbes++
                        ioErrorCount++
                        logger?.warn(TAG, "Capacity boundary read failed at LBA $lba: ${e.message}")
                    }
                }
                capacityVerified = failedProbes == 0
                capacityNote = if (capacityVerified) {
                    "All ${probeLbas.size} boundary LBAs (including final sector) readable."
                } else {
                    "$failedProbes boundary sector read(s) failed — reported capacity may be inaccurate or unreadable."
                }
            }

            // =====================================================================
            // Locate Safe Non-Destructive FAT32 Free Space for Temporary Test File
            // =====================================================================
            checkAbort()
            onProgress(0.20f, "2/6 Preparing Safe Temp File", "Checking for unallocated FAT32 space (non-destructive)...")
            val tempRegion = if (device.isReadOnly) {
                notes.add("Drive is write-protected; write and SHA-256 tests skipped safely.")
                null
            } else {
                try {
                    findFat32TempFileRegion(
                        device = device,
                        desiredBytes = sampleSizeBytes.coerceAtLeast(MIN_TEMP_FILE_BYTES),
                        isCancelled = isCancelled,
                        isDisconnected = isDisconnected
                    )
                } catch (e: UsbDisconnectedException) {
                    disconnected = true
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ioErrorCount++
                    logger?.warn(TAG, "FAT32 free-space inspection failed: ${e.message}")
                    null
                }
            }

            if (tempRegion != null) {
                // =====================================================================
                // Tests 3 & 4: Sequential Write Speed + Write-and-Read SHA-256 Integrity
                // using temporary file USBHCHK.TMP strictly in free clusters
                // =====================================================================
                val testBytes = tempRegion.fileSizeBytes
                val transferBuf = ByteArray(effectiveChunkBytes)
                val rng = Random(System.nanoTime() xor 0x5A3C91E7L)
                val writeSha = MessageDigest.getInstance("SHA-256")
                val readSha = MessageDigest.getInstance("SHA-256")

                var tempFileCreated = false
                try {
                    checkAbort()
                    onProgress(
                        0.30f,
                        "3/6 Sequential Write Speed Test",
                        "Writing temporary file (${ByteUtils.formatBytes(testBytes)}) in free space..."
                    )

                    // Mark tempFileCreated BEFORE writing metadata so partial FAT/dir failures still attempt cleanup
                    tempFileCreated = true
                    tempRegion.createTempFileMetadata(device)

                    var remaining = testBytes
                    var currentLba = tempRegion.firstDataLba
                    val writeStartNanos = System.nanoTime()
                    while (remaining > 0L) {
                        checkAbort()
                        val toWrite = minOf(remaining, transferBuf.size.toLong()).toInt()
                        val blocks = toWrite / blockSize
                        rng.nextBytes(transferBuf, 0, toWrite)
                        writeSha.update(transferBuf, 0, toWrite)
                        timedWrite(currentLba, blocks, transferBuf)
                        currentLba += blocks
                        remaining -= toWrite
                        val frac = ((testBytes - remaining).toFloat() / testBytes.toFloat()).coerceIn(0f, 1f)
                        onProgress(
                            0.30f + frac * 0.28f,
                            "3/6 Sequential Write Speed Test",
                            "Writing temp file: ${ByteUtils.formatBytes(testBytes - remaining)} / ${ByteUtils.formatBytes(testBytes)}"
                        )
                    }
                    device.syncCache()
                    val writeElapsedSec = ((System.nanoTime() - writeStartNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                    writeSpeed = testBytes.toDouble() / writeElapsedSec
                    val expectedShaHex = ByteUtils.toHex(writeSha.digest())

                    // =====================================================================
                    // Test 2 & 4b: Sequential Read Speed + SHA-256 Read-Back Verification
                    // =====================================================================
                    checkAbort()
                    onProgress(
                        0.60f,
                        "4/6 Read Speed & SHA-256 Verification",
                        "Reading back temporary file to verify data integrity..."
                    )

                    remaining = testBytes
                    currentLba = tempRegion.firstDataLba
                    val readStartNanos = System.nanoTime()
                    while (remaining > 0L) {
                        checkAbort()
                        val toRead = minOf(remaining, transferBuf.size.toLong()).toInt()
                        val blocks = toRead / blockSize
                        timedRead(currentLba, blocks, transferBuf)
                        readSha.update(transferBuf, 0, toRead)
                        currentLba += blocks
                        remaining -= toRead
                        val frac = ((testBytes - remaining).toFloat() / testBytes.toFloat()).coerceIn(0f, 1f)
                        onProgress(
                            0.60f + frac * 0.25f,
                            "4/6 Read Speed & SHA-256 Verification",
                            "Verifying temp file: ${ByteUtils.formatBytes(testBytes - remaining)} / ${ByteUtils.formatBytes(testBytes)}"
                        )
                    }
                    val readElapsedSec = ((System.nanoTime() - readStartNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                    readSpeed = testBytes.toDouble() / readElapsedSec
                    val actualShaHex = ByteUtils.toHex(readSha.digest())

                    if (expectedShaHex == actualShaHex) {
                        integrityResult = IntegrityResult.PASSED
                        integrityNote = "SHA-256 matched across ${ByteUtils.formatBytes(testBytes)} temporary test file."
                    } else {
                        integrityResult = IntegrityResult.FAILED
                        integrityNote = "SHA-256 mismatch! Data written did not match data read back (possible failing or fake flash)."
                        notes.add("SHA-256 verification failed on temporary test file.")
                    }
                } catch (e: UsbDisconnectedException) {
                    disconnected = true
                    tempFileCleanedUp = !tempFileCreated
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ioErrorCount++
                    integrityResult = IntegrityResult.FAILED
                    integrityNote = "Write/read I/O error: ${e.message ?: "USB transfer failed"}"
                    notes.add("USB I/O error during write/read test: ${e.message ?: "Unknown error"}")
                } finally {
                    if (tempFileCreated) {
                        if (!disconnected && !isDisconnected()) {
                            tempFileCleanedUp = tempRegion.cleanupSafely(device, logger)
                            if (!tempFileCleanedUp) {
                                notes.add("Warning: Temporary test file cleanup could not be confirmed.")
                            }
                        } else {
                            tempFileCleanedUp = false
                            notes.add("Warning: USB disconnected before temporary health check file cleanup could complete.")
                        }
                    }
                }
            } else {
                // =====================================================================
                // Safe Read-Only Fallback: Sequential Read Speed Test (Zero Writes!)
                // =====================================================================
                checkAbort()
                if (!device.isReadOnly) {
                    notes.add("Write & SHA-256 test skipped to protect existing data (no unallocated FAT32 free space detected).")
                }
                integrityResult = IntegrityResult.SKIPPED_READ_ONLY
                integrityNote = if (device.isReadOnly) {
                    "Skipped (USB drive is write-protected)."
                } else {
                    "Skipped to avoid overwriting data (non-FAT32 or insufficient free space)."
                }

                val maxReadBytes = minOf(
                    sampleSizeBytes.coerceAtLeast(MIN_TEMP_FILE_BYTES),
                    (device.totalBlocks * blockSize.toLong())
                )
                val alignedReadBytes = (maxReadBytes / blockSize) * blockSize
                if (alignedReadBytes > 0L) {
                    val readBuf = ByteArray(effectiveChunkBytes)
                    var remaining = alignedReadBytes
                    var currentLba = 0L
                    val readStartNanos = System.nanoTime()
                    onProgress(
                        0.35f,
                        "2/6 Sequential Read Speed Test",
                        "Running non-destructive read speed benchmark..."
                    )
                    while (remaining > 0L) {
                        checkAbort()
                        val toRead = minOf(remaining, readBuf.size.toLong()).toInt()
                        val blocks = toRead / blockSize
                        try {
                            timedRead(currentLba, blocks, readBuf)
                        } catch (e: UsbDisconnectedException) {
                            disconnected = true
                            throw e
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ioErrorCount++
                            notes.add("Read error at LBA $currentLba: ${e.message ?: "I/O failure"}")
                            break
                        }
                        currentLba += blocks
                        remaining -= toRead
                        val frac = ((alignedReadBytes - remaining).toFloat() / alignedReadBytes.toFloat()).coerceIn(0f, 1f)
                        onProgress(
                            0.35f + frac * 0.45f,
                            "2/6 Sequential Read Speed Test",
                            "Reading: ${ByteUtils.formatBytes(alignedReadBytes - remaining)} / ${ByteUtils.formatBytes(alignedReadBytes)}"
                        )
                    }
                    val readDone = alignedReadBytes - remaining
                    val readElapsedSec = ((System.nanoTime() - readStartNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                    if (readDone > 0L) {
                        readSpeed = readDone.toDouble() / readElapsedSec
                    }
                }
            }

            // =====================================================================
            // Tests 5 & 6: USB I/O Error Summary & Connection Stability Monitoring
            // =====================================================================
            checkAbort()
            onProgress(0.90f, "6/6 Connection Stability Check", "Checking USB link stability and response latency...")
            val stabilityBuf = ByteArray(blockSize)
            val stabilityProbes = listOf(0L, (device.totalBlocks / 2L).coerceAtLeast(0L), (device.totalBlocks - 1L).coerceAtLeast(0L))
                .distinct()
                .filter { it in 0L until device.totalBlocks }
            var stabilityErrors = 0
            for (lba in stabilityProbes) {
                checkAbort()
                try {
                    timedRead(lba, 1, stabilityBuf)
                } catch (e: UsbDisconnectedException) {
                    disconnected = true
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stabilityErrors++
                    ioErrorCount++
                    logger?.warn(TAG, "Stability probe failed at LBA $lba: ${e.message}")
                }
            }

            val connectionStable = !disconnected && stabilityErrors == 0 && maxLatencyMs < HIGH_LATENCY_WARN_MS
            val stabilityNote = when {
                disconnected -> "USB disconnected during testing."
                stabilityErrors > 0 -> "Unstable: $stabilityErrors stability probe error(s) detected."
                maxLatencyMs >= HIGH_LATENCY_WARN_MS -> "Warning: Slow USB response spike detected (${maxLatencyMs} ms)."
                else -> "Stable (peak transfer latency ${maxLatencyMs} ms)."
            }

            if (readSpeed > 0.0 && readSpeed < SLOW_READ_WARN_BPS) {
                notes.add("Slow read speed (${ByteUtils.formatSpeed(readSpeed)}) — check USB port/cable or drive wear.")
            }
            if (writeSpeed != null && writeSpeed < SLOW_WRITE_WARN_BPS) {
                notes.add("Slow write speed (${ByteUtils.formatSpeed(writeSpeed)}) — flashing large ISOs may be slow.")
            }

            val overallStatus = when {
                disconnected || !capacityVerified || integrityResult == IntegrityResult.FAILED || ioErrorCount > 0 ->
                    UsbHealthStatus.FAILED
                integrityResult == IntegrityResult.SKIPPED_READ_ONLY ||
                    !tempFileCleanedUp ||
                    !connectionStable ||
                    device.isReadOnly ||
                    (readSpeed > 0.0 && readSpeed < SLOW_READ_WARN_BPS) ||
                    (writeSpeed != null && writeSpeed < SLOW_WRITE_WARN_BPS) ->
                    UsbHealthStatus.WARNING
                else -> UsbHealthStatus.HEALTHY
            }

            onProgress(1f, "Diagnostic Complete", "USB health check finished: ${overallStatus.label}")

            return UsbHealthReport(
                status = overallStatus,
                driveName = driveName,
                reportedCapacityBytes = reportedCapacityBytes,
                capacityVerified = capacityVerified,
                capacityNote = capacityNote,
                sequentialReadBytesPerSec = readSpeed,
                sequentialWriteBytesPerSec = writeSpeed,
                integrityResult = integrityResult,
                integrityNote = integrityNote,
                ioErrorCount = ioErrorCount,
                connectionStable = connectionStable,
                disconnectedMidTest = disconnected,
                stabilityNote = stabilityNote,
                tempFileCleanedUp = tempFileCleanedUp,
                elapsedSeconds = elapsedSec(),
                summaryNotes = notes
            )
        } catch (e: UsbDisconnectedException) {
            return UsbHealthReport(
                status = UsbHealthStatus.FAILED,
                driveName = driveName,
                reportedCapacityBytes = reportedCapacityBytes,
                capacityVerified = capacityVerified,
                capacityNote = capacityNote,
                sequentialReadBytesPerSec = readSpeed,
                sequentialWriteBytesPerSec = writeSpeed,
                integrityResult = if (integrityResult == IntegrityResult.PASSED) IntegrityResult.PASSED else IntegrityResult.FAILED,
                integrityNote = "Interrupted: USB drive disconnected mid-test.",
                ioErrorCount = ioErrorCount + 1,
                connectionStable = false,
                disconnectedMidTest = true,
                stabilityNote = "Disconnected during diagnostics.",
                tempFileCleanedUp = tempFileCleanedUp,
                elapsedSeconds = elapsedSec(),
                summaryNotes = notes + "USB drive disconnected mid-test. Check OTG cable or USB port."
            )
        }
    }

    /**
     * Holds a verified free contiguous FAT32 cluster run and a free root directory slot, along
     * with exact backups of every metadata sector modified so cleanup restores the volume cleanly.
     */
    private class Fat32TempFileRegion(
        val blockSize: Int,
        val numFats: Int,
        val mirrorFats: Boolean,
        val activeFatIndex: Int,
        val fatStartLba: Long,
        val fatSizeSectors: Long,
        val firstCluster: Long,
        val clusterCount: Long,
        val firstDataLba: Long,
        val fileSizeBytes: Long,
        val dirSectorLba: Long,
        val dirSlotOffset: Int,
        val originalDirSector: ByteArray,
        val fatSectorBackups: Map<Long, ByteArray>
    ) {
        fun createTempFileMetadata(device: BlockDevice) {
            // 1. Update FAT chain across active FAT(s) using backed-up sectors
            val updatedFatSectors = HashMap<Long, ByteArray>()
            for ((lba, orig) in fatSectorBackups) {
                updatedFatSectors[lba] = orig.copyOf()
            }
            val entriesPerSector = blockSize / 4
            for (i in 0L until clusterCount) {
                val cluster = firstCluster + i
                val nextVal = if (i == clusterCount - 1L) 0x0FFFFFFFL else (cluster + 1L)
                val fatRelSector = cluster / entriesPerSector
                val entryOffset = ((cluster % entriesPerSector) * 4L).toInt()
                val activeFatRelLba = fatStartLba + activeFatIndex.toLong() * fatSizeSectors + fatRelSector
                val sec = updatedFatSectors[activeFatRelLba]
                    ?: throw IOException("Missing FAT sector backup for LBA $activeFatRelLba")
                val existingTop4 = ByteUtils.readU32LE(sec, entryOffset) and 0xF0000000L
                ByteUtils.writeI32LE(sec, entryOffset, (existingTop4 or (nextVal and 0x0FFFFFFFL)).toInt())
            }
            val targetFatIndices = if (mirrorFats) (0 until numFats) else listOf(activeFatIndex)
            for (fatIdx in targetFatIndices) {
                val fatBaseDelta = (fatIdx - activeFatIndex).toLong() * fatSizeSectors
                for ((activeLba, sec) in updatedFatSectors) {
                    device.writeBlocks(activeLba + fatBaseDelta, 1, sec, 0)
                }
            }

            // 2. Write 32-byte 8.3 directory entry for USBHCHK.TMP in the free root directory slot
            val updatedDir = originalDirSector.copyOf()
            for (i in 0 until 32) {
                updatedDir[dirSlotOffset + i] = 0
            }
            ByteUtils.writeAscii(updatedDir, dirSlotOffset, TEMP_FILE_SFN_11, 11)
            updatedDir[dirSlotOffset + 11] = 0x22.toByte() // Archive + Hidden
            ByteUtils.writeU16LE(updatedDir, dirSlotOffset + 20, ((firstCluster ushr 16) and 0xFFFFL).toInt())
            ByteUtils.writeU16LE(updatedDir, dirSlotOffset + 26, (firstCluster and 0xFFFFL).toInt())
            ByteUtils.writeI32LE(updatedDir, dirSlotOffset + 28, fileSizeBytes.toInt())
            device.writeBlocks(dirSectorLba, 1, updatedDir, 0)
            device.syncCache()
        }

        fun cleanupSafely(device: BlockDevice, logger: OperationLogger?): Boolean {
            return try {
                // Restore original directory sector first so file disappears immediately
                device.writeBlocks(dirSectorLba, 1, originalDirSector, 0)
                // Restore original FAT sectors across updated FAT copy/copies
                val targetFatIndices = if (mirrorFats) (0 until numFats) else listOf(activeFatIndex)
                for (fatIdx in targetFatIndices) {
                    val fatBaseDelta = (fatIdx - activeFatIndex).toLong() * fatSizeSectors
                    for ((activeLba, origSec) in fatSectorBackups) {
                        device.writeBlocks(activeLba + fatBaseDelta, 1, origSec, 0)
                    }
                }
                device.syncCache()
                logger?.info(TAG, "Safely cleaned up temporary health check file ($TEMP_FILE_DISPLAY_NAME).")
                true
            } catch (e: Exception) {
                logger?.warn(TAG, "Failed to clean up temporary health check file: ${e.message}")
                false
            }
        }
    }

    private fun findFat32TempFileRegion(
        device: BlockDevice,
        desiredBytes: Long,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean
    ): Fat32TempFileRegion? {
        val blockSize = device.blockSize
        val fat32Range = locateFat32Partition(device) ?: return null
        val bpb = ByteArray(blockSize)
        device.readBlocks(fat32Range.first, 1, bpb, 0)
        if (!isFat32BootSector(bpb, blockSize)) return null

        val sectorsPerCluster = bpb[13].toInt() and 0xFF
        val reservedSectors = ByteUtils.readU16LE(bpb, 14).toLong()
        val numFats = bpb[16].toInt() and 0xFF
        val fatSizeSectors = ByteUtils.readU32LE(bpb, 36)
        val extFlags = ByteUtils.readU16LE(bpb, 40)
        val mirrorFats = (extFlags and 0x0080) == 0
        val activeFatIndex = (extFlags and 0x0F).coerceIn(0, (numFats - 1).coerceAtLeast(0))
        val rootCluster = ByteUtils.readU32LE(bpb, 44)
        val totalPartSectors = ByteUtils.readU32LE(bpb, 32).let {
            if (it > 0L) minOf(it, fat32Range.second) else fat32Range.second
        }

        val fatStartLba = fat32Range.first + reservedSectors
        val activeFatStartLba = fatStartLba + activeFatIndex.toLong() * fatSizeSectors
        val dataStartLba = fatStartLba + numFats.toLong() * fatSizeSectors
        val dataSectors = totalPartSectors - (dataStartLba - fat32Range.first)
        if (dataSectors <= 0L) return null
        val totalDataClusters = dataSectors / sectorsPerCluster.toLong()
        if (totalDataClusters < 4L) return null
        val clusterSizeBytes = sectorsPerCluster.toLong() * blockSize.toLong()
        if (clusterSizeBytes <= 0L) return null

        val maxEndLba = dataStartLba + totalDataClusters * sectorsPerCluster.toLong()
        if (maxEndLba > device.totalBlocks) return null

        // 1. Find a free 32-byte slot in the root directory cluster chain
        val rootSlot = findFreeRootDirSlot(
            device = device,
            blockSize = blockSize,
            fatStartLba = activeFatStartLba,
            fatSizeSectors = fatSizeSectors,
            dataStartLba = dataStartLba,
            sectorsPerCluster = sectorsPerCluster,
            rootCluster = rootCluster,
            totalDataClusters = totalDataClusters,
            isCancelled = isCancelled,
            isDisconnected = isDisconnected
        ) ?: return null

        // 2. Scan active FAT for a contiguous run of strictly FREE clusters (entry == 0)
        val targetClusters = ((desiredBytes + clusterSizeBytes - 1L) / clusterSizeBytes).coerceAtLeast(1L)
        val minClusters = ((MIN_TEMP_FILE_BYTES + clusterSizeBytes - 1L) / clusterSizeBytes).coerceAtLeast(1L)

        val entriesPerSector = blockSize / 4
        val fatSectorsToScan = minOf(
            fatSizeSectors,
            ((totalDataClusters + 2L) + entriesPerSector - 1L) / entriesPerSector,
            MAX_FAT_SECTORS_TO_SCAN
        )
        val batchSectors = (64 * 1024 / blockSize).coerceAtLeast(1)
        val fatBuf = ByteArray(batchSectors * blockSize)

        var runStart = -1L
        var runLen = 0L
        var bestStart = -1L
        var bestLen = 0L

        var fatSecIdx = 0L
        while (fatSecIdx < fatSectorsToScan && bestLen < targetClusters) {
            if (isCancelled()) throw CancellationException("Cancelled during FAT scan.")
            if (isDisconnected()) throw UsbDisconnectedException()

            val count = minOf(batchSectors.toLong(), fatSectorsToScan - fatSecIdx).toInt()
            device.readBlocks(activeFatStartLba + fatSecIdx, count, fatBuf, 0)
            val totalEntriesInBatch = count * entriesPerSector
            for (i in 0 until totalEntriesInBatch) {
                val cluster = fatSecIdx * entriesPerSector + i
                if (cluster < 2L) continue
                if (cluster >= totalDataClusters + 2L) break
                val value = ByteUtils.readU32LE(fatBuf, i * 4) and 0x0FFFFFFFL
                if (value == 0L) {
                    if (runStart < 0L) runStart = cluster
                    runLen++
                    if (runLen > bestLen) {
                        bestStart = runStart
                        bestLen = runLen
                    }
                    if (bestLen >= targetClusters) break
                } else {
                    runStart = -1L
                    runLen = 0L
                }
            }
            fatSecIdx += count
        }

        if (bestStart < 2L || bestLen < minClusters) return null
        val chosenClusters = minOf(bestLen, targetClusters)
        val fileSizeBytes = chosenClusters * clusterSizeBytes
        if (fileSizeBytes > Int.MAX_VALUE.toLong()) return null

        // 3. Back up every active FAT sector touched by [bestStart .. bestStart + chosenClusters - 1]
        // and verify mirrored FAT copies (when mirroring is active) also mark all chosen clusters as free
        val firstFatRelSec = bestStart / entriesPerSector
        val lastFatRelSec = (bestStart + chosenClusters - 1L) / entriesPerSector
        val backups = LinkedHashMap<Long, ByteArray>()
        for (relSec in firstFatRelSec..lastFatRelSec) {
            val lbaActive = activeFatStartLba + relSec
            val secActive = ByteArray(blockSize)
            device.readBlocks(lbaActive, 1, secActive, 0)
            for (c in maxOf(bestStart, relSec * entriesPerSector)..minOf(bestStart + chosenClusters - 1L, (relSec + 1L) * entriesPerSector - 1L)) {
                val off = ((c % entriesPerSector) * 4L).toInt()
                val vActive = ByteUtils.readU32LE(secActive, off) and 0x0FFFFFFFL
                if (vActive != 0L) return null
            }
            if (mirrorFats && numFats >= 2) {
                for (fatIdx in 0 until numFats) {
                    if (fatIdx == activeFatIndex) continue
                    val secOther = ByteArray(blockSize)
                    device.readBlocks(fatStartLba + fatIdx.toLong() * fatSizeSectors + relSec, 1, secOther, 0)
                    for (c in maxOf(bestStart, relSec * entriesPerSector)..minOf(bestStart + chosenClusters - 1L, (relSec + 1L) * entriesPerSector - 1L)) {
                        val off = ((c % entriesPerSector) * 4L).toInt()
                        val vOther = ByteUtils.readU32LE(secOther, off) and 0x0FFFFFFFL
                        if (vOther != 0L) return null
                    }
                }
            }
            backups[lbaActive] = secActive
        }

        val firstDataLba = dataStartLba + (bestStart - 2L) * sectorsPerCluster.toLong()
        return Fat32TempFileRegion(
            blockSize = blockSize,
            numFats = numFats,
            mirrorFats = mirrorFats,
            activeFatIndex = activeFatIndex,
            fatStartLba = fatStartLba,
            fatSizeSectors = fatSizeSectors,
            firstCluster = bestStart,
            clusterCount = chosenClusters,
            firstDataLba = firstDataLba,
            fileSizeBytes = fileSizeBytes,
            dirSectorLba = rootSlot.first,
            dirSlotOffset = rootSlot.second,
            originalDirSector = rootSlot.third,
            fatSectorBackups = backups
        )
    }

    private fun locateFat32Partition(device: BlockDevice): Pair<Long, Long>? {
        val blockSize = device.blockSize
        val sec0 = ByteArray(blockSize)
        device.readBlocks(0L, 1, sec0, 0)
        if (isFat32BootSector(sec0, blockSize)) {
            return Pair(0L, device.totalBlocks)
        }
        val hasMbrSig = (sec0[510].toInt() and 0xFF) == 0x55 && (sec0[511].toInt() and 0xFF) == 0xAA
        if (!hasMbrSig) return null

        val testSec = ByteArray(blockSize)
        var hasProtectiveGpt = false

        for (i in 0 until 4) {
            val off = 446 + i * 16
            val type = sec0[off + 4].toInt() and 0xFF
            val startLba = ByteUtils.readU32LE(sec0, off + 8)
            val sectors = ByteUtils.readU32LE(sec0, off + 12)
            if (type == 0xEE) {
                hasProtectiveGpt = true
                continue
            }
            if (type in setOf(0x0B, 0x0C, 0x0E, 0xEF) && startLba > 0L && sectors > 0L && startLba + sectors <= device.totalBlocks) {
                device.readBlocks(startLba, 1, testSec, 0)
                if (isFat32BootSector(testSec, blockSize)) {
                    return Pair(startLba, sectors)
                }
            }
        }

        if (hasProtectiveGpt && device.totalBlocks > 34L) {
            val gptHdr = ByteArray(blockSize)
            device.readBlocks(1L, 1, gptHdr, 0)
            if (String(gptHdr, 0, 8, Charsets.US_ASCII) == "EFI PART") {
                val entriesStartLba = ByteUtils.readI64LE(gptHdr, 72)
                val entryCount = ByteUtils.readI32LE(gptHdr, 80).coerceIn(0, 128)
                val entrySize = ByteUtils.readI32LE(gptHdr, 84)
                if (entriesStartLba >= 2L && entrySize >= 128 && entrySize <= blockSize) {
                    val entriesPerSec = blockSize / entrySize
                    val secBuf = ByteArray(blockSize)
                    var lastSecRead = -1L
                    for (idx in 0 until entryCount) {
                        val secLba = entriesStartLba + (idx / entriesPerSec)
                        if (secLba >= device.totalBlocks) break
                        if (secLba != lastSecRead) {
                            device.readBlocks(secLba, 1, secBuf, 0)
                            lastSecRead = secLba
                        }
                        val eOff = (idx % entriesPerSec) * entrySize
                        val firstLba = ByteUtils.readI64LE(secBuf, eOff + 32)
                        val lastLba = ByteUtils.readI64LE(secBuf, eOff + 40)
                        if (firstLba > 0L && lastLba >= firstLba && lastLba < device.totalBlocks) {
                            device.readBlocks(firstLba, 1, testSec, 0)
                            if (isFat32BootSector(testSec, blockSize)) {
                                return Pair(firstLba, lastLba - firstLba + 1L)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun isFat32BootSector(sec: ByteArray, expectedBlockSize: Int): Boolean {
        if (sec.size < 512) return false
        if ((sec[510].toInt() and 0xFF) != 0x55 || (sec[511].toInt() and 0xFF) != 0xAA) return false
        val bps = ByteUtils.readU16LE(sec, 11)
        if (bps != expectedBlockSize) return false
        val spc = sec[13].toInt() and 0xFF
        if (spc <= 0 || (spc and (spc - 1)) != 0 || spc > 128) return false
        val reserved = ByteUtils.readU16LE(sec, 14)
        val numFats = sec[16].toInt() and 0xFF
        val rootEntries = ByteUtils.readU16LE(sec, 17)
        val fat16 = ByteUtils.readU16LE(sec, 22)
        val fat32 = ByteUtils.readU32LE(sec, 36)
        val rootCluster = ByteUtils.readU32LE(sec, 44)
        val fsLabel = String(sec, 82, 8, Charsets.US_ASCII)
        return reserved > 0 && numFats in 1..2 && rootEntries == 0 && fat16 == 0 &&
            fat32 > 0L && rootCluster >= 2L && fsLabel == "FAT32   "
    }

    private fun findFreeRootDirSlot(
        device: BlockDevice,
        blockSize: Int,
        fatStartLba: Long,
        fatSizeSectors: Long,
        dataStartLba: Long,
        sectorsPerCluster: Int,
        rootCluster: Long,
        totalDataClusters: Long,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean
    ): Triple<Long, Int, ByteArray>? {
        val entriesPerFatSector = blockSize / 4
        val secBuf = ByteArray(blockSize)
        val fatSecBuf = ByteArray(blockSize)
        var curCluster = rootCluster
        var visited = 0
        var firstFree: Triple<Long, Int, ByteArray>? = null

        while (curCluster in 2L until (totalDataClusters + 2L) && visited < 32) {
            if (isCancelled()) throw CancellationException("Cancelled during root directory scan.")
            if (isDisconnected()) throw UsbDisconnectedException()
            visited++
            val clusterLba = dataStartLba + (curCluster - 2L) * sectorsPerCluster.toLong()
            for (s in 0 until sectorsPerCluster) {
                val lba = clusterLba + s
                device.readBlocks(lba, 1, secBuf, 0)
                for (off in 0 until blockSize step 32) {
                    val firstByte = secBuf[off].toInt() and 0xFF
                    if (firstByte == 0x00 || firstByte == 0xE5) {
                        if (firstFree == null) {
                            firstFree = Triple(lba, off, secBuf.copyOf())
                        }
                        if (firstByte == 0x00) {
                            return firstFree
                        }
                    } else {
                        val name11 = String(secBuf, off, 11, Charsets.US_ASCII).uppercase(Locale.ROOT)
                        if (name11 == TEMP_FILE_SFN_11) {
                            // Never overwrite an existing file with the same name
                            return null
                        }
                    }
                }
            }
            val fatRelSec = curCluster / entriesPerFatSector
            if (fatRelSec >= fatSizeSectors) break
            val fatOff = ((curCluster % entriesPerFatSector) * 4L).toInt()
            device.readBlocks(fatStartLba + fatRelSec, 1, fatSecBuf, 0)
            val next = ByteUtils.readU32LE(fatSecBuf, fatOff) and 0x0FFFFFFFL
            if (next >= 0x0FFFFFF8L || next < 2L) break
            curCluster = next
        }
        return firstFree
    }

    companion object {
        private const val TAG = "UsbHealthChecker"
        private const val TEMP_FILE_SFN_11 = "USBHCHK TMP"
        private const val TEMP_FILE_DISPLAY_NAME = "USBHCHK.TMP"
        const val DEFAULT_SAMPLE_BYTES = 8L * 1024L * 1024L
        const val MIN_TEMP_FILE_BYTES = 256L * 1024L
        private const val DEFAULT_CHUNK_BYTES = 64 * 1024
        private const val MAX_CHUNK_BYTES = 256 * 1024
        private const val MAX_FAT_SECTORS_TO_SCAN = 32_768L // 16 MiB of FAT table (~4M clusters)
        private const val HIGH_LATENCY_WARN_MS = 2_000L
        private const val SLOW_READ_WARN_BPS = 5.0 * 1024.0 * 1024.0
        private const val SLOW_WRITE_WARN_BPS = 2.0 * 1024.0 * 1024.0
    }
}
