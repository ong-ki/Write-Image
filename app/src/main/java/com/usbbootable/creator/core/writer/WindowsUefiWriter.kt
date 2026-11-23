package com.usbbootable.creator.core.writer

import com.usbbootable.creator.core.filesystem.ExFatFileSystem
import com.usbbootable.creator.core.filesystem.Fat32FileSystem
import com.usbbootable.creator.core.filesystem.GptPartitioner
import com.usbbootable.creator.core.filesystem.MbrPartitioner
import com.usbbootable.creator.core.filesystem.NtfsFileSystem
import com.usbbootable.creator.core.filesystem.WrittenFileAllocation
import com.usbbootable.creator.core.image.DetectedOsFamily
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
        windowsBypassOptions: WindowsBypassOptions = WindowsBypassOptions.NONE,
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

        val effectiveBypass = if (isoInfo.detectedOsFamily == DetectedOsFamily.WINDOWS && windowsBypassOptions.hasAnyEnabled) {
            windowsBypassOptions
        } else {
            WindowsBypassOptions.NONE
        }
        val bypassFiles = if (effectiveBypass.hasAnyEnabled) {
            val generated = WindowsBypassGenerator.generateFiles(
                options = effectiveBypass,
                lowerIsoPaths = allFiles.map { it.normalizedPathLower }.toSet()
            )
            logger.info(
                TAG,
                "Applying Windows Requirements Bypass (${effectiveBypass.enabledLabels().joinToString(", ")}): " +
                    generated.joinToString(", ") { it.path }
            )
            generated
        } else {
            emptyList()
        }

        return when (isoInfo.writeStrategy) {
            WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32 -> writeSingleFat32(
                imageFs = imageFs,
                allDirs = allDirs,
                allFiles = allFiles,
                bypassFiles = bypassFiles,
                device = device,
                isoInfo = isoInfo,
                targetDeviceName = targetDeviceName,
                bufferSizeBytes = bufferSizeBytes,
                verifyAfterWrite = verifyAfterWrite,
                isCancelled = isCancelled,
                isDisconnected = isDisconnected,
                onProgress = onProgress
            )
            WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS -> writeDualGptWindows(
                useNtfs = true,
                imageFs = imageFs,
                allDirs = allDirs,
                allFiles = allFiles,
                bypassFiles = bypassFiles,
                device = device,
                isoInfo = isoInfo,
                targetDeviceName = targetDeviceName,
                bufferSizeBytes = bufferSizeBytes,
                verifyAfterWrite = verifyAfterWrite,
                isCancelled = isCancelled,
                isDisconnected = isDisconnected,
                onProgress = onProgress
            )
            WriteStrategy.WINDOWS_UEFI_DUAL_GPT_FAT32_EXFAT -> writeDualGptWindows(
                useNtfs = false,
                imageFs = imageFs,
                allDirs = allDirs,
                allFiles = allFiles,
                bypassFiles = bypassFiles,
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
        bypassFiles: List<GeneratedBypassFile>,
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
        val bypassPathsLower = bypassFiles.map { it.normalizedPathLower }.toSet()
        val filesToExtract = if (bypassPathsLower.isEmpty()) {
            allFiles
        } else {
            allFiles.filterNot { it.normalizedPathLower in bypassPathsLower }
        }
        val totalPayloadBytes = (filesToExtract.sumOf { it.sizeBytes } + bypassFiles.sumOf { it.content.size.toLong() })
            .coerceAtLeast(1L)

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
        var lastEmitNanos = System.nanoTime() - PROGRESS_INTERVAL_NANOS
        val writeWeight = if (verifyAfterWrite) 0.65f else 0.91f

        for (file in filesToExtract) {
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
                                bytesWritten = bytesWritten,
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

        for (bypassFile in bypassFiles) {
            checkState(isCancelled, isDisconnected)
            fat32.writeFile(
                normalizedPath = bypassFile.path,
                sizeBytes = bypassFile.content.size.toLong(),
                streamer = { consumer ->
                    checkSyncState(isCancelled, isDisconnected)
                    consumer(bypassFile.content, bypassFile.content.size)
                },
                onBytesWritten = { delta ->
                    bytesWritten += delta
                }
            )
        }

        checkState(isCancelled, isDisconnected)
        onProgress(
            WriteProgress(
                phase = WritePhase.FLUSHING_CACHE,
                bytesProcessed = bytesWritten,
                bytesWritten = bytesWritten,
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
            bytesWritten = bytesWritten,
            totalPayloadBytes = totalPayloadBytes,
            isoInfo = isoInfo,
            targetDeviceName = targetDeviceName,
            isCancelled = isCancelled,
            isDisconnected = isDisconnected,
            onProgress = onProgress
        )
    }

    private interface DualDataPartitionAdapter {
        val fsTag: String
        val fsLabel: String
        val gptHeadline: String
        val gptStatus: String
        val flushLabel: String
        val flushStatus: String
        val methodSummary: String
        val expectedOemSignature: String
        fun ensureDirectory(path: String)
        fun writeFile(
            normalizedPath: String,
            sizeBytes: Long,
            streamer: ((ByteArray, Int) -> Unit) -> Unit,
            onBytesWritten: (Long) -> Unit
        ): WrittenFileAllocation
        fun finalizeFileSystem()
        fun getWrittenFiles(): List<WrittenFileAllocation>
        fun verifyFile(record: WrittenFileAllocation, onBytesVerified: (Long) -> Unit): Boolean
    }

    /**
     * Unified GPT Dual-Partition writer for Windows ISOs > 4 GiB:
     * - Partition 1 (GPT EFI System Partition, FAT32): signed UEFI bootloader + `boot.wim`
     * - Partition 2 (GPT Microsoft Basic Data Partition, NTFS 3.1 or exFAT): complete installer tree
     */
    private suspend fun writeDualGptWindows(
        useNtfs: Boolean,
        imageFs: com.usbbootable.creator.core.image.ImageFileSystem,
        allDirs: List<ImageEntry>,
        allFiles: List<ImageEntry>,
        bypassFiles: List<GeneratedBypassFile>,
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

        val espBypassFiles = bypassFiles.filter { it.includeOnEsp }
        val espBypassPathsLower = espBypassFiles.map { it.normalizedPathLower }.toSet()
        val dataBypassPathsLower = bypassFiles.map { it.normalizedPathLower }.toSet()

        val espFiles = allFiles.filter {
            IsoImageAnalyzer.isWindowsEspBootFile(it.normalizedPathLower) &&
                it.normalizedPathLower !in espBypassPathsLower
        }
        val dataFiles = if (dataBypassPathsLower.isEmpty()) {
            allFiles
        } else {
            allFiles.filterNot { it.normalizedPathLower in dataBypassPathsLower }
        }

        val espBytes = espFiles.sumOf { it.sizeBytes } + espBypassFiles.sumOf { it.content.size.toLong() }
        val dataBytes = dataFiles.sumOf { it.sizeBytes } + bypassFiles.sumOf { it.content.size.toLong() }
        val totalPayloadBytes = (espBytes + dataBytes).coerceAtLeast(1L)

        val espPartitionBytes = if (device.capacityBytes >= 4L * 1024L * 1024L * 1024L) {
            maxOf(1024L * 1024L * 1024L, espBytes + 256L * 1024L * 1024L)
        } else {
            maxOf(64L * 1024L * 1024L, espBytes + 32L * 1024L * 1024L)
        }

        val (espSpec, dataSpec) = GptPartitioner.planDualWindowsUefiPartitions(device, espPartitionBytes)
        val espPartDevice = PartitionBlockDevice(device, espSpec.startLba, espSpec.sectorCount)
        val dataPartDevice = PartitionBlockDevice(device, dataSpec.startLba, dataSpec.sectorCount)

        val adapter: DualDataPartitionAdapter = if (useNtfs) {
            val expectedEntries = allFiles.size + allDirs.size + bypassFiles.size + 16
            val ntfs = NtfsFileSystem(
                device = dataPartDevice,
                hiddenSectors = dataSpec.startLba,
                volumeLabel = "WIN_INSTALL",
                ioBufferBytes = bufferSizeBytes,
                isCancelled = isCancelled,
                expectedEntryCount = expectedEntries
            )
            object : DualDataPartitionAdapter {
                override val fsTag = "NTFS"
                override val fsLabel = "NTFS 3.1"
                override val gptHeadline = "GPT Dual-Partition (FAT32 EFI + NTFS 3.1)"
                override val gptStatus = "Writing GPT partition table (FAT32 UEFI Boot + NTFS Windows Installer)..."
                override val flushLabel = "Finalizing NTFS MFT & flushing USB cache"
                override val flushStatus = "Writing NTFS 3.1 Master File Table and flushing drive cache..."
                override val methodSummary = "GPT + FAT32 ESP + NTFS 3.1"
                override val expectedOemSignature = "NTFS    "
                override fun ensureDirectory(path: String) = ntfs.ensureDirectory(path)
                override fun writeFile(
                    normalizedPath: String,
                    sizeBytes: Long,
                    streamer: ((ByteArray, Int) -> Unit) -> Unit,
                    onBytesWritten: (Long) -> Unit
                ) = ntfs.writeFile(normalizedPath, sizeBytes, streamer, onBytesWritten)
                override fun finalizeFileSystem() = ntfs.finalizeFileSystem()
                override fun getWrittenFiles() = ntfs.getWrittenFiles()
                override fun verifyFile(record: WrittenFileAllocation, onBytesVerified: (Long) -> Unit) =
                    ntfs.verifyFile(record, onBytesVerified)
            }
        } else {
            val exFat = ExFatFileSystem(
                device = dataPartDevice,
                partitionOffsetLba = dataSpec.startLba,
                volumeLabel = "WIN_INSTALL",
                ioBufferBytes = bufferSizeBytes,
                isCancelled = isCancelled
            )
            object : DualDataPartitionAdapter {
                override val fsTag = "exFAT"
                override val fsLabel = "exFAT"
                override val gptHeadline = "GPT Dual-Partition (FAT32 ESP + exFAT)"
                override val gptStatus = "Writing GPT partition table (FAT32 UEFI Boot + exFAT Data)..."
                override val flushLabel = "Finalizing exFAT & flushing USB cache"
                override val flushStatus = "Writing exFAT metadata and flushing drive cache..."
                override val methodSummary = "GPT + FAT32 ESP + exFAT"
                override val expectedOemSignature = "EXFAT   "
                override fun ensureDirectory(path: String) = exFat.ensureDirectory(path)
                override fun writeFile(
                    normalizedPath: String,
                    sizeBytes: Long,
                    streamer: ((ByteArray, Int) -> Unit) -> Unit,
                    onBytesWritten: (Long) -> Unit
                ) = exFat.writeFile(normalizedPath, sizeBytes, streamer, onBytesWritten)
                override fun finalizeFileSystem() = exFat.finalizeFileSystem()
                override fun getWrittenFiles() = exFat.getWrittenFiles()
                override fun verifyFile(record: WrittenFileAllocation, onBytesVerified: (Long) -> Unit) =
                    exFat.verifyFile(record, onBytesVerified)
            }
        }

        val fat32 = Fat32FileSystem(
            device = espPartDevice,
            hiddenSectors = espSpec.startLba,
            volumeLabel = "WIN_UEFI",
            ioBufferBytes = bufferSizeBytes,
            isCancelled = isCancelled
        )

        onProgress(
            WriteProgress(
                phase = WritePhase.PARTITIONING,
                totalBytes = totalPayloadBytes,
                overallProgress = 0.03f,
                currentItemLabel = adapter.gptHeadline,
                statusMessage = adapter.gptStatus,
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )

        GptPartitioner.writeGptLayout(device, listOf(espSpec, dataSpec))
        logger.info(
            TAG,
            "Wrote GPT UEFI+${adapter.fsTag} layout: ESP=[${espSpec.startLba}..${espSpec.endLbaInclusive}] " +
                "(${ByteUtils.formatBytes(espSpec.sectorCount * device.blockSize)}), " +
                "${adapter.fsTag}=[${dataSpec.startLba}..${dataSpec.endLbaInclusive}] " +
                "(${ByteUtils.formatBytes(dataSpec.sectorCount * device.blockSize)})"
        )

        val streamBuf = ByteArray(bufferSizeBytes.coerceIn(64 * 1024, 512 * 1024))
        val speedTracker = SpeedTracker()
        speedTracker.reset()
        var bytesWritten = 0L
        var lastEmitNanos = System.nanoTime() - PROGRESS_INTERVAL_NANOS
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
                        bytesWritten = bytesWritten,
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
        logger.info(TAG, "Populating Partition 1 (FAT32 ESP) with ${espFiles.size + espBypassFiles.size} UEFI boot files...")
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
        for (bypassFile in espBypassFiles) {
            checkState(isCancelled, isDisconnected)
            fat32.writeFile(
                normalizedPath = bypassFile.path,
                sizeBytes = bypassFile.content.size.toLong(),
                streamer = { consumer ->
                    checkSyncState(isCancelled, isDisconnected)
                    consumer(bypassFile.content, bypassFile.content.size)
                },
                onBytesWritten = { delta ->
                    bytesWritten += delta
                    emitWriteProgress("[FAT32 ESP] ${bypassFile.path}", "Writing Windows bypass config to Partition 1 (FAT32)...")
                }
            )
        }
        fat32.finalizeFileSystem()
        val espWritten = fat32.getWrittenFiles()
        logger.success(TAG, "Partition 1 (FAT32 ESP) finalized (${espWritten.size} files).")

        // Phase B: Populate Data Partition with full Windows installer (including >4 GiB install.wim)
        logger.info(TAG, "Populating Partition 2 (${adapter.fsLabel}) with ${dataFiles.size + bypassFiles.size} Windows installer files...")
        for (dir in allDirs) {
            adapter.ensureDirectory(dir.path)
        }
        for (file in dataFiles) {
            checkState(isCancelled, isDisconnected)
            adapter.writeFile(
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
                    emitWriteProgress("[${adapter.fsTag}] ${file.path}", "Writing Windows installer files to Partition 2 (${adapter.fsTag})...")
                }
            )
        }
        for (bypassFile in bypassFiles) {
            checkState(isCancelled, isDisconnected)
            adapter.writeFile(
                normalizedPath = bypassFile.path,
                sizeBytes = bypassFile.content.size.toLong(),
                streamer = { consumer ->
                    checkSyncState(isCancelled, isDisconnected)
                    consumer(bypassFile.content, bypassFile.content.size)
                },
                onBytesWritten = { delta ->
                    bytesWritten += delta
                    emitWriteProgress("[${adapter.fsTag}] ${bypassFile.path}", "Writing Windows bypass config to Partition 2 (${adapter.fsTag})...")
                }
            )
        }

        checkState(isCancelled, isDisconnected)
        onProgress(
            WriteProgress(
                phase = WritePhase.FLUSHING_CACHE,
                bytesProcessed = bytesWritten,
                bytesWritten = bytesWritten,
                totalBytes = totalPayloadBytes,
                phaseProgress = 1f,
                overallProgress = if (verifyAfterWrite) 0.71f else 0.98f,
                currentItemLabel = adapter.flushLabel,
                statusMessage = adapter.flushStatus,
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )
        adapter.finalizeFileSystem()
        device.syncCache()
        val dataWritten = adapter.getWrittenFiles()
        logger.success(TAG, "Partition 2 (${adapter.fsLabel}) finalized (${dataWritten.size} files). Cache flushed.")

        if (!verifyAfterWrite) {
            logger.warn(TAG, "Post-write SHA-256 verification skipped per user setting.")
            return VerificationReport(
                verified = true,
                methodDescription = "Post-write verification disabled in Settings",
                bytesVerified = bytesWritten,
                filesVerified = espWritten.size + dataWritten.size
            )
        }

        // Phase C: Full SCSI read-back verification of GPT headers, BOTH boot sectors, and all files!
        logger.info(TAG, "Verifying GPT headers, FAT32 ESP + ${adapter.fsTag} boot sectors, and all written files...")
        val secBuf = ByteArray(device.blockSize)
        device.readBlocks(1L, 1, secBuf, 0)
        val primarySig = String(secBuf, 0, 8, Charsets.US_ASCII)
        device.readBlocks(device.totalBlocks - 1L, 1, secBuf, 0)
        val backupSig = String(secBuf, 0, 8, Charsets.US_ASCII)
        if (primarySig != "EFI PART" || backupSig != "EFI PART") {
            return VerificationReport(
                verified = false,
                methodDescription = "${adapter.methodSummary} SHA-256 read-back verification",
                bytesVerified = 0L,
                mismatchDetail = "GPT header signature mismatch (primary='$primarySig', backup='$backupSig')."
            )
        }

        device.readBlocks(espSpec.startLba, 1, secBuf, 0)
        val espFsLabel = String(secBuf, 82, 8, Charsets.US_ASCII)
        if (espFsLabel != "FAT32   ") {
            return VerificationReport(
                verified = false,
                methodDescription = "${adapter.methodSummary} SHA-256 read-back verification",
                bytesVerified = 0L,
                mismatchDetail = "FAT32 ESP boot sector signature mismatch (got '$espFsLabel')."
            )
        }

        device.readBlocks(dataSpec.startLba, 1, secBuf, 0)
        val dataOem = String(secBuf, 3, 8, Charsets.US_ASCII)
        if (dataOem != adapter.expectedOemSignature) {
            return VerificationReport(
                verified = false,
                methodDescription = "${adapter.methodSummary} SHA-256 read-back verification",
                bytesVerified = 0L,
                mismatchDetail = "${adapter.fsTag} boot sector signature mismatch (got '$dataOem')."
            )
        }

        var bytesVerified = 0L
        var filesVerified = 0
        speedTracker.reset()
        lastEmitNanos = System.nanoTime() - PROGRESS_INTERVAL_NANOS

        onProgress(
            WriteProgress(
                phase = WritePhase.VERIFYING,
                bytesProcessed = 0L,
                bytesWritten = bytesWritten,
                totalBytes = totalPayloadBytes,
                phaseProgress = 0f,
                overallProgress = 0.72f,
                currentItemLabel = "Verifying FAT32 ESP & ${adapter.fsTag} files",
                statusMessage = "Reading back written files for SHA-256 verification...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )

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
                        bytesWritten = bytesWritten,
                        totalBytes = totalPayloadBytes,
                        phaseProgress = frac,
                        overallProgress = 0.72f + frac * 0.27f,
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

        for (rec in espWritten) {
            checkState(isCancelled, isDisconnected)
            val ok = fat32.verifyFile(rec) { delta ->
                bytesVerified += delta
                emitVerify("[Verify FAT32] ${rec.path}")
            }
            if (!ok) {
                logger.error(TAG, "SHA-256 verification failed on FAT32 ESP file: ${rec.path}")
                return VerificationReport(
                    verified = false,
                    methodDescription = "${adapter.methodSummary} SHA-256 read-back verification",
                    bytesVerified = bytesVerified,
                    filesVerified = filesVerified,
                    mismatchDetail = "SHA-256 mismatch on FAT32 boot file '${rec.path}'."
                )
            }
            filesVerified++
        }

        for (rec in dataWritten) {
            checkState(isCancelled, isDisconnected)
            val ok = adapter.verifyFile(rec) { delta ->
                bytesVerified += delta
                emitVerify("[Verify ${adapter.fsTag}] ${rec.path}")
            }
            if (!ok) {
                logger.error(TAG, "SHA-256 verification failed on ${adapter.fsTag} file: ${rec.path}")
                return VerificationReport(
                    verified = false,
                    methodDescription = "${adapter.methodSummary} SHA-256 read-back verification",
                    bytesVerified = bytesVerified,
                    filesVerified = filesVerified,
                    mismatchDetail = "SHA-256 mismatch on ${adapter.fsTag} installer file '${rec.path}'."
                )
            }
            filesVerified++
        }

        logger.success(
            TAG,
            "GPT FAT32+${adapter.fsTag} verification PASSED: $filesVerified files (${ByteUtils.formatBytes(bytesVerified)}) verified!"
        )
        return VerificationReport(
            verified = true,
            methodDescription = "${adapter.methodSummary} full SCSI read-back SHA-256 verification",
            bytesVerified = bytesVerified,
            filesVerified = filesVerified
        )
    }

    private suspend fun verifyFat32Files(
        device: BlockDevice,
        partStartLba: Long,
        fat32: Fat32FileSystem,
        records: List<WrittenFileAllocation>,
        bytesWritten: Long,
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
        var lastEmitNanos = System.nanoTime() - PROGRESS_INTERVAL_NANOS

        onProgress(
            WriteProgress(
                phase = WritePhase.VERIFYING,
                bytesProcessed = 0L,
                bytesWritten = bytesWritten,
                totalBytes = totalPayloadBytes,
                phaseProgress = 0f,
                overallProgress = 0.72f,
                currentItemLabel = "Verifying FAT32 files",
                statusMessage = "Reading back written FAT32 files for SHA-256 verification...",
                targetDeviceName = targetDeviceName,
                sourceIsoName = isoInfo.displayName,
                bootSupport = isoInfo.bootSupport
            )
        )

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
                            bytesWritten = bytesWritten,
                            totalBytes = totalPayloadBytes,
                            phaseProgress = frac,
                            overallProgress = 0.72f + frac * 0.27f,
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
