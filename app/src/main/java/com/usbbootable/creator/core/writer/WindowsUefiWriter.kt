package com.usbbootable.creator.core.writer

import com.usbbootable.creator.core.filesystem.ExFatFileSystem
import com.usbbootable.creator.core.filesystem.Fat32FileSystem
import com.usbbootable.creator.core.filesystem.GptPartitioner
import com.usbbootable.creator.core.filesystem.MbrPartitioner
import com.usbbootable.creator.core.filesystem.NtfsFileSystem
import com.usbbootable.creator.core.filesystem.WrittenFileAllocation
import com.usbbootable.creator.core.image.ImageEntry
import com.usbbootable.creator.core.image.ImageSource
import com.usbbootable.creator.core.image.IsoImageAnalyzer
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.image.WriteStrategy
import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.usb.PartitionBlockDevice
import com.usbbootable.creator.core.usb.UsbDisconnectedException
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.OperationLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * Creates genuine UEFI-bootable Windows (or UEFI-extractable) USB flash drives directly over
 * a raw SCSI [BlockDevice] with NO root privileges required:
 *
 * 1. [WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32]:
 *    - Formats MBR + single active FAT32 (0x0C) partition.
 *    - Extracts the full UDF/ISO-9660 file tree into FAT32.
 *    - Verifies MBR, FAT32 Boot Sector, and SHA-256 of every written file via SCSI read-back.
 *
 * 2. [WriteStrategy.WINDOWS_UEFI_DUAL_GPT_FAT32_EXFAT]:
 *    - Used when `sources/install.wim` (or any file) exceeds FAT32's 4 GiB ceiling.
 *    - Formats GPT Partition 1 as a FAT32 EFI System Partition (GUID C12A7328-...) containing
 *      `efi/`, `boot/`, `bootmgr`, `bootmgr.efi`, and `sources/boot.wim`.
 *    - Formats GPT Partition 2 as an exFAT Microsoft Basic Data Partition (GUID EBD0A0A2-...)
 *      containing the complete Windows installer tree including the >4 GiB `sources/install.wim`.
 *    - Verifies GPT Primary & Backup headers and SHA-256 of every written file on both partitions.
 */
class WindowsUefiWriter(
    private val logger: OperationLogger
) {
    @Throws(IOException::class, CancellationException::class)
    suspend fun writeAndVerify(
        source: ImageSource,
        device: BlockDevice,
        isoInfo: SelectedIsoInfo,
        targetDeviceName: String,
        bufferSizeBytes: Int = RawImageWriter.DEFAULT_BUFFER_BYTES,
        verifyAfterWrite: Boolean = true,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean,
        onProgress: (WriteProgress) -> Unit
    ): VerificationReport {
        checkState(isCancelled, isDisconnected)

        onProgress(
            WriteProgress(
                phase = WritePhase.PREPARING,
                overallProgress = 0.01f,
                currentItemLabel = "Parsing ${isoInfo.displayName}",
                statusMessage = "Opening ISO/UDF file tree...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )

        val imageFs = IsoImageAnalyzer.openFileSystem(source)
            ?: throw IOException("Unable to open UDF or ISO-9660 filesystem inside ${isoInfo.displayName}.")

        val allEntries = imageFs.entries
        val allDirs = allEntries.filter { it.isDirectory }
        val allFiles = allEntries.filter { !it.isDirectory }
        if (allFiles.isEmpty()) {
            throw IOException("No files found inside ${isoInfo.displayName}.")
        }

        val hasEfiBoot = allFiles.any {
            val p = it.normalizedPathLower
            p == "efi/boot/bootx64.efi" || p == "efi/boot/bootaa64.efi" || p == "efi/boot/bootia32.efi"
        }
        if (!hasEfiBoot) {
            throw IOException(
                "ISO does not contain a standard UEFI bootloader (/efi/boot/bootx64.efi or bootaa64.efi). " +
                    "Cannot create a UEFI bootable Windows USB from this image."
            )
        }

        logger.info(
            TAG,
            "Opened ${imageFs.formatName} image '${imageFs.volumeLabel}': ${allFiles.size} files, ${allDirs.size} directories"
        )

        return when (isoInfo.writeStrategy) {
            WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32 -> writeSingleFat32(
                imageFs = imageFs,
                allDirs = allDirs,
                allFiles = allFiles,
                device = device,
                isoInfo = isoInfo,
                targetDeviceName = targetDeviceName,
                bufferSizeBytes = bufferSizeBytes,
                verifyAfterWrite = verifyAfterWrite,
                isCancelled = isCancelled,
                isDisconnected = isDisconnected,
                onProgress = onProgress
            )
            WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS -> writeDualGptFat32Ntfs(
                imageFs = imageFs,
                allDirs = allDirs,
                allFiles = allFiles,
                device = device,
                isoInfo = isoInfo,
                targetDeviceName = targetDeviceName,
                bufferSizeBytes = bufferSizeBytes,
                verifyAfterWrite = verifyAfterWrite,
                isCancelled = isCancelled,
                isDisconnected = isDisconnected,
                onProgress = onProgress
            )
            WriteStrategy.WINDOWS_UEFI_DUAL_GPT_FAT32_EXFAT -> writeDualGptFat32ExFat(
                imageFs = imageFs,
                allDirs = allDirs,
                allFiles = allFiles,
                device = device,
                isoInfo = isoInfo,
                targetDeviceName = targetDeviceName,
                bufferSizeBytes = bufferSizeBytes,
                verifyAfterWrite = verifyAfterWrite,
                isCancelled = isCancelled,
                isDisconnected = isDisconnected,
                onProgress = onProgress
            )
            WriteStrategy.RAW_SECTOR_WRITE -> {
                throw IllegalArgumentException("WindowsUefiWriter does not handle RAW_SECTOR_WRITE.")
            }
        }
    }

    private suspend fun writeSingleFat32(
        imageFs: com.usbbootable.creator.core.image.ImageFileSystem,
        allDirs: List<ImageEntry>,
        allFiles: List<ImageEntry>,
        device: BlockDevice,
        isoInfo: SelectedIsoInfo,
        targetDeviceName: String,
        bufferSizeBytes: Int,
        verifyAfterWrite: Boolean,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean,
        onProgress: (WriteProgress) -> Unit
    ): VerificationReport {
        checkState(isCancelled, isDisconnected)
        val totalPayloadBytes = allFiles.sumOf { it.sizeBytes }.coerceAtLeast(1L)

        onProgress(
            WriteProgress(
                phase = WritePhase.PARTITIONING,
                totalBytes = totalPayloadBytes,
                overallProgress = 0.03f,
                currentItemLabel = "MBR + FAT32",
                statusMessage = "Writing MBR partition table and preparing FAT32 filesystem...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )

        val partRange = MbrPartitioner.writeSingleActivePartition(device, MbrPartitioner.TYPE_FAT32_LBA)
        logger.info(
            TAG,
            "Wrote MBR FAT32 partition: startLBA=${partRange.startLba}, sectors=${partRange.sectorCount}"
        )

        val partDevice = PartitionBlockDevice(device, partRange.startLba, partRange.sectorCount)
        val fat32 = Fat32FileSystem(
            device = partDevice,
            hiddenSectors = partRange.startLba,
            volumeLabel = isoInfo.volumeLabel.ifBlank { "WIN_BOOT" },
            ioBufferBytes = bufferSizeBytes,
            isCancelled = isCancelled
        )
        for (dir in allDirs) {
            fat32.ensureDirectory(dir.path)
        }

        val streamBuf = ByteArray(bufferSizeBytes.coerceIn(64 * 1024, 512 * 1024))
        val speedTracker = SpeedTracker()
        speedTracker.reset()
        var bytesWritten = 0L
        var lastEmitNanos = 0L
        val writeWeight = if (verifyAfterWrite) 0.65f else 0.91f

        for (file in allFiles) {
            checkState(isCancelled, isDisconnected)
            fat32.writeFile(
                normalizedPath = file.path,
                sizeBytes = file.sizeBytes,
                streamer = { consumer ->
                    imageFs.streamFile(file, streamBuf) { data, len ->
                        checkSyncState(isCancelled, isDisconnected)
                        consumer(data, len)
                    }
                },
                onBytesWritten = { delta ->
                    bytesWritten += delta
                    val now = System.nanoTime()
                    if (!isCancelled() && (now - lastEmitNanos >= PROGRESS_INTERVAL_NANOS || bytesWritten >= totalPayloadBytes)) {
                        lastEmitNanos = now
                        val (speed, eta) = speedTracker.record(bytesWritten, totalPayloadBytes)
                        val frac = (bytesWritten.toDouble() / totalPayloadBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                        onProgress(
                            WriteProgress(
                                phase = WritePhase.WRITING,
                                bytesProcessed = bytesWritten,
                                totalBytes = totalPayloadBytes,
                                phaseProgress = frac,
                                overallProgress = 0.05f + frac * writeWeight,
                                speedBytesPerSec = speed,
                                etaSeconds = eta,
                                currentItemLabel = file.path,
                                statusMessage = "Extracting Windows UEFI files to FAT32...",
                                targetDeviceName = targetDeviceName,
                                sourceIsoName = isoInfo.displayName,
                                bootSupport = isoInfo.bootSupport
                            )
                        )
                    }
                }
            )
        }

        checkState(isCancelled, isDisconnected)
        onProgress(
            WriteProgress(
                phase = WritePhase.FLUSHING_CACHE,
                bytesProcessed = bytesWritten,
                totalBytes = totalPayloadBytes,
                phaseProgress = 1f,
                overallProgress = if (verifyAfterWrite) 0.71f else 0.98f,
                currentItemLabel = "Writing FAT32 tables & flushing cache",
                statusMessage = "Finalizing FAT32 metadata and flushing USB cache...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )
        fat32.finalizeFileSystem()
        device.syncCache()
        logger.success(TAG, "Finalized FAT32 filesystem (${fat32.getWrittenFiles().size} files).")

        if (!verifyAfterWrite) {
            logger.warn(TAG, "Post-write SHA-256 verification skipped per user setting.")
            return VerificationReport(
                verified = true,
                methodDescription = "Post-write verification disabled in Settings",
                bytesVerified = bytesWritten,
                filesVerified = fat32.getWrittenFiles().size
            )
        }

        // Real SCSI read-back verification
        return verifyFat32Files(
            device = device,
            partStartLba = partRange.startLba,
            fat32 = fat32,
            records = fat32.getWrittenFiles(),
            totalPayloadBytes = totalPayloadBytes,
            isoInfo = isoInfo,
            targetDeviceName = targetDeviceName,
            isCancelled = isCancelled,
            isDisconnected = isDisconnected,
            onProgress = onProgress
        )
    }

    /**
     * UEFI-compatible NTFS boot solution for Windows 10/11 ISOs with `install.wim` > 4 GiB:
     * - Partition 1 (GPT EFI System Partition, FAT32): signed Microsoft UEFI bootloader + `boot.wim`
     * - Partition 2 (GPT Microsoft Basic Data Partition, NTFS 3.1): full Windows installer + `install.wim`
     */
    private suspend fun writeDualGptFat32Ntfs(
        imageFs: com.usbbootable.creator.core.image.ImageFileSystem,
        allDirs: List<ImageEntry>,
        allFiles: List<ImageEntry>,
        device: BlockDevice,
        isoInfo: SelectedIsoInfo,
        targetDeviceName: String,
        bufferSizeBytes: Int,
        verifyAfterWrite: Boolean,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean,
        onProgress: (WriteProgress) -> Unit
    ): VerificationReport {
        checkState(isCancelled, isDisconnected)

        val espFiles = allFiles.filter { IsoImageAnalyzer.isWindowsEspBootFile(it.normalizedPathLower) }
        val espBytes = espFiles.sumOf { it.sizeBytes }
        val dataBytes = allFiles.sumOf { it.sizeBytes }
        val totalPayloadBytes = (espBytes + dataBytes).coerceAtLeast(1L)

        val espPartitionBytes = if (device.capacityBytes >= 4L * 1024L * 1024L * 1024L) {
            maxOf(1024L * 1024L * 1024L, espBytes + 256L * 1024L * 1024L)
        } else {
            maxOf(64L * 1024L * 1024L, espBytes + 32L * 1024L * 1024L)
        }

        onProgress(
            WriteProgress(
                phase = WritePhase.PARTITIONING,
                totalBytes = totalPayloadBytes,
                overallProgress = 0.03f,
                currentItemLabel = "GPT Dual-Partition (FAT32 EFI + NTFS 3.1)",
                statusMessage = "Writing GPT partition table (FAT32 UEFI Boot + NTFS Windows Installer)...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )

        val (espSpec, dataSpec) = GptPartitioner.planDualWindowsUefiPartitions(device, espPartitionBytes)
        GptPartitioner.writeGptLayout(device, listOf(espSpec, dataSpec))
        logger.info(
            TAG,
            "Wrote GPT UEFI+NTFS layout: ESP=[${espSpec.startLba}..${espSpec.endLbaInclusive}] " +
                "(${ByteUtils.formatBytes(espSpec.sectorCount * device.blockSize)}), " +
                "NTFS=[${dataSpec.startLba}..${dataSpec.endLbaInclusive}] " +
                "(${ByteUtils.formatBytes(dataSpec.sectorCount * device.blockSize)})"
        )

        val espPartDevice = PartitionBlockDevice(device, espSpec.startLba, espSpec.sectorCount)
        val ntfsPartDevice = PartitionBlockDevice(device, dataSpec.startLba, dataSpec.sectorCount)

        val fat32 = Fat32FileSystem(
            device = espPartDevice,
            hiddenSectors = espSpec.startLba,
            volumeLabel = "WIN_UEFI",
            ioBufferBytes = bufferSizeBytes,
            isCancelled = isCancelled
        )
        val ntfs = NtfsFileSystem(
            device = ntfsPartDevice,
            hiddenSectors = dataSpec.startLba,
            volumeLabel = "WIN_INSTALL",
            ioBufferBytes = bufferSizeBytes,
            isCancelled = isCancelled
        )

        val streamBuf = ByteArray(bufferSizeBytes.coerceIn(64 * 1024, 512 * 1024))
        val speedTracker = SpeedTracker()
        speedTracker.reset()
        var bytesWritten = 0L
        var lastEmitNanos = 0L
        val writeWeight = if (verifyAfterWrite) 0.65f else 0.91f

        fun emitWriteProgress(label: String, status: String) {
            if (isCancelled()) return
            val now = System.nanoTime()
            if (now - lastEmitNanos >= PROGRESS_INTERVAL_NANOS || bytesWritten >= totalPayloadBytes) {
                lastEmitNanos = now
                val (speed, eta) = speedTracker.record(bytesWritten, totalPayloadBytes)
                val frac = (bytesWritten.toDouble() / totalPayloadBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                onProgress(
                    WriteProgress(
                        phase = WritePhase.WRITING,
                        bytesProcessed = bytesWritten,
                        totalBytes = totalPayloadBytes,
                        phaseProgress = frac,
                        overallProgress = 0.05f + frac * writeWeight,
                        speedBytesPerSec = speed,
                        etaSeconds = eta,
                        currentItemLabel = label,
                        statusMessage = status,
                        targetDeviceName = targetDeviceName,
                        sourceIsoName = isoInfo.displayName,
                        bootSupport = isoInfo.bootSupport
                    )
                )
            }
        }

        // Phase A: Populate FAT32 EFI System Partition with Windows UEFI boot files
        logger.info(TAG, "Populating Partition 1 (FAT32 ESP) with ${espFiles.size} UEFI boot files...")
        for (file in espFiles) {
            checkState(isCancelled, isDisconnected)
            fat32.writeFile(
                normalizedPath = file.path,
                sizeBytes = file.sizeBytes,
                streamer = { consumer ->
                    imageFs.streamFile(file, streamBuf) { data, len ->
                        checkSyncState(isCancelled, isDisconnected)
                        consumer(data, len)
                    }
                },
                onBytesWritten = { delta ->
                    bytesWritten += delta
                    emitWriteProgress("[FAT32 ESP] ${file.path}", "Writing UEFI boot files to Partition 1 (FAT32)...")
                }
            )
        }
        fat32.finalizeFileSystem()
        logger.success(TAG, "Partition 1 (FAT32 ESP) finalized (${espFiles.size} files).")

        // Phase B: Populate NTFS 3.1 Data Partition with full Windows installer (including >4 GiB install.wim)
        logger.info(TAG, "Populating Partition 2 (NTFS 3.1) with ${allFiles.size} Windows installer files...")
        for (dir in allDirs) {
            ntfs.ensureDirectory(dir.path)
        }
        for (file in allFiles) {
            checkState(isCancelled, isDisconnected)
            ntfs.writeFile(
                normalizedPath = file.path,
                sizeBytes = file.sizeBytes,
                streamer = { consumer ->
                    imageFs.streamFile(file, streamBuf) { data, len ->
                        checkSyncState(isCancelled, isDisconnected)
                        consumer(data, len)
                    }
                },
                onBytesWritten = { delta ->
                    bytesWritten += delta
                    emitWriteProgress("[NTFS] ${file.path}", "Writing Windows installer files to Partition 2 (NTFS)...")
                }
            )
        }

        checkState(isCancelled, isDisconnected)
        onProgress(
            WriteProgress(
                phase = WritePhase.FLUSHING_CACHE,
                bytesProcessed = bytesWritten,
                totalBytes = totalPayloadBytes,
                phaseProgress = 1f,
                overallProgress = if (verifyAfterWrite) 0.71f else 0.98f,
                currentItemLabel = "Finalizing NTFS MFT & flushing USB cache",
                statusMessage = "Writing NTFS 3.1 Master File Table and flushing drive cache...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )
        ntfs.finalizeFileSystem()
        device.syncCache()
        logger.success(TAG, "Partition 2 (NTFS 3.1) finalized (${allFiles.size} files). Cache flushed.")

        if (!verifyAfterWrite) {
            logger.warn(TAG, "Post-write SHA-256 verification skipped per user setting.")
            return VerificationReport(
                verified = true,
                methodDescription = "Post-write verification disabled in Settings",
                bytesVerified = bytesWritten,
                filesVerified = fat32.getWrittenFiles().size + ntfs.getWrittenFiles().size
            )
        }

        // Phase C: Full SCSI read-back SHA-256 verification of GPT headers, NTFS Boot Sector, and all files!
        logger.info(TAG, "Verifying GPT headers + NTFS boot sector + all files on FAT32 ESP and NTFS partitions...")
        val secBuf = ByteArray(device.blockSize)
        device.readBlocks(1L, 1, secBuf, 0)
        val primarySig = String(secBuf, 0, 8, Charsets.US_ASCII)
        device.readBlocks(device.totalBlocks - 1L, 1, secBuf, 0)
        val backupSig = String(secBuf, 0, 8, Charsets.US_ASCII)
        if (primarySig != "EFI PART" || backupSig != "EFI PART") {
            return VerificationReport(
                verified = false,
                methodDescription = "GPT + FAT32 ESP + NTFS 3.1 SHA-256 read-back verification",
                bytesVerified = 0L,
                mismatchDetail = "GPT header signature mismatch (primary='$primarySig', backup='$backupSig')."
            )
        }

        device.readBlocks(dataSpec.startLba, 1, secBuf, 0)
        val ntfsOem = String(secBuf, 3, 8, Charsets.US_ASCII)
        if (ntfsOem != "NTFS    ") {
            return VerificationReport(
                verified = false,
                methodDescription = "GPT + FAT32 ESP + NTFS 3.1 SHA-256 read-back verification",
                bytesVerified = 0L,
                mismatchDetail = "NTFS boot sector signature mismatch (got '$ntfsOem')."
            )
        }

        val fatRecords = fat32.getWrittenFiles()
        val ntfsRecords = ntfs.getWrittenFiles()
        var bytesVerified = 0L
        var filesVerified = 0
        speedTracker.reset()
        lastEmitNanos = 0L

        fun emitVerify(label: String) {
            if (isCancelled()) return
            val now = System.nanoTime()
            if (now - lastEmitNanos >= PROGRESS_INTERVAL_NANOS || bytesVerified >= totalPayloadBytes) {
                lastEmitNanos = now
                val (speed, eta) = speedTracker.record(bytesVerified, totalPayloadBytes)
                val frac = (bytesVerified.toDouble() / totalPayloadBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                onProgress(
                    WriteProgress(
                        phase = WritePhase.VERIFYING,
                        bytesProcessed = bytesVerified,
                        totalBytes = totalPayloadBytes,
                        phaseProgress = frac,
                        overallProgress = 0.72f + frac * 0.28f,
                        speedBytesPerSec = speed,
                        etaSeconds = eta,
                        currentItemLabel = label,
                        statusMessage = "Verifying written files via SCSI read-back SHA-256...",
                        targetDeviceName = targetDeviceName,
                        sourceIsoName = isoInfo.displayName,
                        bootSupport = isoInfo.bootSupport
                    )
                )
            }
        }

        for (rec in fatRecords) {
            checkState(isCancelled, isDisconnected)
            val ok = fat32.verifyFile(rec) { delta ->
                bytesVerified += delta
                emitVerify("[Verify FAT32] ${rec.path}")
            }
            if (!ok) {
                logger.error(TAG, "SHA-256 verification failed on FAT32 ESP file: ${rec.path}")
                return VerificationReport(
                    verified = false,
                    methodDescription = "GPT + FAT32 ESP + NTFS 3.1 SHA-256 read-back verification",
                    bytesVerified = bytesVerified,
                    filesVerified = filesVerified,
                    mismatchDetail = "SHA-256 mismatch on FAT32 boot file '${rec.path}'."
                )
            }
            filesVerified++
        }

        for (rec in ntfsRecords) {
            checkState(isCancelled, isDisconnected)
            val ok = ntfs.verifyFile(rec) { delta ->
                bytesVerified += delta
                emitVerify("[Verify NTFS] ${rec.path}")
            }
            if (!ok) {
                logger.error(TAG, "SHA-256 verification failed on NTFS file: ${rec.path}")
                return VerificationReport(
                    verified = false,
                    methodDescription = "GPT + FAT32 ESP + NTFS 3.1 SHA-256 read-back verification",
                    bytesVerified = bytesVerified,
                    filesVerified = filesVerified,
                    mismatchDetail = "SHA-256 mismatch on NTFS installer file '${rec.path}'."
                )
            }
            filesVerified++
        }

        logger.success(
            TAG,
            "GPT FAT32+NTFS verification PASSED: $filesVerified files (${ByteUtils.formatBytes(bytesVerified)}) verified!"
        )
        return VerificationReport(
            verified = true,
            methodDescription = "GPT + FAT32 ESP + NTFS 3.1 full SCSI read-back SHA-256 verification",
            bytesVerified = bytesVerified,
            filesVerified = filesVerified
        )
    }

    private suspend fun writeDualGptFat32ExFat(
        imageFs: com.usbbootable.creator.core.image.ImageFileSystem,
        allDirs: List<ImageEntry>,
        allFiles: List<ImageEntry>,
        device: BlockDevice,
        isoInfo: SelectedIsoInfo,
        targetDeviceName: String,
        bufferSizeBytes: Int,
        verifyAfterWrite: Boolean,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean,
        onProgress: (WriteProgress) -> Unit
    ): VerificationReport {
        checkState(isCancelled, isDisconnected)

        // Partition 1 (FAT32 ESP): UEFI bootloader, BCD, and sources/boot.wim
        val espFiles = allFiles.filter { IsoImageAnalyzer.isWindowsEspBootFile(it.normalizedPathLower) }
        val espBytes = espFiles.sumOf { it.sizeBytes }
        // Partition 2 (exFAT): complete installer tree including >4 GiB sources/install.wim
        val dataBytes = allFiles.sumOf { it.sizeBytes }
        val totalPayloadBytes = (espBytes + dataBytes).coerceAtLeast(1L)

        // Compute ESP partition size with safe headroom (at least 64 MiB for FAT32, up to 1 GiB+ on large drives)
        val espPartitionBytes = if (device.capacityBytes >= 4L * 1024L * 1024L * 1024L) {
            maxOf(1024L * 1024L * 1024L, espBytes + 256L * 1024L * 1024L)
        } else {
            maxOf(64L * 1024L * 1024L, espBytes + 32L * 1024L * 1024L)
        }

        onProgress(
            WriteProgress(
                phase = WritePhase.PARTITIONING,
                totalBytes = totalPayloadBytes,
                overallProgress = 0.03f,
                currentItemLabel = "GPT Dual-Partition (FAT32 ESP + exFAT)",
                statusMessage = "Writing GPT partition table (FAT32 UEFI Boot + exFAT Data)...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )

        val (espSpec, dataSpec) = GptPartitioner.planDualWindowsUefiPartitions(device, espPartitionBytes)
        GptPartitioner.writeGptLayout(device, listOf(espSpec, dataSpec))
        logger.info(
            TAG,
            "Wrote GPT Dual-Partition: ESP=[${espSpec.startLba}..${espSpec.endLbaInclusive}] " +
                "(${ByteUtils.formatBytes(espSpec.sectorCount * device.blockSize)}), " +
                "DATA=[${dataSpec.startLba}..${dataSpec.endLbaInclusive}] " +
                "(${ByteUtils.formatBytes(dataSpec.sectorCount * device.blockSize)})"
        )

        val espPartDevice = PartitionBlockDevice(device, espSpec.startLba, espSpec.sectorCount)
        val dataPartDevice = PartitionBlockDevice(device, dataSpec.startLba, dataSpec.sectorCount)

        val fat32 = Fat32FileSystem(
            device = espPartDevice,
            hiddenSectors = espSpec.startLba,
            volumeLabel = "WIN_UEFI",
            ioBufferBytes = bufferSizeBytes,
            isCancelled = isCancelled
        )
        val exFat = ExFatFileSystem(
            device = dataPartDevice,
            partitionOffsetLba = dataSpec.startLba,
            volumeLabel = "WIN_INSTALL",
            ioBufferBytes = bufferSizeBytes,
            isCancelled = isCancelled
        )

        val streamBuf = ByteArray(bufferSizeBytes.coerceIn(64 * 1024, 512 * 1024))
        val speedTracker = SpeedTracker()
        speedTracker.reset()
        var bytesWritten = 0L
        var lastEmitNanos = 0L
        val writeWeight = if (verifyAfterWrite) 0.65f else 0.91f

        fun emitWriteProgress(label: String, status: String) {
            if (isCancelled()) return
            val now = System.nanoTime()
            if (now - lastEmitNanos >= PROGRESS_INTERVAL_NANOS || bytesWritten >= totalPayloadBytes) {
                lastEmitNanos = now
                val (speed, eta) = speedTracker.record(bytesWritten, totalPayloadBytes)
                val frac = (bytesWritten.toDouble() / totalPayloadBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                onProgress(
                    WriteProgress(
                        phase = WritePhase.WRITING,
                        bytesProcessed = bytesWritten,
                        totalBytes = totalPayloadBytes,
                        phaseProgress = frac,
                        overallProgress = 0.05f + frac * writeWeight,
                        speedBytesPerSec = speed,
                        etaSeconds = eta,
                        currentItemLabel = label,
                        statusMessage = status,
                        targetDeviceName = targetDeviceName,
                        sourceIsoName = isoInfo.displayName,
                        bootSupport = isoInfo.bootSupport
                    )
                )
            }
        }

        // Phase A: Populate FAT32 EFI System Partition with Windows UEFI boot components
        logger.info(TAG, "Populating Partition 1 (FAT32 ESP) with ${espFiles.size} UEFI boot files...")
        for (file in espFiles) {
            checkState(isCancelled, isDisconnected)
            fat32.writeFile(
                normalizedPath = file.path,
                sizeBytes = file.sizeBytes,
                streamer = { consumer ->
                    imageFs.streamFile(file, streamBuf) { data, len ->
                        checkSyncState(isCancelled, isDisconnected)
                        consumer(data, len)
                    }
                },
                onBytesWritten = { delta ->
                    bytesWritten += delta
                    emitWriteProgress("[FAT32 ESP] ${file.path}", "Writing UEFI boot files to Partition 1 (FAT32)...")
                }
            )
        }
        fat32.finalizeFileSystem()
        logger.success(TAG, "Partition 1 (FAT32 ESP) finalized (${espFiles.size} files).")

        // Phase B: Populate exFAT Data Partition with full Windows installer (including >4 GiB install.wim)
        logger.info(TAG, "Populating Partition 2 (exFAT) with ${allFiles.size} Windows installer files...")
        for (dir in allDirs) {
            exFat.ensureDirectory(dir.path)
        }
        for (file in allFiles) {
            checkState(isCancelled, isDisconnected)
            exFat.writeFile(
                normalizedPath = file.path,
                sizeBytes = file.sizeBytes,
                streamer = { consumer ->
                    imageFs.streamFile(file, streamBuf) { data, len ->
                        checkSyncState(isCancelled, isDisconnected)
                        consumer(data, len)
                    }
                },
                onBytesWritten = { delta ->
                    bytesWritten += delta
                    emitWriteProgress("[exFAT] ${file.path}", "Writing Windows installer files to Partition 2 (exFAT)...")
                }
            )
        }

        checkState(isCancelled, isDisconnected)
        onProgress(
            WriteProgress(
                phase = WritePhase.FLUSHING_CACHE,
                bytesProcessed = bytesWritten,
                totalBytes = totalPayloadBytes,
                phaseProgress = 1f,
                overallProgress = if (verifyAfterWrite) 0.71f else 0.98f,
                currentItemLabel = "Finalizing exFAT & flushing USB cache",
                statusMessage = "Writing exFAT metadata and flushing drive cache...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )
        exFat.finalizeFileSystem()
        device.syncCache()
        logger.success(TAG, "Partition 2 (exFAT) finalized (${allFiles.size} files). Cache flushed.")

        if (!verifyAfterWrite) {
            logger.warn(TAG, "Post-write SHA-256 verification skipped per user setting.")
            return VerificationReport(
                verified = true,
                methodDescription = "Post-write verification disabled in Settings",
                bytesVerified = bytesWritten,
                filesVerified = fat32.getWrittenFiles().size + exFat.getWrittenFiles().size
            )
        }

        // Phase C: Full SCSI read-back SHA-256 verification of GPT headers + BOTH partitions!
        logger.info(TAG, "Verifying GPT headers + all files on FAT32 ESP and exFAT Data partitions...")
        val secBuf = ByteArray(device.blockSize)
        device.readBlocks(1L, 1, secBuf, 0)
        val primarySig = String(secBuf, 0, 8, Charsets.US_ASCII)
        device.readBlocks(device.totalBlocks - 1L, 1, secBuf, 0)
        val backupSig = String(secBuf, 0, 8, Charsets.US_ASCII)
        if (primarySig != "EFI PART" || backupSig != "EFI PART") {
            return VerificationReport(
                verified = false,
                methodDescription = "GPT + Dual-Partition FAT32/exFAT SHA-256 read-back verification",
                bytesVerified = 0L,
                mismatchDetail = "GPT header signature mismatch (primary='$primarySig', backup='$backupSig')."
            )
        }

        val fatRecords = fat32.getWrittenFiles()
        val exFatRecords = exFat.getWrittenFiles()
        var bytesVerified = 0L
        var filesVerified = 0
        speedTracker.reset()
        lastEmitNanos = 0L

        fun emitVerify(label: String) {
            if (isCancelled()) return
            val now = System.nanoTime()
            if (now - lastEmitNanos >= PROGRESS_INTERVAL_NANOS || bytesVerified >= totalPayloadBytes) {
                lastEmitNanos = now
                val (speed, eta) = speedTracker.record(bytesVerified, totalPayloadBytes)
                val frac = (bytesVerified.toDouble() / totalPayloadBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                onProgress(
                    WriteProgress(
                        phase = WritePhase.VERIFYING,
                        bytesProcessed = bytesVerified,
                        totalBytes = totalPayloadBytes,
                        phaseProgress = frac,
                        overallProgress = 0.72f + frac * 0.28f,
                        speedBytesPerSec = speed,
                        etaSeconds = eta,
                        currentItemLabel = label,
                        statusMessage = "Verifying written files via SCSI read-back SHA-256...",
                        targetDeviceName = targetDeviceName,
                        sourceIsoName = isoInfo.displayName,
                        bootSupport = isoInfo.bootSupport
                    )
                )
            }
        }

        for (rec in fatRecords) {
            checkState(isCancelled, isDisconnected)
            val ok = fat32.verifyFile(rec) { delta ->
                bytesVerified += delta
                emitVerify("[Verify FAT32] ${rec.path}")
            }
            if (!ok) {
                logger.error(TAG, "SHA-256 verification failed on FAT32 ESP file: ${rec.path}")
                return VerificationReport(
                    verified = false,
                    methodDescription = "GPT + Dual-Partition FAT32/exFAT SHA-256 read-back verification",
                    bytesVerified = bytesVerified,
                    filesVerified = filesVerified,
                    mismatchDetail = "SHA-256 mismatch on FAT32 boot file '${rec.path}'."
                )
            }
            filesVerified++
        }

        for (rec in exFatRecords) {
            checkState(isCancelled, isDisconnected)
            val ok = exFat.verifyFile(rec) { delta ->
                bytesVerified += delta
                emitVerify("[Verify exFAT] ${rec.path}")
            }
            if (!ok) {
                logger.error(TAG, "SHA-256 verification failed on exFAT file: ${rec.path}")
                return VerificationReport(
                    verified = false,
                    methodDescription = "GPT + Dual-Partition FAT32/exFAT SHA-256 read-back verification",
                    bytesVerified = bytesVerified,
                    filesVerified = filesVerified,
                    mismatchDetail = "SHA-256 mismatch on exFAT installer file '${rec.path}'."
                )
            }
            filesVerified++
        }

        logger.success(
            TAG,
            "Dual-partition verification PASSED: $filesVerified files (${ByteUtils.formatBytes(bytesVerified)}) verified!"
        )
        return VerificationReport(
            verified = true,
            methodDescription = "GPT + FAT32 ESP + exFAT full SCSI read-back SHA-256 verification",
            bytesVerified = bytesVerified,
            filesVerified = filesVerified
        )
    }

    private suspend fun verifyFat32Files(
        device: BlockDevice,
        partStartLba: Long,
        fat32: Fat32FileSystem,
        records: List<WrittenFileAllocation>,
        totalPayloadBytes: Long,
        isoInfo: SelectedIsoInfo,
        targetDeviceName: String,
        isCancelled: () -> Boolean,
        isDisconnected: () -> Boolean,
        onProgress: (WriteProgress) -> Unit
    ): VerificationReport {
        val secBuf = ByteArray(device.blockSize)
        device.readBlocks(0L, 1, secBuf, 0)
        if ((secBuf[510].toInt() and 0xFF) != 0x55 || (secBuf[511].toInt() and 0xFF) != 0xAA) {
            return VerificationReport(
                verified = false,
                methodDescription = "MBR + FAT32 full SCSI read-back SHA-256 verification",
                bytesVerified = 0L,
                mismatchDetail = "MBR boot signature 0x55AA missing on read-back."
            )
        }
        device.readBlocks(partStartLba, 1, secBuf, 0)
        val fsType = String(secBuf, 82, 8, Charsets.US_ASCII)
        if (fsType != "FAT32   ") {
            return VerificationReport(
                verified = false,
                methodDescription = "MBR + FAT32 full SCSI read-back SHA-256 verification",
                bytesVerified = 0L,
                mismatchDetail = "FAT32 boot sector signature mismatch (got '$fsType')."
            )
        }

        val speedTracker = SpeedTracker()
        speedTracker.reset()
        var bytesVerified = 0L
        var filesVerified = 0
        var lastEmitNanos = 0L

        for (rec in records) {
            checkState(isCancelled, isDisconnected)
            val ok = fat32.verifyFile(rec) { delta ->
                bytesVerified += delta
                val now = System.nanoTime()
                if (!isCancelled() && (now - lastEmitNanos >= PROGRESS_INTERVAL_NANOS || bytesVerified >= totalPayloadBytes)) {
                    lastEmitNanos = now
                    val (speed, eta) = speedTracker.record(bytesVerified, totalPayloadBytes)
                    val frac = (bytesVerified.toDouble() / totalPayloadBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                    onProgress(
                        WriteProgress(
                            phase = WritePhase.VERIFYING,
                            bytesProcessed = bytesVerified,
                            totalBytes = totalPayloadBytes,
                            phaseProgress = frac,
                            overallProgress = 0.72f + frac * 0.28f,
                            speedBytesPerSec = speed,
                            etaSeconds = eta,
                            currentItemLabel = "[Verify] ${rec.path}",
                            statusMessage = "Verifying extracted FAT32 files via SHA-256...",
                            targetDeviceName = targetDeviceName,
                            sourceIsoName = isoInfo.displayName,
                            bootSupport = isoInfo.bootSupport
                        )
                    )
                }
            }
            if (!ok) {
                logger.error(TAG, "Verification SHA-256 mismatch on FAT32 file: ${rec.path}")
                return VerificationReport(
                    verified = false,
                    methodDescription = "MBR + FAT32 full SCSI read-back SHA-256 verification",
                    bytesVerified = bytesVerified,
                    filesVerified = filesVerified,
                    mismatchDetail = "SHA-256 mismatch on file '${rec.path}'."
                )
            }
            filesVerified++
        }

        logger.success(
            TAG,
            "Single-FAT32 verification PASSED: $filesVerified files (${ByteUtils.formatBytes(bytesVerified)}) verified!"
        )
        return VerificationReport(
            verified = true,
            methodDescription = "MBR + FAT32 full SCSI read-back SHA-256 verification",
            bytesVerified = bytesVerified,
            filesVerified = filesVerified
        )
    }

    private suspend fun checkState(isCancelled: () -> Boolean, isDisconnected: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        checkSyncState(isCancelled, isDisconnected)
    }

    private fun checkSyncState(isCancelled: () -> Boolean, isDisconnected: () -> Boolean) {
        if (isDisconnected()) throw UsbDisconnectedException()
        if (isCancelled()) throw CancellationException("Write cancelled by user.")
    }

    companion object {
        private const val TAG = "WinUefiWriter"
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
    }
}
