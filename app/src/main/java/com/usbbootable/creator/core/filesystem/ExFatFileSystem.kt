package com.usbbootable.creator.core.filesystem

import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.util.ByteUtils
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Pure-Kotlin implementation of the Microsoft exFAT File System Specification operating directly
 * over a [BlockDevice].
 *
 * Designed specifically for Windows 10/11 UEFI installation media where `sources/install.wim`
 * exceeds FAT32's 4 GiB single-file ceiling. Supports full 64-bit file sizes, compliant Main +
 * Backup Boot Regions with boot checksum sector, Allocation Bitmap, compressed Up-Case Table,
 * FAT chains, NameHash, EntrySet checksums, and full SHA-256 read-back verification.
 */
class ExFatFileSystem(
    private val device: BlockDevice,
    private val partitionOffsetLba: Long,
    volumeLabel: String = "WIN_INSTALL",
    ioBufferBytes: Int = 256 * 1024,
    private val isCancelled: () -> Boolean = { false }
) {
    val blockSize: Int = device.blockSize
    val bytesPerSectorShift: Int = Integer.numberOfTrailingZeros(blockSize)
    val volumeLengthSectors: Long = device.totalBlocks
    val sectorsPerClusterShift: Int
    val sectorsPerCluster: Int
    val clusterSizeBytes: Int

    val fatOffsetSectors: Long
    val fatLengthSectors: Long
    val clusterHeapOffsetSectors: Long
    val clusterCount: Long

    val volumeSerialNumber: Int = SecureRandom().nextInt()
    val cleanVolumeLabel: String = volumeLabel.trim().take(11).ifEmpty { "WIN_INSTALL" }

    private val upCaseMap = CharArray(65536) { idx -> idx.toChar().uppercaseChar() }
    private val upCaseTableBytes: ByteArray
    private val upCaseTableChecksum: Int

    private val bitmapLengthBytes: Long
    private val bitmapClusters: Long
    private val upCaseFirstCluster: Long
    private val upCaseClusters: Long

    private var nextFreeCluster: Long
    private var rootFirstCluster: Long = 0L

    private data class ClusterRun(
        val startCluster: Long,
        val count: Long
    )

    private val allocatedRuns = mutableListOf<ClusterRun>()

    private class DirNode(
        val name: String,
        var firstCluster: Long = 0L,
        var byteSize: Long = 0L,
        val childrenDirs: MutableMap<String, DirNode> = LinkedHashMap(),
        val childrenFiles: MutableList<FileNode> = mutableListOf()
    )

    private data class FileNode(
        val name: String,
        val sizeBytes: Long,
        val firstCluster: Long
    )

    private val rootDir = DirNode(name = "")
    private val writtenFiles = mutableListOf<WrittenFileAllocation>()

    // Reusable I/O buffer & digest eliminate per-file heap allocations
    private val batchClusters: Int
    private val batchBytes: Int
    private val reusableIoBuffer: ByteArray
    private val sha256Digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    init {
        require(blockSize >= 512 && (blockSize and (blockSize - 1)) == 0) {
            "Invalid block size: $blockSize"
        }

        val volumeBytes = volumeLengthSectors * blockSize.toLong()
        val targetClusterBytes = when {
            volumeBytes <= 256L * 1024L * 1024L -> 4096
            volumeBytes <= 32L * 1024L * 1024L * 1024L -> 32 * 1024
            else -> 128 * 1024
        }
        val spc = (targetClusterBytes / blockSize).coerceAtLeast(1)
        sectorsPerCluster = spc
        sectorsPerClusterShift = Integer.numberOfTrailingZeros(spc)
        clusterSizeBytes = spc * blockSize

        // Main Boot Region = sectors 0..11, Backup Boot Region = sectors 12..23
        fatOffsetSectors = 24L
        val entriesPerFatSector = (blockSize / 4).toLong()
        // Iterative convergence for FatLength & ClusterHeapOffset
        var estClusters = (volumeLengthSectors - 24L) / spc.toLong()
        var estFatSec = ((estClusters + 2L) + entriesPerFatSector - 1L) / entriesPerFatSector
        var heapOffset = alignUp(fatOffsetSectors + estFatSec, spc.toLong())
        for (iter in 0 until 4) {
            if (heapOffset >= volumeLengthSectors) {
                throw IOException("Partition too small to format as exFAT.")
            }
            estClusters = (volumeLengthSectors - heapOffset) / spc.toLong()
            estFatSec = ((estClusters + 2L) + entriesPerFatSector - 1L) / entriesPerFatSector
            heapOffset = alignUp(fatOffsetSectors + estFatSec, spc.toLong())
        }
        if (estClusters < 16L) {
            throw IOException("Partition too small for exFAT ($estClusters clusters).")
        }

        fatLengthSectors = estFatSec
        clusterHeapOffsetSectors = heapOffset
        clusterCount = (volumeLengthSectors - heapOffset) / spc.toLong()

        // Pre-build compressed Up-Case Table & checksum
        upCaseTableBytes = buildCompressedUpCaseTable(upCaseMap)
        upCaseTableChecksum = computeRotating32Checksum(upCaseTableBytes)

        // Reserve Cluster 2.. for Allocation Bitmap and immediately following clusters for Up-Case Table
        bitmapLengthBytes = (clusterCount + 7L) / 8L
        bitmapClusters = ((bitmapLengthBytes + clusterSizeBytes - 1L) / clusterSizeBytes).coerceAtLeast(1L)
        upCaseClusters = ((upCaseTableBytes.size.toLong() + clusterSizeBytes - 1L) / clusterSizeBytes).coerceAtLeast(1L)

        nextFreeCluster = 2L
        val bmpStart = allocateContiguousClusters(bitmapClusters)
        require(bmpStart == 2L)
        upCaseFirstCluster = allocateContiguousClusters(upCaseClusters)

        val clampedTarget = ioBufferBytes.coerceIn(64 * 1024, 512 * 1024)
        batchClusters = (clampedTarget / clusterSizeBytes).coerceAtLeast(1)
        batchBytes = batchClusters * clusterSizeBytes
        reusableIoBuffer = ByteArray(batchBytes)
    }

    fun getWrittenFiles(): List<WrittenFileAllocation> = writtenFiles.toList()

    fun ensureDirectory(normalizedPath: String) {
        val parts = FileSystemIoHelpers.splitAndValidatePath(normalizedPath)
        var cur = rootDir
        for (part in parts) {
            val key = part.lowercase(Locale.ROOT)
            FileSystemIoHelpers.ensureNoCaseCollision(
                parentPathHint = normalizedPath,
                newName = part,
                existingDirKeys = emptySet(),
                existingFileNames = cur.childrenFiles.map { it.name },
                expectDirectory = true
            )
            cur = cur.childrenDirs.getOrPut(key) { DirNode(name = part) }
        }
    }

    /**
     * Streams a file of arbitrary 64-bit size (including > 4 GiB `sources/install.wim`) directly
     * into contiguous exFAT clusters while calculating its SHA-256 digest.
     */
    @Throws(IOException::class)
    fun writeFile(
        normalizedPath: String,
        sizeBytes: Long,
        streamer: (onChunk: (ByteArray, Int) -> Unit) -> Unit,
        onBytesWritten: (Long) -> Unit = {}
    ): WrittenFileAllocation {
        require(sizeBytes >= 0L) { "Negative file size: $sizeBytes" }

        val parts = FileSystemIoHelpers.splitAndValidatePath(normalizedPath)
        var parent = rootDir
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val key = part.lowercase(Locale.ROOT)
            FileSystemIoHelpers.ensureNoCaseCollision(
                parentPathHint = normalizedPath,
                newName = part,
                existingDirKeys = emptySet(),
                existingFileNames = parent.childrenFiles.map { it.name },
                expectDirectory = true
            )
            parent = parent.childrenDirs.getOrPut(key) { DirNode(name = part) }
        }
        val fileName = parts.last()
        FileSystemIoHelpers.ensureNoCaseCollision(
            parentPathHint = normalizedPath,
            newName = fileName,
            existingDirKeys = parent.childrenDirs.keys,
            existingFileNames = parent.childrenFiles.map { it.name },
            expectDirectory = false
        )

        sha256Digest.reset()
        val neededClusters = if (sizeBytes == 0L) 0L else (sizeBytes + clusterSizeBytes - 1L) / clusterSizeBytes

        if (neededClusters == 0L) {
            streamer { _, _ -> }
            parent.childrenFiles.add(FileNode(name = fileName, sizeBytes = 0L, firstCluster = 0L))
            val rec = WrittenFileAllocation(
                path = normalizedPath,
                sizeBytes = 0L,
                firstCluster = 0L,
                clusterCount = 0L,
                sha256Hex = ByteUtils.toHex(sha256Digest.digest())
            )
            writtenFiles.add(rec)
            return rec
        }

        val firstCluster = allocateContiguousClusters(neededClusters)
        val shaHex = FileSystemIoHelpers.streamToContiguousClusters(
            device = device,
            normalizedPath = normalizedPath,
            fsLabel = "exFAT",
            startLba = clusterToLba(firstCluster),
            expectedSizeBytes = sizeBytes,
            blockSize = blockSize,
            clusterSizeBytes = clusterSizeBytes,
            batchBytes = batchBytes,
            writeBuf = reusableIoBuffer,
            digest = sha256Digest,
            isCancelled = isCancelled,
            streamer = streamer,
            onBytesWritten = onBytesWritten
        )

        parent.childrenFiles.add(FileNode(name = fileName, sizeBytes = sizeBytes, firstCluster = firstCluster))
        val rec = WrittenFileAllocation(
            path = normalizedPath,
            sizeBytes = sizeBytes,
            firstCluster = firstCluster,
            clusterCount = neededClusters,
            sha256Hex = shaHex
        )
        writtenFiles.add(rec)
        return rec
    }

    /**
     * Writes all directory streams, Up-Case Table, Allocation Bitmap, FAT Table, and both
     * Main and Backup Boot Regions (with valid Boot Checksum sectors).
     */
    @Throws(IOException::class)
    fun finalizeFileSystem() {
        // 1. Collect all directories (BFS) and allocate contiguous clusters for each
        val allDirs = mutableListOf<DirNode>()
        val queue = ArrayDeque<DirNode>()
        queue.add(rootDir)
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            allDirs.add(d)
            queue.addAll(d.childrenDirs.values)
        }

        for (dir in allDirs) {
            val bytesNeeded = estimateDirectoryBytes(dir)
            val clusters = ((bytesNeeded + clusterSizeBytes - 1L) / clusterSizeBytes).coerceAtLeast(1L)
            dir.firstCluster = allocateContiguousClusters(clusters)
            dir.byteSize = clusters * clusterSizeBytes.toLong()
        }
        rootFirstCluster = rootDir.firstCluster

        // 2. Write every directory's serialized entries now that all child firstClusters are known
        for (dir in allDirs) {
            val raw = serializeDirectory(dir)
            val padded = ByteArray(dir.byteSize.toInt())
            System.arraycopy(raw, 0, padded, 0, raw.size)
            val sectors = padded.size / blockSize
            device.writeBlocks(clusterToLba(dir.firstCluster), sectors, padded, 0)
        }

        // 3. Write Up-Case Table at upCaseFirstCluster
        val upCasePadded = ByteArray((upCaseClusters * clusterSizeBytes).toInt())
        System.arraycopy(upCaseTableBytes, 0, upCasePadded, 0, upCaseTableBytes.size)
        device.writeBlocks(
            clusterToLba(upCaseFirstCluster),
            upCasePadded.size / blockSize,
            upCasePadded,
            0
        )

        // 4. Write Allocation Bitmap at Cluster 2 (streamed in chunks for zero huge-RAM spikes)
        writeAllocationBitmap()

        // 5. Write FAT table sequentially
        writeFatTable()

        // 6. Write Main Boot Region (sectors 0..11) and Backup Boot Region (sectors 12..23)
        val bootRegion = buildBootRegion()
        device.writeBlocks(0L, 12, bootRegion, 0)
        device.writeBlocks(12L, 12, bootRegion, 0)
    }

    /**
     * Reads back a previously written exFAT file directly from [device] and verifies its SHA-256.
     */
    @Throws(IOException::class)
    fun verifyFile(
        record: WrittenFileAllocation,
        onBytesVerified: (Long) -> Unit = {}
    ): Boolean {
        return FileSystemIoHelpers.verifyFromContiguousClusters(
            device = device,
            fsLabel = "exFAT",
            startLba = if (record.sizeBytes > 0L) clusterToLba(record.firstCluster) else 0L,
            sizeBytes = record.sizeBytes,
            clusterCount = record.clusterCount,
            blockSize = blockSize,
            clusterSizeBytes = clusterSizeBytes,
            sectorsPerCluster = sectorsPerCluster,
            batchClusters = batchClusters,
            readBuf = reusableIoBuffer,
            digest = sha256Digest,
            expectedSha256Hex = record.sha256Hex,
            isCancelled = isCancelled,
            onBytesVerified = onBytesVerified
        )
    }

    private fun allocateContiguousClusters(count: Long): Long {
        val start = nextFreeCluster
        val endExclusive = start + count
        if (endExclusive > clusterCount + 2L) {
            throw IOException(
                "exFAT partition out of space: requested $count clusters at $start (max=${clusterCount + 2L})."
            )
        }
        allocatedRuns.add(ClusterRun(start, count))
        nextFreeCluster = endExclusive
        return start
    }

    private fun clusterToLba(cluster: Long): Long {
        require(cluster >= 2L) { "Invalid exFAT cluster: $cluster" }
        return clusterHeapOffsetSectors + (cluster - 2L) * sectorsPerCluster.toLong()
    }

    private fun writeAllocationBitmap() {
        // Because all clusters [2 .. nextFreeCluster - 1] are allocated contiguously,
        // bits [0 .. allocatedCount - 1] are 1 and all remaining bits are 0!
        val allocatedCount = (nextFreeCluster - 2L).coerceAtLeast(0L)
        val totalBitmapRegionSectors = bitmapClusters * sectorsPerCluster.toLong()
        val chunkSectors = ((64 * 1024) / blockSize).coerceAtLeast(1)
        val buf = ByteArray(chunkSectors * blockSize)

        var writtenSectors = 0L
        var bitBase = 0L
        while (writtenSectors < totalBitmapRegionSectors) {
            if (isCancelled()) throw CancellationException("exFAT bitmap write cancelled.")
            val batchSec = minOf(chunkSectors.toLong(), totalBitmapRegionSectors - writtenSectors).toInt()
            val batchBytes = batchSec * blockSize
            buf.fill(0, 0, batchBytes)

            for (byteIdx in 0 until batchBytes) {
                val firstBit = bitBase + byteIdx.toLong() * 8L
                if (firstBit >= allocatedCount) break
                val remBits = allocatedCount - firstBit
                buf[byteIdx] = if (remBits >= 8L) {
                    0xFF.toByte()
                } else {
                    ((1 shl remBits.toInt()) - 1).toByte()
                }
            }

            device.writeBlocks(clusterToLba(2L) + writtenSectors, batchSec, buf, 0)
            writtenSectors += batchSec
            bitBase += batchBytes.toLong() * 8L
        }
    }

    private fun writeFatTable() {
        allocatedRuns.sortBy { it.startCluster }
        val entriesPerSector = blockSize / 4
        val chunkSectors = ((64 * 1024) / blockSize).coerceAtLeast(1)
        val buf = ByteArray(chunkSectors * blockSize)

        var runIdx = 0
        var fatSec = 0L
        while (fatSec < fatLengthSectors) {
            if (isCancelled()) throw CancellationException("exFAT FAT flush cancelled.")
            val batchSec = minOf(chunkSectors.toLong(), fatLengthSectors - fatSec).toInt()
            val batchBytes = batchSec * blockSize
            buf.fill(0, 0, batchBytes)

            val firstClusterInChunk = fatSec * entriesPerSector.toLong()
            val lastClusterInChunk = firstClusterInChunk + (batchSec * entriesPerSector).toLong() - 1L

            if (0L in firstClusterInChunk..lastClusterInChunk) {
                ByteUtils.writeI32LE(buf, 0, 0xFFFFFFF8.toInt())
            }
            if (1L in firstClusterInChunk..lastClusterInChunk) {
                ByteUtils.writeI32LE(buf, 4, 0xFFFFFFFF.toInt())
            }

            while (runIdx < allocatedRuns.size) {
                val r = allocatedRuns[runIdx]
                if (r.startCluster + r.count - 1L < firstClusterInChunk) runIdx++ else break
            }

            var i = runIdx
            while (i < allocatedRuns.size) {
                val r = allocatedRuns[i]
                if (r.startCluster > lastClusterInChunk) break

                val overlapStart = maxOf(r.startCluster, firstClusterInChunk)
                val overlapEnd = minOf(r.startCluster + r.count - 1L, lastClusterInChunk)
                val runLast = r.startCluster + r.count - 1L

                var c = overlapStart
                while (c <= overlapEnd) {
                    val value = if (c == runLast) 0xFFFFFFFF.toInt() else (c + 1L).toInt()
                    val off = ((c - firstClusterInChunk) * 4L).toInt()
                    ByteUtils.writeI32LE(buf, off, value)
                    c++
                }
                i++
            }

            device.writeBlocks(fatOffsetSectors + fatSec, batchSec, buf, 0)
            fatSec += batchSec
        }
    }

    private fun estimateDirectoryBytes(dir: DirNode): Long {
        var entryCount = 0L
        if (dir === rootDir) {
            entryCount += 3L // Volume Label + Allocation Bitmap + Up-Case Table
        }
        for (sub in dir.childrenDirs.values) {
            entryCount += 2L + ((sub.name.length + 14) / 15).toLong()
        }
        for (file in dir.childrenFiles) {
            entryCount += 2L + ((file.name.length + 14) / 15).toLong()
        }
        return (entryCount + 1L) * 32L
    }

    private fun serializeDirectory(dir: DirNode): ByteArray {
        val entries = mutableListOf<ByteArray>()

        if (dir === rootDir) {
            entries.add(buildVolumeLabelEntry(cleanVolumeLabel))
            entries.add(buildAllocationBitmapEntry(2L, bitmapLengthBytes))
            entries.add(buildUpCaseTableEntry(upCaseFirstCluster, upCaseTableBytes.size.toLong(), upCaseTableChecksum))
        }

        for (sub in dir.childrenDirs.values) {
            entries.addAll(
                buildFileEntrySet(
                    name = sub.name,
                    isDirectory = true,
                    firstCluster = sub.firstCluster,
                    dataLength = sub.byteSize
                )
            )
        }

        for (file in dir.childrenFiles) {
            entries.addAll(
                buildFileEntrySet(
                    name = file.name,
                    isDirectory = false,
                    firstCluster = file.firstCluster,
                    dataLength = file.sizeBytes
                )
            )
        }

        val out = ByteArray((entries.size + 1) * 32)
        for (idx in entries.indices) {
            System.arraycopy(entries[idx], 0, out, idx * 32, 32)
        }
        return out
    }

    private fun buildFileEntrySet(
        name: String,
        isDirectory: Boolean,
        firstCluster: Long,
        dataLength: Long
    ): List<ByteArray> {
        val trimmedName = name.take(255)
        val nameEntryCount = ((trimmedName.length + 14) / 15).coerceAtLeast(1)
        val secondaryCount = 1 + nameEntryCount
        val totalEntries = 1 + secondaryCount
        val setBytes = ByteArray(totalEntries * 32)

        // 1. File Directory Entry (0x85)
        setBytes[0] = 0x85.toByte()
        setBytes[1] = secondaryCount.toByte()
        // Bytes 2..3 = SetChecksum (computed after all secondary entries are filled)
        val attrs = if (isDirectory) 0x0010 else 0x0020
        ByteUtils.writeU16LE(setBytes, 4, attrs)
        ByteUtils.writeI32LE(setBytes, 8, DEFAULT_EXFAT_TIMESTAMP)
        ByteUtils.writeI32LE(setBytes, 12, DEFAULT_EXFAT_TIMESTAMP)
        ByteUtils.writeI32LE(setBytes, 16, DEFAULT_EXFAT_TIMESTAMP)

        // 2. Stream Extension Entry (0xC0)
        val sOff = 32
        setBytes[sOff] = 0xC0.toByte()
        // AllocationPossible = 1 (0x01), NoFatChain = 0 (we populate valid FAT chains for every file/dir)
        setBytes[sOff + 1] = 0x01
        setBytes[sOff + 3] = trimmedName.length.toByte()
        ByteUtils.writeU16LE(setBytes, sOff + 4, computeNameHash(trimmedName, upCaseMap))
        ByteUtils.writeI64LE(setBytes, sOff + 8, dataLength)  // ValidDataLength
        ByteUtils.writeI32LE(setBytes, sOff + 20, firstCluster.toInt())
        ByteUtils.writeI64LE(setBytes, sOff + 24, dataLength) // DataLength

        // 3. File Name Entries (0xC1)
        for (nIdx in 0 until nameEntryCount) {
            val nOff = (2 + nIdx) * 32
            setBytes[nOff] = 0xC1.toByte()
            setBytes[nOff + 1] = 0x00
            val charStart = nIdx * 15
            for (c in 0 until 15) {
                val pos = charStart + c
                val ch = if (pos < trimmedName.length) trimmedName[pos].code else 0
                ByteUtils.writeU16LE(setBytes, nOff + 2 + c * 2, ch)
            }
        }

        // Compute 16-bit Entry Set Checksum over entire set skipping bytes 2 and 3
        val setChecksum = computeEntrySetChecksum(setBytes)
        ByteUtils.writeU16LE(setBytes, 2, setChecksum)

        return List(totalEntries) { idx ->
            setBytes.copyOfRange(idx * 32, (idx + 1) * 32)
        }
    }

    private fun buildBootRegion(): ByteArray {
        val region = ByteArray(12 * blockSize)

        // Sector 0: Main Boot Sector
        region[0] = 0xEB.toByte()
        region[1] = 0x76
        region[2] = 0x90.toByte()
        ByteUtils.writeAscii(region, 3, "EXFAT   ", 8)
        // Bytes 11..63 are MustBeZero (already 0)
        ByteUtils.writeI64LE(region, 64, partitionOffsetLba)
        ByteUtils.writeI64LE(region, 72, volumeLengthSectors)
        ByteUtils.writeI32LE(region, 80, fatOffsetSectors.toInt())
        ByteUtils.writeI32LE(region, 84, fatLengthSectors.toInt())
        ByteUtils.writeI32LE(region, 88, clusterHeapOffsetSectors.toInt())
        ByteUtils.writeI32LE(region, 92, clusterCount.toInt())
        ByteUtils.writeI32LE(region, 96, rootFirstCluster.toInt())
        ByteUtils.writeI32LE(region, 100, volumeSerialNumber)
        ByteUtils.writeU16LE(region, 104, 0x0100) // Revision 1.00
        ByteUtils.writeU16LE(region, 106, 0x0000) // VolumeFlags
        region[108] = bytesPerSectorShift.toByte()
        region[109] = sectorsPerClusterShift.toByte()
        region[110] = 1                           // NumberOfFats = 1
        region[111] = 0x80.toByte()               // DriveSelect
        val usedClusters = (nextFreeCluster - 2L).coerceAtLeast(0L)
        val percentInUse = ((usedClusters * 100L) / clusterCount.coerceAtLeast(1L)).coerceIn(0L, 100L).toInt()
        region[112] = percentInUse.toByte()
        region[510] = 0x55
        region[511] = 0xAA.toByte()

        // Sectors 1..8: Extended Boot Sectors (trailing 0xAA550000 signature at end of each sector)
        for (sec in 1..8) {
            val sigOff = sec * blockSize + blockSize - 4
            ByteUtils.writeI32LE(region, sigOff, 0xAA550000.toInt())
        }

        // Sector 9: OEM Parameters (zeroed)
        // Sector 10: Reserved (zeroed)

        // Sector 11: Boot Checksum Sector (checksum of sectors 0..10 skipping bytes 106, 107, 112)
        val bootChecksum = computeBootChecksum(region, 11 * blockSize)
        val checksumSecStart = 11 * blockSize
        for (off in 0 until blockSize step 4) {
            ByteUtils.writeI32LE(region, checksumSecStart + off, bootChecksum)
        }

        return region
    }

    companion object {
        // 2026-01-01 12:00:00 in exFAT timestamp format
        private const val DEFAULT_EXFAT_TIMESTAMP =
            ((2026 - 1980) shl 25) or (1 shl 21) or (1 shl 16) or (12 shl 11)

        private fun alignUp(value: Long, alignment: Long): Long {
            return ((value + alignment - 1L) / alignment) * alignment
        }

        private fun buildVolumeLabelEntry(label: String): ByteArray {
            val e = ByteArray(32)
            e[0] = 0x83.toByte()
            val trimmed = label.take(11)
            e[1] = trimmed.length.toByte()
            for (i in trimmed.indices) {
                ByteUtils.writeU16LE(e, 2 + i * 2, trimmed[i].code)
            }
            return e
        }

        private fun buildAllocationBitmapEntry(firstCluster: Long, lengthBytes: Long): ByteArray {
            val e = ByteArray(32)
            e[0] = 0x81.toByte()
            e[1] = 0x00 // BitmapFlags = 0 (first FAT)
            ByteUtils.writeI32LE(e, 20, firstCluster.toInt())
            ByteUtils.writeI64LE(e, 24, lengthBytes)
            return e
        }

        private fun buildUpCaseTableEntry(firstCluster: Long, lengthBytes: Long, checksum: Int): ByteArray {
            val e = ByteArray(32)
            e[0] = 0x82.toByte()
            ByteUtils.writeI32LE(e, 4, checksum)
            ByteUtils.writeI32LE(e, 20, firstCluster.toInt())
            ByteUtils.writeI64LE(e, 24, lengthBytes)
            return e
        }

        /**
         * Builds a compliant compressed exFAT Up-Case Table using 0xFFFF run-length encoding
         * for identity ranges per Microsoft exFAT Spec §7.2.1.
         */
        fun buildCompressedUpCaseTable(upCaseMap: CharArray): ByteArray {
            val shorts = ArrayList<Int>(4096)
            var i = 0
            while (i < 65536) {
                if (upCaseMap[i].code == i) {
                    var run = 0
                    while (i + run < 65536 && upCaseMap[i + run].code == i + run && run < 65535) {
                        run++
                    }
                    if (run >= 3) {
                        shorts.add(0xFFFF)
                        shorts.add(run)
                        i += run
                    } else {
                        for (k in 0 until run) {
                            val cp = i + k
                            if (cp == 0xFFFF) {
                                shorts.add(0xFFFF)
                                shorts.add(1)
                            } else {
                                shorts.add(cp)
                            }
                        }
                        i += run
                    }
                } else {
                    shorts.add(upCaseMap[i].code)
                    i++
                }
            }
            val bytes = ByteArray(shorts.size * 2)
            for (idx in shorts.indices) {
                ByteUtils.writeU16LE(bytes, idx * 2, shorts[idx])
            }
            return bytes
        }

        fun computeBootChecksum(region: ByteArray, lengthBytes: Int): Int {
            var sum = 0L
            for (i in 0 until lengthBytes) {
                if (i == 106 || i == 107 || i == 112) continue
                val ror = ((sum and 1L) shl 31) or (sum ushr 1)
                sum = (ror + (region[i].toLong() and 0xFFL)) and 0xFFFFFFFFL
            }
            return sum.toInt()
        }

        fun computeRotating32Checksum(data: ByteArray): Int {
            var sum = 0L
            for (b in data) {
                val ror = ((sum and 1L) shl 31) or (sum ushr 1)
                sum = (ror + (b.toLong() and 0xFFL)) and 0xFFFFFFFFL
            }
            return sum.toInt()
        }

        fun computeEntrySetChecksum(entrySetBytes: ByteArray): Int {
            var sum = 0
            for (i in entrySetBytes.indices) {
                if (i == 2 || i == 3) continue
                val ror = ((sum and 1) shl 15) or (sum ushr 1)
                sum = (ror + (entrySetBytes[i].toInt() and 0xFF)) and 0xFFFF
            }
            return sum
        }

        fun computeNameHash(name: String, upCaseMap: CharArray): Int {
            var hash = 0
            for (ch in name) {
                val upper = upCaseMap[ch.code].code
                val lo = upper and 0xFF
                val hi = (upper ushr 8) and 0xFF
                hash = ((((hash and 1) shl 15) or (hash ushr 1)) + lo) and 0xFFFF
                hash = ((((hash and 1) shl 15) or (hash ushr 1)) + hi) and 0xFFFF
            }
            return hash
        }
    }
}
