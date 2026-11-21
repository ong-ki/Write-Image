package com.usbbootable.creator.core.filesystem

import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.util.ByteUtils
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CancellationException

data class WrittenFileAllocation(
    val path: String,
    val sizeBytes: Long,
    val firstCluster: Long,
    val clusterCount: Long,
    val sha256Hex: String
)

/**
 * Standards-compliant FAT32 formatter and streaming writer operating directly over a [BlockDevice]
 * (typically a [com.usbbootable.creator.core.usb.PartitionBlockDevice]).
 *
 * Streams file data contiguously straight to USB sectors with minimal RAM usage, tracks cluster
 * chains compactly, writes full VFAT Long File Names (LFN) + unique 8.3 Short File Names, keeps
 * FAT1 and FAT2 synchronized, and supports exact SHA-256 read-back verification.
 */
class Fat32FileSystem(
    private val device: BlockDevice,
    private val hiddenSectors: Long,
    volumeLabel: String = "WIN_BOOT",
    ioBufferBytes: Int = 256 * 1024,
    private val isCancelled: () -> Boolean = { false }
) {
    val blockSize: Int = device.blockSize
    val totalSectors: Long = device.totalBlocks
    val sectorsPerCluster: Int
    val clusterSizeBytes: Int
    val reservedSectors: Int = 32
    val numFats: Int = 2
    val fatSizeSectors: Long
    val dataStartSector: Long
    val totalDataClusters: Long
    val volumeId: Int = SecureRandom().nextInt()
    val cleanVolumeLabel: String = sanitizeVolumeLabel(volumeLabel)

    private var nextFreeCluster: Long = 3L // Cluster 2 is reserved for Root Directory first cluster
    private val fatLinks = HashMap<Long, Long>() // Non-contiguous or EOC cluster overrides
    private val allocatedRanges = mutableListOf<ClusterRun>() // Contiguous cluster runs for sequential FAT write

    private data class ClusterRun(
        val startCluster: Long,
        val count: Long,
        val endEntryValue: Long = FAT32_EOC
    )

    private class DirNode(
        val name: String,
        val parent: DirNode?,
        var firstCluster: Long = 0L,
        val childrenDirs: MutableMap<String, DirNode> = LinkedHashMap(),
        val childrenFiles: MutableList<FileNode> = mutableListOf()
    )

    private data class FileNode(
        val name: String,
        val sizeBytes: Long,
        val firstCluster: Long
    )

    private val rootDir = DirNode(name = "", parent = null, firstCluster = 2L)
    private val writtenFiles = mutableListOf<WrittenFileAllocation>()

    // Reusable I/O buffer & digest eliminate per-file heap allocations across hundreds of ISO files
    private val batchClusters: Int
    private val batchBytes: Int
    private val reusableIoBuffer: ByteArray
    private val sha256Digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    init {
        require(blockSize >= 512 && (blockSize and (blockSize - 1)) == 0) {
            "Invalid block size: $blockSize"
        }
        val partitionBytes = totalSectors * blockSize.toLong()
        var spc = chooseSectorsPerCluster(partitionBytes, blockSize)
        var computedFatSec: Long
        var computedDataClusters: Long

        while (true) {
            val entriesPerFatSector = (blockSize / 4).toLong()
            var fatSec = 1L
            var dataClusters = 0L
            for (iter in 0 until 8) {
                val dataSec = totalSectors - reservedSectors.toLong() - numFats.toLong() * fatSec
                if (dataSec <= 0L) {
                    dataClusters = 0L
                    break
                }
                dataClusters = dataSec / spc.toLong()
                val neededEntries = dataClusters + 2L
                val nextFatSec = (neededEntries + entriesPerFatSector - 1L) / entriesPerFatSector
                if (nextFatSec == fatSec) break
                fatSec = nextFatSec
            }
            if (dataClusters >= MIN_FAT32_CLUSTERS || spc == 1) {
                computedFatSec = fatSec
                computedDataClusters = dataClusters
                break
            }
            spc /= 2
        }

        if (computedDataClusters < MIN_FAT32_CLUSTERS) {
            throw IOException(
                "Partition too small for valid FAT32 ($computedDataClusters clusters; minimum $MIN_FAT32_CLUSTERS)."
            )
        }

        sectorsPerCluster = spc
        clusterSizeBytes = spc * blockSize
        fatSizeSectors = computedFatSec
        dataStartSector = reservedSectors.toLong() + numFats.toLong() * fatSizeSectors
        totalDataClusters = computedDataClusters

        val clampedTarget = ioBufferBytes.coerceIn(64 * 1024, 512 * 1024)
        batchClusters = (clampedTarget / clusterSizeBytes).coerceAtLeast(1)
        batchBytes = batchClusters * clusterSizeBytes
        reusableIoBuffer = ByteArray(batchBytes)
    }

    fun getWrittenFiles(): List<WrittenFileAllocation> = writtenFiles.toList()

    fun ensureDirectory(normalizedPath: String) {
        val parts = normalizedPath.split('/').filter { it.isNotEmpty() }
        var cur = rootDir
        for (part in parts) {
            val key = part.lowercase(Locale.ROOT)
            cur = cur.childrenDirs.getOrPut(key) { DirNode(name = part, parent = cur) }
        }
    }

    /**
     * Streams a single file directly to contiguous USB clusters while computing its SHA-256 hash.
     * Supports files up to 4 GiB - 1 byte without buffering the file in RAM.
     */
    @Throws(IOException::class)
    fun writeFile(
        normalizedPath: String,
        sizeBytes: Long,
        streamer: (onChunk: (ByteArray, Int) -> Unit) -> Unit,
        onBytesWritten: (Long) -> Unit = {}
    ): WrittenFileAllocation {
        if (sizeBytes < 0L || sizeBytes > MAX_FAT32_FILE_SIZE) {
            throw IOException("File '$normalizedPath' size ($sizeBytes B) exceeds FAT32 4 GiB limit.")
        }

        val parts = normalizedPath.split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) { "Empty file path" }
        var parent = rootDir
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val key = part.lowercase(Locale.ROOT)
            parent = parent.childrenDirs.getOrPut(key) { DirNode(name = part, parent = parent) }
        }
        val fileName = parts.last()

        sha256Digest.reset()
        val neededClusters = if (sizeBytes == 0L) 0L else (sizeBytes + clusterSizeBytes - 1L) / clusterSizeBytes

        if (neededClusters == 0L) {
            // Empty file uses firstCluster = 0 per FAT32 spec
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
        var currentLba = clusterToLba(firstCluster)

        val writeBuf = reusableIoBuffer
        var bufFill = 0
        var totalWritten = 0L

        streamer { data, len ->
            if (isCancelled()) throw CancellationException("FAT32 write cancelled.")
            if (len <= 0) return@streamer
            sha256Digest.update(data, 0, len)
            var srcPos = 0
            var rem = len
            while (rem > 0) {
                val space = batchBytes - bufFill
                val toCopy = minOf(rem, space)
                System.arraycopy(data, srcPos, writeBuf, bufFill, toCopy)
                bufFill += toCopy
                srcPos += toCopy
                rem -= toCopy

                if (bufFill == batchBytes) {
                    if (isCancelled()) throw CancellationException("FAT32 write cancelled.")
                    val sectorCount = batchBytes / blockSize
                    device.writeBlocks(currentLba, sectorCount, writeBuf, 0)
                    currentLba += sectorCount
                    totalWritten += batchBytes
                    onBytesWritten(batchBytes.toLong())
                    bufFill = 0
                }
            }
        }

        if (bufFill > 0) {
            if (isCancelled()) throw CancellationException("FAT32 write cancelled.")
            // Pad remainder of final cluster with zeros
            val paddedClusters = (bufFill + clusterSizeBytes - 1) / clusterSizeBytes
            val paddedBytes = paddedClusters * clusterSizeBytes
            writeBuf.fill(0, bufFill, paddedBytes)
            val sectorCount = paddedBytes / blockSize
            device.writeBlocks(currentLba, sectorCount, writeBuf, 0)
            totalWritten += bufFill
            onBytesWritten(bufFill.toLong())
        }

        if (totalWritten != sizeBytes) {
            throw IOException(
                "FAT32 stream size mismatch for '$normalizedPath': expected=$sizeBytes, got=$totalWritten"
            )
        }

        parent.childrenFiles.add(FileNode(name = fileName, sizeBytes = sizeBytes, firstCluster = firstCluster))
        val rec = WrittenFileAllocation(
            path = normalizedPath,
            sizeBytes = sizeBytes,
            firstCluster = firstCluster,
            clusterCount = neededClusters,
            sha256Hex = ByteUtils.toHex(sha256Digest.digest())
        )
        writtenFiles.add(rec)
        return rec
    }

    /**
     * Writes all directory clusters, synchronizes FAT1 and FAT2, writes FSInfo (primary + backup),
     * and writes the FAT32 Boot Sector (primary + backup).
     */
    @Throws(IOException::class)
    fun finalizeFileSystem() {
        // 1. Collect all directories (BFS) and serialize directory entry tables
        val allDirs = mutableListOf<DirNode>()
        val queue = ArrayDeque<DirNode>()
        queue.add(rootDir)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            allDirs.add(dir)
            queue.addAll(dir.childrenDirs.values)
        }

        // First pass: calculate exact byte size & allocate clusters for each directory
        val dirEntrySizes = HashMap<DirNode, Int>()
        val dirExtraClusters = HashMap<DirNode, Pair<Long, Long>>() // For root dir if > 1 cluster
        for (dir in allDirs) {
            val serializedSize = estimateDirectoryBytes(dir)
            dirEntrySizes[dir] = serializedSize
            val clusterCount = ((serializedSize + clusterSizeBytes - 1) / clusterSizeBytes).coerceAtLeast(1)
            if (dir === rootDir) {
                dir.firstCluster = 2L
                if (clusterCount > 1) {
                    val extraStart = allocateContiguousClusters((clusterCount - 1).toLong())
                    // Link cluster 2 -> extraStart
                    fatLinks[2L] = extraStart
                    dirExtraClusters[dir] = Pair(extraStart, (clusterCount - 1).toLong())
                } else {
                    fatLinks[2L] = FAT32_EOC
                }
            } else {
                dir.firstCluster = allocateContiguousClusters(clusterCount.toLong())
            }
        }

        // Second pass: now that every DirNode has its final firstCluster, write directory clusters!
        for (dir in allDirs) {
            val rawBytes = serializeDirectory(dir)
            val totalDirClusters = ((rawBytes.size + clusterSizeBytes - 1) / clusterSizeBytes).coerceAtLeast(1)
            val padded = ByteArray(totalDirClusters * clusterSizeBytes)
            System.arraycopy(rawBytes, 0, padded, 0, rawBytes.size)

            if (dir === rootDir) {
                // Write first cluster at cluster 2
                device.writeBlocks(clusterToLba(2L), sectorsPerCluster, padded, 0)
                val extra = dirExtraClusters[dir]
                if (extra != null) {
                    val (extraStart, extraCount) = extra
                    device.writeBlocks(
                        clusterToLba(extraStart),
                        (extraCount * sectorsPerCluster).toInt(),
                        padded,
                        clusterSizeBytes
                    )
                }
            } else {
                device.writeBlocks(
                    clusterToLba(dir.firstCluster),
                    totalDirClusters * sectorsPerCluster,
                    padded,
                    0
                )
            }
        }

        // 2. Write FAT1 and FAT2 sequentially in 64 KiB chunks (with unused tail cleanly zeroed)
        writeFatTables()

        // 3. Zero reserved sectors (0..31), then write Boot Sector (0 & 6) and FSInfo (1 & 7)
        val reservedZero = ByteArray(reservedSectors * blockSize)
        device.writeBlocks(0L, reservedSectors, reservedZero)

        val bootSector = buildBootSector()
        val fsInfo = buildFsInfoSector()

        device.writeBlocks(0L, 1, bootSector)
        device.writeBlocks(1L, 1, fsInfo)
        device.writeBlocks(6L, 1, bootSector) // Backup Boot Sector
        device.writeBlocks(7L, 1, fsInfo)     // Backup FSInfo Sector
    }

    /**
     * Reads back a previously written file directly from the USB [BlockDevice] and verifies
     * its SHA-256 digest matches the digest computed during writing.
     */
    @Throws(IOException::class)
    fun verifyFile(
        record: WrittenFileAllocation,
        onBytesVerified: (Long) -> Unit = {}
    ): Boolean {
        sha256Digest.reset()
        if (record.sizeBytes == 0L) {
            return ByteUtils.toHex(sha256Digest.digest()) == record.sha256Hex
        }

        val readBuf = reusableIoBuffer
        var currentLba = clusterToLba(record.firstCluster)
        var remainingBytes = record.sizeBytes
        var remainingClusters = record.clusterCount

        while (remainingClusters > 0L) {
            if (isCancelled()) throw CancellationException("FAT32 verification cancelled.")
            val clustersToRead = minOf(remainingClusters, batchClusters.toLong()).toInt()
            val sectorsToRead = clustersToRead * sectorsPerCluster
            device.readBlocks(currentLba, sectorsToRead, readBuf, 0)

            val validBytes = minOf(remainingBytes, (clustersToRead * clusterSizeBytes).toLong()).toInt()
            sha256Digest.update(readBuf, 0, validBytes)
            onBytesVerified(validBytes.toLong())

            currentLba += sectorsToRead
            remainingClusters -= clustersToRead
            remainingBytes -= validBytes
        }

        val actualHash = ByteUtils.toHex(sha256Digest.digest())
        return actualHash == record.sha256Hex
    }

    private fun allocateContiguousClusters(count: Long): Long {
        val start = nextFreeCluster
        val endExclusive = start + count
        if (endExclusive > totalDataClusters + 2L) {
            throw IOException(
                "FAT32 partition out of space: requested $count clusters at $start (max=${totalDataClusters + 2L})."
            )
        }
        allocatedRanges.add(ClusterRun(startCluster = start, count = count))
        nextFreeCluster = endExclusive
        return start
    }

    private fun clusterToLba(cluster: Long): Long {
        require(cluster >= 2L) { "Invalid FAT32 cluster number: $cluster" }
        return dataStartSector + (cluster - 2L) * sectorsPerCluster.toLong()
    }

    private fun writeFatTables() {
        // Build sorted lookup of allocated cluster runs for O(1) streaming FAT generation
        allocatedRanges.sortBy { it.startCluster }
        val entriesPerSector = blockSize / 4
        val sectorsPerChunk = ((64 * 1024) / blockSize).coerceAtLeast(1)
        val chunkBuf = ByteArray(sectorsPerChunk * blockSize)

        val fat1Start = reservedSectors.toLong()
        val fat2Start = fat1Start + fatSizeSectors

        var runIdx = 0
        var fatSector = 0L
        while (fatSector < fatSizeSectors) {
            if (isCancelled()) throw CancellationException("FAT32 metadata finalize cancelled.")
            val batchSectors = minOf(sectorsPerChunk.toLong(), fatSizeSectors - fatSector).toInt()
            val batchBytes = batchSectors * blockSize
            chunkBuf.fill(0, 0, batchBytes)

            val firstClusterInChunk = fatSector * entriesPerSector.toLong()
            val lastClusterInChunk = firstClusterInChunk + (batchSectors * entriesPerSector).toLong() - 1L

            // Special reserved clusters 0, 1, and root cluster 2
            if (0L in firstClusterInChunk..lastClusterInChunk) {
                ByteUtils.writeI32LE(chunkBuf, 0, 0x0FFFFFF8)
            }
            if (1L in firstClusterInChunk..lastClusterInChunk) {
                ByteUtils.writeI32LE(chunkBuf, 4, 0x0FFFFFFF)
            }
            if (2L in firstClusterInChunk..lastClusterInChunk) {
                val rootNext = fatLinks[2L] ?: FAT32_EOC
                ByteUtils.writeI32LE(chunkBuf, 8, rootNext.toInt())
            }

            // Advance runIdx past completed runs
            while (runIdx < allocatedRanges.size) {
                val r = allocatedRanges[runIdx]
                if (r.startCluster + r.count - 1L < firstClusterInChunk) {
                    runIdx++
                } else {
                    break
                }
            }

            // Fill overlapping cluster runs into this FAT chunk
            var i = runIdx
            while (i < allocatedRanges.size) {
                val r = allocatedRanges[i]
                if (r.startCluster > lastClusterInChunk) break

                val overlapStart = maxOf(r.startCluster, firstClusterInChunk)
                val overlapEnd = minOf(r.startCluster + r.count - 1L, lastClusterInChunk)
                val runLast = r.startCluster + r.count - 1L

                var c = overlapStart
                while (c <= overlapEnd) {
                    val value = if (c == runLast) {
                        fatLinks[c] ?: r.endEntryValue
                    } else {
                        c + 1L
                    }
                    val byteOff = ((c - firstClusterInChunk) * 4L).toInt()
                    ByteUtils.writeI32LE(chunkBuf, byteOff, value.toInt())
                    c++
                }
                i++
            }

            // Write identical FAT chunk to both FAT1 and FAT2
            device.writeBlocks(fat1Start + fatSector, batchSectors, chunkBuf, 0)
            device.writeBlocks(fat2Start + fatSector, batchSectors, chunkBuf, 0)
            fatSector += batchSectors
        }
    }

    private fun estimateDirectoryBytes(dir: DirNode): Int {
        var entriesCount = 0
        if (dir === rootDir) {
            entriesCount += 1 // Volume label entry
        } else {
            entriesCount += 2 // "." and ".."
        }
        for (childDir in dir.childrenDirs.values) {
            entriesCount += 1 + lfnEntryCountFor(childDir.name)
        }
        for (childFile in dir.childrenFiles) {
            entriesCount += 1 + lfnEntryCountFor(childFile.name)
        }
        return (entriesCount + 1) * 32 // +1 guarantees terminating 0x00 entry
    }

    private fun serializeDirectory(dir: DirNode): ByteArray {
        val entries = mutableListOf<ByteArray>()
        val usedShortNames = HashSet<String>()

        if (dir === rootDir) {
            entries.add(buildVolumeLabelEntry(cleanVolumeLabel))
        } else {
            entries.add(buildDotEntry(".", dir.firstCluster))
            val parentCluster = if (dir.parent === rootDir) 0L else (dir.parent?.firstCluster ?: 0L)
            entries.add(buildDotEntry("..", parentCluster))
        }

        for (childDir in dir.childrenDirs.values) {
            val (sfn11, needsLfn) = makeShortName(childDir.name, usedShortNames)
            if (needsLfn) {
                entries.addAll(buildLfnEntries(childDir.name, lfnChecksum(sfn11)))
            }
            entries.add(buildShortEntry(sfn11, ATTR_DIRECTORY, childDir.firstCluster, 0L))
        }

        for (childFile in dir.childrenFiles) {
            val (sfn11, needsLfn) = makeShortName(childFile.name, usedShortNames)
            if (needsLfn) {
                entries.addAll(buildLfnEntries(childFile.name, lfnChecksum(sfn11)))
            }
            entries.add(buildShortEntry(sfn11, ATTR_ARCHIVE, childFile.firstCluster, childFile.sizeBytes))
        }

        val out = ByteArray((entries.size + 1) * 32)
        for (idx in entries.indices) {
            System.arraycopy(entries[idx], 0, out, idx * 32, 32)
        }
        return out
    }

    private fun buildBootSector(): ByteArray {
        val bpb = ByteArray(blockSize)
        bpb[0] = 0xEB.toByte()
        bpb[1] = 0x58
        bpb[2] = 0x90.toByte()
        ByteUtils.writeAscii(bpb, 3, "MSWIN4.1", 8)
        ByteUtils.writeU16LE(bpb, 11, blockSize)
        bpb[13] = sectorsPerCluster.toByte()
        ByteUtils.writeU16LE(bpb, 14, reservedSectors)
        bpb[16] = numFats.toByte()
        ByteUtils.writeU16LE(bpb, 17, 0) // RootEntCnt = 0 for FAT32
        ByteUtils.writeU16LE(bpb, 19, 0) // TotSec16 = 0
        bpb[21] = 0xF8.toByte()          // Fixed/removable non-floppy media descriptor
        ByteUtils.writeU16LE(bpb, 22, 0) // FATSz16 = 0
        ByteUtils.writeU16LE(bpb, 24, 63)  // SecPerTrk
        ByteUtils.writeU16LE(bpb, 26, 255) // NumHeads
        ByteUtils.writeI32LE(bpb, 28, hiddenSectors.toInt())
        ByteUtils.writeI32LE(bpb, 32, totalSectors.toInt())
        ByteUtils.writeI32LE(bpb, 36, fatSizeSectors.toInt())
        ByteUtils.writeU16LE(bpb, 40, 0) // ExtFlags
        ByteUtils.writeU16LE(bpb, 42, 0) // FSVer
        ByteUtils.writeI32LE(bpb, 44, 2) // RootClus = 2
        ByteUtils.writeU16LE(bpb, 48, 1) // FSInfo = sector 1
        ByteUtils.writeU16LE(bpb, 50, 6) // BkBootSec = sector 6
        bpb[64] = 0x80.toByte()          // DrvNum
        bpb[66] = 0x29                   // Extended Boot Signature
        ByteUtils.writeI32LE(bpb, 67, volumeId)
        ByteUtils.writeAscii(bpb, 71, cleanVolumeLabel, 11)
        ByteUtils.writeAscii(bpb, 82, "FAT32   ", 8)
        bpb[510] = 0x55
        bpb[511] = 0xAA.toByte()
        return bpb
    }

    private fun buildFsInfoSector(): ByteArray {
        val fsInfo = ByteArray(blockSize)
        val allocatedCount = (nextFreeCluster - 2L).coerceAtLeast(1L)
        val freeCount = (totalDataClusters - allocatedCount).coerceAtLeast(0L)

        ByteUtils.writeI32LE(fsInfo, 0, 0x41615252)   // FSI_LeadSig
        ByteUtils.writeI32LE(fsInfo, 484, 0x61417272) // FSI_StrucSig
        ByteUtils.writeI32LE(fsInfo, 488, freeCount.toInt())
        ByteUtils.writeI32LE(fsInfo, 492, nextFreeCluster.toInt())
        ByteUtils.writeI32LE(fsInfo, 508, 0xAA550000.toInt()) // FSI_TrailSig
        return fsInfo
    }

    companion object {
        const val MAX_FAT32_FILE_SIZE: Long = 4_294_967_295L
        private const val MIN_FAT32_CLUSTERS = 65_525L
        private const val FAT32_EOC = 0x0FFFFFFFL
        private const val ATTR_VOLUME_ID = 0x08
        private const val ATTR_LFN = 0x0F
        private const val ATTR_DIRECTORY = 0x10
        private const val ATTR_ARCHIVE = 0x20

        // Valid MS-DOS timestamp: 2026-01-01 12:00:00
        private const val DEFAULT_DOS_TIME = (12 shl 11) or (0 shl 5) or 0
        private const val DEFAULT_DOS_DATE = ((2026 - 1980) shl 9) or (1 shl 5) or 1

        private fun chooseSectorsPerCluster(partitionBytes: Long, blockSize: Int): Int {
            val targetClusterBytes = when {
                partitionBytes <= 260L * 1024L * 1024L -> 512
                partitionBytes <= 8L * 1024L * 1024L * 1024L -> 4096
                partitionBytes <= 16L * 1024L * 1024L * 1024L -> 8192
                partitionBytes <= 32L * 1024L * 1024L * 1024L -> 16384
                else -> 32768
            }
            return (targetClusterBytes / blockSize).coerceAtLeast(1)
        }

        private fun sanitizeVolumeLabel(raw: String): String {
            val cleaned = raw.uppercase(Locale.ROOT)
                .filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == ' ' }
                .trim()
                .take(11)
            return if (cleaned.isEmpty()) "WIN_BOOT" else cleaned
        }

        private fun lfnEntryCountFor(name: String): Int {
            return (name.length + 12) / 13
        }

        private fun buildVolumeLabelEntry(label: String): ByteArray {
            val entry = ByteArray(32)
            ByteUtils.writeAscii(entry, 0, label, 11)
            entry[11] = ATTR_VOLUME_ID.toByte()
            ByteUtils.writeU16LE(entry, 22, DEFAULT_DOS_TIME)
            ByteUtils.writeU16LE(entry, 24, DEFAULT_DOS_DATE)
            return entry
        }

        private fun buildDotEntry(dotName: String, cluster: Long): ByteArray {
            val name11 = ByteArray(11) { 0x20 }
            for (i in dotName.indices) name11[i] = dotName[i].code.toByte()
            return buildShortEntry(name11, ATTR_DIRECTORY, cluster, 0L)
        }

        private fun buildShortEntry(
            shortName11: ByteArray,
            attributes: Int,
            firstCluster: Long,
            fileSize: Long
        ): ByteArray {
            val entry = ByteArray(32)
            System.arraycopy(shortName11, 0, entry, 0, 11)
            entry[11] = attributes.toByte()
            ByteUtils.writeU16LE(entry, 14, DEFAULT_DOS_TIME)
            ByteUtils.writeU16LE(entry, 16, DEFAULT_DOS_DATE)
            ByteUtils.writeU16LE(entry, 18, DEFAULT_DOS_DATE)
            ByteUtils.writeU16LE(entry, 20, ((firstCluster ushr 16) and 0xFFFFL).toInt())
            ByteUtils.writeU16LE(entry, 22, DEFAULT_DOS_TIME)
            ByteUtils.writeU16LE(entry, 24, DEFAULT_DOS_DATE)
            ByteUtils.writeU16LE(entry, 26, (firstCluster and 0xFFFFL).toInt())
            ByteUtils.writeI32LE(entry, 28, fileSize.toInt())
            return entry
        }

        private fun buildLfnEntries(longName: String, checksum: Int): List<ByteArray> {
            val total = (longName.length + 12) / 13
            val result = ArrayList<ByteArray>(total)
            for (seq in total downTo 1) {
                val entry = ByteArray(32)
                val ord = if (seq == total) (seq or 0x40) else seq
                entry[0] = ord.toByte()
                entry[11] = ATTR_LFN.toByte()
                entry[12] = 0
                entry[13] = (checksum and 0xFF).toByte()
                entry[26] = 0
                entry[27] = 0

                val startChar = (seq - 1) * 13
                for (i in 0 until 13) {
                    val idx = startChar + i
                    val codeUnit = when {
                        idx < longName.length -> longName[idx].code
                        idx == longName.length -> 0x0000
                        else -> 0xFFFF
                    }
                    val byteOff = when {
                        i < 5 -> 1 + i * 2
                        i < 11 -> 14 + (i - 5) * 2
                        else -> 28 + (i - 11) * 2
                    }
                    ByteUtils.writeU16LE(entry, byteOff, codeUnit)
                }
                result.add(entry)
            }
            return result
        }

        private fun lfnChecksum(shortName11: ByteArray): Int {
            var sum = 0
            for (i in 0 until 11) {
                sum = (((sum and 1) shl 7) + (sum ushr 1) + (shortName11[i].toInt() and 0xFF)) and 0xFF
            }
            return sum
        }

        private fun isValidShortChar(c: Char): Boolean {
            return c in 'A'..'Z' || c in '0'..'9' || c in "!#\$%&'()-@^_`{}~"
        }

        /**
         * Generates a unique 11-byte 8.3 Short File Name and reports whether VFAT LFN entries are required.
         */
        private fun makeShortName(name: String, usedNames: MutableSet<String>): Pair<ByteArray, Boolean> {
            val trimmed = name.trim()
            val lastDot = trimmed.lastIndexOf('.')
            val rawBase: String
            val rawExt: String
            if (lastDot > 0) {
                rawBase = trimmed.substring(0, lastDot)
                rawExt = trimmed.substring(lastDot + 1)
            } else {
                rawBase = trimmed
                rawExt = ""
            }

            val pure83 = rawBase.length in 1..8 &&
                rawExt.length <= 3 &&
                !rawBase.contains('.') &&
                rawBase.all { isValidShortChar(it) } &&
                rawExt.all { isValidShortChar(it) }

            val cleanBase = rawBase.uppercase(Locale.ROOT)
                .map { if (isValidShortChar(it)) it else '_' }
                .joinToString("")
                .ifEmpty { "FILE" }
            val cleanExt = rawExt.uppercase(Locale.ROOT)
                .map { if (isValidShortChar(it)) it else '_' }
                .joinToString("")
                .take(3)

            if (pure83) {
                val cand11 = pack83(rawBase, rawExt)
                val key = String(cand11, Charsets.US_ASCII)
                if (usedNames.add(key)) {
                    return Pair(cand11, false)
                }
            }

            // Always generate unique ~N numeric tail and emit LFN to preserve exact original name/case
            var n = 1
            while (true) {
                val suffix = "~$n"
                val prefixLen = (8 - suffix.length).coerceAtLeast(1)
                val base8 = cleanBase.take(prefixLen) + suffix
                val cand11 = pack83(base8, cleanExt)
                val key = String(cand11, Charsets.US_ASCII)
                if (usedNames.add(key)) {
                    return Pair(cand11, true)
                }
                n++
            }
        }

        private fun pack83(base: String, ext: String): ByteArray {
            val out = ByteArray(11) { 0x20 }
            for (i in 0 until minOf(base.length, 8)) {
                out[i] = base[i].code.toByte()
            }
            for (i in 0 until minOf(ext.length, 3)) {
                out[8 + i] = ext[i].code.toByte()
            }
            return out
        }
    }
}
