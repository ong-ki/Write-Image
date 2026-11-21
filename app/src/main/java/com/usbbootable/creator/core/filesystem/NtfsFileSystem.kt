package com.usbbootable.creator.core.filesystem

import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.util.ByteUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CancellationException

/**
 * Pure-Kotlin NTFS 3.1 formatter and streaming file writer operating directly over a [BlockDevice].
 *
 * Designed for UEFI-compatible Windows 10 and Windows 11 bootable USB creation (GPT Dual-Partition:
 * FAT32 EFI System Partition + NTFS 3.1 Windows Installer Partition) when `sources/install.wim`
 * exceeds FAT32's 4 GiB single-file limit.
 *
 * Produces a standards-compliant NTFS 3.1 volume verified against `ntfsfix` and `ntfsinfo`:
 * - Primary `$Boot` + trailing Backup Boot Sector
 * - `$MFT` (1024-byte FILE records with Update Sequence Array sector fixups) & `$MFTMirr`
 * - `$LogFile` (clean unmounted 0xFF journal), `$Volume` (v3.1), `$AttrDef`, `$Bitmap`,
 *   `$BadClus` (sparse `$Bad`), `$Secure` (`$SDS`, `$SDH`, `$SII`), `$UpCase` (128 KiB), `$Extend`
 * - Sorted `$I30` B-tree directory indexes (`$INDEX_ROOT` + `$INDEX_ALLOCATION` `INDX` blocks + `$BITMAP`)
 * - Contiguous non-resident `$DATA` extents supporting 64-bit file sizes (> 4 GiB `install.wim`)
 * - Reusable I/O buffer & `MessageDigest` with full SHA-256 read-back verification.
 */
class NtfsFileSystem(
    private val device: BlockDevice,
    private val hiddenSectors: Long,
    volumeLabel: String = "WIN_INSTALL",
    ioBufferBytes: Int = 256 * 1024,
    private val isCancelled: () -> Boolean = { false }
) {
    val blockSize: Int = device.blockSize
    val totalPartitionSectors: Long = device.totalBlocks
    val clusterSizeBytes: Int = 4096
    val sectorsPerCluster: Int = clusterSizeBytes / blockSize
    // Last sector of partition holds the backup NTFS boot sector; volume clusters span the preceding sectors
    val totalClusters: Long = (totalPartitionSectors - 1L) / sectorsPerCluster.toLong()
    val volumeNewestSectors: Long = totalClusters * sectorsPerCluster.toLong()
    val volumeSerialNumber: Long = SecureRandom().nextLong()
    val cleanVolumeLabel: String = volumeLabel.trim().take(32).ifEmpty { "WIN_INSTALL" }

    // Pre-computed 65,536-entry Windows NTFS $UpCase table
    private val upCaseMap = IntArray(65536) { code ->
        val up = code.toChar().uppercaseChar().code
        up
    }

    // Fixed system LCN layout
    private val mftStartLcn: Long = 4L
    private val mftMirrLcn: Long = 2L
    private val maxMftRecordsReserved: Int
    private val reservedMftClusters: Long
    private val mftBitmapLcn: Long
    private val attrDefLcn: Long
    private val volBitmapLcn: Long
    private val volBitmapLengthBytes: Long
    private val volBitmapClusters: Long
    private val secureSdsLcn: Long
    private val secureSdsClusters: Long = 65L // 256 KiB mirror offset + 1 cluster
    private val upCaseLcn: Long
    private val upCaseClusters: Long = 32L    // 128 KiB
    private val logFileLcn: Long
    private val logFileClusters: Long
    private val firstDataLcn: Long

    private var nextFreeCluster: Long

    private class DirNode(
        val name: String,
        val parent: DirNode?,
        var mftRecordNo: Int = 0,
        val childrenDirs: MutableMap<String, DirNode> = LinkedHashMap(),
        val childrenFiles: MutableList<FileNode> = mutableListOf()
    )

    private data class FileNode(
        val name: String,
        val sizeBytes: Long,
        val startLcn: Long,
        val clusterCount: Long,
        var mftRecordNo: Int = 0
    )

    private data class IndexEntryItem(
        val name: String,
        val mftNo: Int,
        val seqNo: Int,
        val fnBytes: ByteArray,
        val subnodeVcn: Long? = null
    )

    private val rootDir = DirNode(name = ".", parent = null, mftRecordNo = 5)
    private val writtenFiles = mutableListOf<WrittenFileAllocation>()

    private val batchClusters: Int
    private val batchBytes: Int
    private val reusableIoBuffer: ByteArray
    private val sha256Digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    init {
        require(blockSize in 512..4096 && (blockSize and (blockSize - 1)) == 0) {
            "Unsupported block size for NTFS: $blockSize"
        }
        if (totalClusters < 512L) {
            throw IOException("Partition too small to format as NTFS 3.1 ($totalClusters clusters).")
        }

        // Reserve room for up to 4096 MFT records (1024 clusters = 4 MiB) on large drives,
        // or 256 MFT records (64 clusters = 256 KiB) on small synthetic test volumes.
        maxMftRecordsReserved = if (totalClusters >= 8192L) 4096 else 256
        reservedMftClusters = (maxMftRecordsReserved / MFT_RECORDS_PER_CLUSTER).toLong()

        mftBitmapLcn = mftStartLcn + reservedMftClusters
        attrDefLcn = mftBitmapLcn + 1L
        volBitmapLcn = attrDefLcn + 1L

        // NTFS $Bitmap size in bytes is rounded up to an 8-byte boundary
        val rawBmpBytes = (totalClusters + 7L) / 8L
        volBitmapLengthBytes = ((rawBmpBytes + 7L) / 8L) * 8L
        volBitmapClusters = ((volBitmapLengthBytes + clusterSizeBytes - 1L) / clusterSizeBytes).coerceAtLeast(1L)

        secureSdsLcn = volBitmapLcn + volBitmapClusters
        upCaseLcn = secureSdsLcn + secureSdsClusters
        logFileLcn = upCaseLcn + upCaseClusters
        logFileClusters = if (totalClusters >= 4095L) 512L else 64L

        firstDataLcn = logFileLcn + logFileClusters
        if (firstDataLcn >= totalClusters - 8L) {
            throw IOException("Partition has insufficient data clusters for NTFS metadata.")
        }
        nextFreeCluster = firstDataLcn

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
     * Streams a file of arbitrary 64-bit size (including > 4 GiB `sources/install.wim`) directly
     * into contiguous NTFS clusters while calculating its SHA-256 digest.
     */
    @Throws(IOException::class)
    fun writeFile(
        normalizedPath: String,
        sizeBytes: Long,
        streamer: (onChunk: (ByteArray, Int) -> Unit) -> Unit,
        onBytesWritten: (Long) -> Unit = {}
    ): WrittenFileAllocation {
        require(sizeBytes >= 0L) { "Negative file size: $sizeBytes" }
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
            streamer { _, _ -> }
            parent.childrenFiles.add(
                FileNode(
                    name = fileName,
                    sizeBytes = 0L,
                    startLcn = 0L,
                    clusterCount = 0L
                )
            )
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

        val startLcn = allocateContiguousClusters(neededClusters)
        var currentLba = clusterToLba(startLcn)

        val writeBuf = reusableIoBuffer
        var bufFill = 0
        var totalWritten = 0L

        streamer { data, len ->
            if (isCancelled()) throw CancellationException("NTFS write cancelled.")
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
                    if (isCancelled()) throw CancellationException("NTFS write cancelled.")
                    val secCount = batchBytes / blockSize
                    device.writeBlocks(currentLba, secCount, writeBuf, 0)
                    currentLba += secCount
                    totalWritten += batchBytes
                    onBytesWritten(batchBytes.toLong())
                    bufFill = 0
                }
            }
        }

        if (bufFill > 0) {
            if (isCancelled()) throw CancellationException("NTFS write cancelled.")
            val paddedClusters = (bufFill + clusterSizeBytes - 1) / clusterSizeBytes
            val paddedBytes = paddedClusters * clusterSizeBytes
            writeBuf.fill(0, bufFill, paddedBytes)
            val secCount = paddedBytes / blockSize
            device.writeBlocks(currentLba, secCount, writeBuf, 0)
            totalWritten += bufFill
            onBytesWritten(bufFill.toLong())
        }

        if (totalWritten != sizeBytes) {
            throw IOException(
                "NTFS stream size mismatch for '$normalizedPath': expected=$sizeBytes, got=$totalWritten"
            )
        }

        parent.childrenFiles.add(
            FileNode(
                name = fileName,
                sizeBytes = sizeBytes,
                startLcn = startLcn,
                clusterCount = neededClusters
            )
        )
        val rec = WrittenFileAllocation(
            path = normalizedPath,
            sizeBytes = sizeBytes,
            firstCluster = startLcn,
            clusterCount = neededClusters,
            sha256Hex = ByteUtils.toHex(sha256Digest.digest())
        )
        writtenFiles.add(rec)
        return rec
    }

    /**
     * Writes directory B-trees (`INDX`), `$MFT`, `$MFTMirr`, `$AttrDef`, `$Bitmap`, `$Secure`,
     * `$UpCase`, `$LogFile`, and primary + backup `$Boot` sectors.
     */
    @Throws(IOException::class)
    fun finalizeFileSystem() {
        if (isCancelled()) throw CancellationException("NTFS finalize cancelled.")

        // 1. Collect all directories (BFS) and assign MFT record numbers >= 24 to user dirs and files
        val allDirs = mutableListOf<DirNode>()
        val queue = ArrayDeque<DirNode>()
        queue.add(rootDir)
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            allDirs.add(d)
            queue.addAll(d.childrenDirs.values)
        }

        var nextMftNo = FIRST_USER_MFT_RECORD
        for (dir in allDirs) {
            if (dir !== rootDir) {
                dir.mftRecordNo = nextMftNo++
            }
            for (file in dir.childrenFiles) {
                file.mftRecordNo = nextMftNo++
            }
        }

        val totalMftRecords = maxOf(28, ((nextMftNo + 3) / 4) * 4)
        if (totalMftRecords > maxMftRecordsReserved) {
            throw IOException(
                "NTFS MFT record count ($totalMftRecords) exceeds reserved table capacity ($maxMftRecordsReserved)."
            )
        }
        val activeMftClusters = (totalMftRecords / MFT_RECORDS_PER_CLUSTER).toLong()
        val activeMftBytes = totalMftRecords.toLong() * MFT_RECORD_SIZE.toLong()

        // 2. Build directory $I30 B-trees and write any non-resident INDX blocks
        val dirIndexRoots = HashMap<Int, ByteArray>()
        val dirIndxAllocations = HashMap<Int, Pair<Long, Int>>() // mftNo -> (startLcn, blockCount)
        val dirIndexBitmaps = HashMap<Int, ByteArray>()

        for (dir in allDirs) {
            if (isCancelled()) throw CancellationException("NTFS directory indexing cancelled.")
            val items = mutableListOf<IndexEntryItem>()

            if (dir === rootDir) {
                // Root directory '.' also indexes system metadata files 0..11
                items.addAll(buildRootSystemIndexItems(activeMftClusters, activeMftBytes))
            }

            val dirSeq = if (dir === rootDir) 5 else 1
            for (sub in dir.childrenDirs.values) {
                val fn = buildFileNameValue(
                    parentMft = dir.mftRecordNo,
                    parentSeq = dirSeq,
                    name = sub.name,
                    allocatedSize = 0L,
                    dataSize = 0L,
                    fileAttrs = ATTR_FILE_ARCHIVE,
                    isDirectory = true
                )
                items.add(IndexEntryItem(sub.name, sub.mftRecordNo, 1, fn))
            }
            for (file in dir.childrenFiles) {
                val allocBytes = file.clusterCount * clusterSizeBytes.toLong()
                val fn = buildFileNameValue(
                    parentMft = dir.mftRecordNo,
                    parentSeq = dirSeq,
                    name = file.name,
                    allocatedSize = allocBytes,
                    dataSize = file.sizeBytes,
                    fileAttrs = ATTR_FILE_ARCHIVE,
                    isDirectory = false
                )
                items.add(IndexEntryItem(file.name, file.mftRecordNo, 1, fn))
            }

            // Sort items strictly using the volume's $UpCase collation rule
            items.sortWith { a, b -> compareNtfsNames(a.name, b.name) }

            // Root directory '.' always uses LARGE_INDEX (at least 1 INDX block) to match standard mkntfs
            val (irBytes, indxBlocks, bmpBytes) = packDirectoryBTree(
                sortedItems = items,
                forceLargeIndex = (dir === rootDir)
            )
            dirIndexRoots[dir.mftRecordNo] = irBytes
            if (indxBlocks.isNotEmpty()) {
                val indxLcn = allocateContiguousClusters(indxBlocks.size.toLong())
                val indxBuf = ByteArray(indxBlocks.size * clusterSizeBytes)
                for (i in indxBlocks.indices) {
                    System.arraycopy(indxBlocks[i], 0, indxBuf, i * clusterSizeBytes, clusterSizeBytes)
                }
                device.writeBlocks(clusterToLba(indxLcn), indxBuf.size / blockSize, indxBuf, 0)
                dirIndxAllocations[dir.mftRecordNo] = Pair(indxLcn, indxBlocks.size)
                dirIndexBitmaps[dir.mftRecordNo] = bmpBytes
            }
        }

        // 3. Write $MFT $BITMAP at mftBitmapLcn
        val mftBmpCluster = ByteArray(clusterSizeBytes)
        val mftBmpBytesLen = (((totalMftRecords + 7) / 8 + 7) / 8) * 8
        for (bit in 0 until 16) {
            mftBmpCluster[bit / 8] = (mftBmpCluster[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        for (bit in FIRST_USER_MFT_RECORD until nextMftNo) {
            mftBmpCluster[bit / 8] = (mftBmpCluster[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        device.writeBlocks(clusterToLcnLba(mftBitmapLcn), sectorsPerCluster, mftBmpCluster, 0)

        // 4. Write $AttrDef at attrDefLcn
        val attrDefCluster = ByteArray(clusterSizeBytes)
        val attrDefBytes = buildAttrDefTable()
        System.arraycopy(attrDefBytes, 0, attrDefCluster, 0, attrDefBytes.size)
        device.writeBlocks(clusterToLcnLba(attrDefLcn), sectorsPerCluster, attrDefCluster, 0)

        // 5. Write $Secure $SDS at secureSdsLcn (and 256 KiB mirror at secureSdsLcn + 64)
        val sdsCluster = ByteArray(clusterSizeBytes)
        System.arraycopy(SDS_256_BYTES, 0, sdsCluster, 0, SDS_256_BYTES.size)
        val zeroCluster = ByteArray(clusterSizeBytes)
        device.writeBlocks(clusterToLcnLba(secureSdsLcn), sectorsPerCluster, sdsCluster, 0)
        for (c in 1L until 64L) {
            device.writeBlocks(clusterToLcnLba(secureSdsLcn + c), sectorsPerCluster, zeroCluster, 0)
        }
        device.writeBlocks(clusterToLcnLba(secureSdsLcn + 64L), sectorsPerCluster, sdsCluster, 0)

        // 6. Write $UpCase (128 KiB = 32 clusters) at upCaseLcn
        val upCaseBytes = ByteArray(131_072)
        for (i in 0 until 65536) {
            ByteUtils.writeU16LE(upCaseBytes, i * 2, upCaseMap[i])
        }
        device.writeBlocks(clusterToLcnLba(upCaseLcn), upCaseBytes.size / blockSize, upCaseBytes, 0)

        // 7. Write $LogFile (clean unmounted 0xFF journal) at logFileLcn
        writeCleanLogFile()

        // 8. Write Volume $Bitmap at volBitmapLcn
        writeVolumeBitmap(activeMftClusters)

        // 9. Build and write all MFT records (0 .. totalMftRecords - 1) and $MFTMirr (records 0..3)
        writeMftAndMirror(
            totalMftRecords = totalMftRecords,
            nextUserMftNo = nextMftNo,
            activeMftClusters = activeMftClusters,
            activeMftBytes = activeMftBytes,
            mftBmpBytesLen = mftBmpBytesLen,
            allDirs = allDirs,
            dirIndexRoots = dirIndexRoots,
            dirIndxAllocations = dirIndxAllocations,
            dirIndexBitmaps = dirIndexBitmaps
        )

        // 10. Write $Boot (Cluster 0..1) and Backup Boot Sector at (totalPartitionSectors - 1)
        val bootCluster01 = ByteArray(2 * clusterSizeBytes)
        val bootSector = buildBootSector()
        System.arraycopy(bootSector, 0, bootCluster01, 0, blockSize)
        device.writeBlocks(0L, (2 * clusterSizeBytes) / blockSize, bootCluster01, 0)
        device.writeBlocks(totalPartitionSectors - 1L, 1, bootSector, 0)
    }

    /**
     * Reads back a previously written NTFS file directly from [device] and verifies its SHA-256.
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
            if (isCancelled()) throw CancellationException("NTFS verification cancelled.")
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

        return ByteUtils.toHex(sha256Digest.digest()) == record.sha256Hex
    }

    private fun allocateContiguousClusters(count: Long): Long {
        val start = nextFreeCluster
        val endExclusive = start + count
        if (endExclusive > totalClusters) {
            throw IOException(
                "NTFS partition out of space: requested $count clusters at $start (totalClusters=$totalClusters)."
            )
        }
        nextFreeCluster = endExclusive
        return start
    }

    private fun clusterToLba(lcn: Long): Long = lcn * sectorsPerCluster.toLong()
    private fun clusterToLcnLba(lcn: Long): Long = lcn * sectorsPerCluster.toLong()

    private fun writeCleanLogFile() {
        val chunkClusters = minOf(16L, logFileClusters).toInt()
        val ffBuf = ByteArray(chunkClusters * clusterSizeBytes) { 0xFF.toByte() }
        var written = 0L
        while (written < logFileClusters) {
            if (isCancelled()) throw CancellationException("NTFS \$LogFile init cancelled.")
            val batch = minOf(chunkClusters.toLong(), logFileClusters - written).toInt()
            device.writeBlocks(
                clusterToLba(logFileLcn + written),
                batch * sectorsPerCluster,
                ffBuf,
                0
            )
            written += batch
        }
    }

    private fun writeVolumeBitmap(activeMftClusters: Long) {
        // Allocated clusters on volume:
        // - 0..1 ($Boot)
        // - 2 ($MFTMirr)
        // - 4 .. 4 + activeMftClusters - 1 ($MFT active clusters)
        // - mftBitmapLcn .. nextFreeCluster - 1 (all system metadata + all user files + all INDX blocks)
        val totalBitmapRegionSectors = volBitmapClusters * sectorsPerCluster.toLong()
        val chunkSectors = ((64 * 1024) / blockSize).coerceAtLeast(sectorsPerCluster)
        val buf = ByteArray(chunkSectors * blockSize)
        val mftEndExclusive = mftStartLcn + activeMftClusters

        var writtenSectors = 0L
        var bitBase = 0L
        while (writtenSectors < totalBitmapRegionSectors) {
            if (isCancelled()) throw CancellationException("NTFS \$Bitmap flush cancelled.")
            val batchSec = minOf(chunkSectors.toLong(), totalBitmapRegionSectors - writtenSectors).toInt()
            val batchBytes = batchSec * blockSize
            buf.fill(0, 0, batchBytes)

            for (byteIdx in 0 until batchBytes) {
                val bytePos = (writtenSectors * blockSize) + byteIdx
                if (bytePos >= volBitmapLengthBytes) break
                var mask = 0
                for (b in 0 until 8) {
                    val lcn = bitBase + byteIdx.toLong() * 8L + b.toLong()
                    val isAllocated = when {
                        lcn == 0L || lcn == 1L || lcn == mftMirrLcn -> true
                        lcn in mftStartLcn until mftEndExclusive -> true
                        lcn in mftBitmapLcn until nextFreeCluster -> true
                        lcn >= totalClusters && bytePos < (totalClusters + 7L) / 8L -> true // padding bits in last valid byte
                        else -> false
                    }
                    if (isAllocated) {
                        mask = mask or (1 shl b)
                    }
                }
                buf[byteIdx] = mask.toByte()
            }

            device.writeBlocks(clusterToLba(volBitmapLcn) + writtenSectors, batchSec, buf, 0)
            writtenSectors += batchSec
            bitBase += batchBytes.toLong() * 8L
        }
    }

    private fun writeMftAndMirror(
        totalMftRecords: Int,
        nextUserMftNo: Int,
        activeMftClusters: Long,
        activeMftBytes: Long,
        mftBmpBytesLen: Int,
        allDirs: List<DirNode>,
        dirIndexRoots: Map<Int, ByteArray>,
        dirIndxAllocations: Map<Int, Pair<Long, Int>>,
        dirIndexBitmaps: Map<Int, ByteArray>
    ) {
        val mftBytes = ByteArray(totalMftRecords * MFT_RECORD_SIZE)

        fun putRecord(recNo: Int, bytes: ByteArray) {
            System.arraycopy(bytes, 0, mftBytes, recNo * MFT_RECORD_SIZE, MFT_RECORD_SIZE)
        }

        val mftAllocBytes = activeMftClusters * clusterSizeBytes.toLong()
        val logBytes = logFileClusters * clusterSizeBytes.toLong()
        val sdsDataBytes = 262_144L + SDS_256_BYTES.size.toLong()
        val sdsAllocBytes = secureSdsClusters * clusterSizeBytes.toLong()

        // MFT[0]: $MFT
        putRecord(
            0,
            buildMftRecord(
                recNo = 0,
                seqNo = 1,
                flags = FLAG_IN_USE,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0x0100, size48 = false)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$MFT", mftAllocBytes, activeMftBytes, 0x06)),
                    AttrSpec.NonResident(0x80, "", mftStartLcn, activeMftClusters, activeMftBytes, mftAllocBytes, activeMftBytes),
                    AttrSpec.NonResident(0xB0, "", mftBitmapLcn, 1L, mftBmpBytesLen.toLong(), clusterSizeBytes.toLong(), mftBmpBytesLen.toLong())
                )
            )
        )

        // MFT[1]: $MFTMirr
        putRecord(
            1,
            buildMftRecord(
                recNo = 1,
                seqNo = 1,
                flags = FLAG_IN_USE,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0x0100, size48 = false)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$MFTMirr", clusterSizeBytes.toLong(), clusterSizeBytes.toLong(), 0x06)),
                    AttrSpec.NonResident(0x80, "", mftMirrLcn, 1L, clusterSizeBytes.toLong(), clusterSizeBytes.toLong(), clusterSizeBytes.toLong())
                )
            )
        )

        // MFT[2]: $LogFile
        putRecord(
            2,
            buildMftRecord(
                recNo = 2,
                seqNo = 2,
                flags = FLAG_IN_USE,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0x0100, size48 = false)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$LogFile", logBytes, logBytes, 0x06)),
                    AttrSpec.NonResident(0x80, "", logFileLcn, logFileClusters, logBytes, logBytes, logBytes)
                )
            )
        )

        // MFT[3]: $Volume (NTFS 3.1)
        val volInfo = ByteArray(12).apply {
            this[8] = 3 // Major version 3
            this[9] = 1 // Minor version 1
        }
        putRecord(
            3,
            buildMftRecord(
                recNo = 3,
                seqNo = 3,
                flags = FLAG_IN_USE,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0, size48 = true)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$Volume", 0L, 0L, 0x06)),
                    AttrSpec.Resident(0x50, "", RESIDENT_SECURITY_DESCRIPTOR),
                    AttrSpec.Resident(0x60, "", cleanVolumeLabel.toByteArray(Charsets.UTF_16LE)),
                    AttrSpec.Resident(0x70, "", volInfo),
                    AttrSpec.Resident(0x80, "", ByteArray(0))
                )
            )
        )

        // MFT[4]: $AttrDef
        putRecord(
            4,
            buildMftRecord(
                recNo = 4,
                seqNo = 4,
                flags = FLAG_IN_USE,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0, size48 = true)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$AttrDef", clusterSizeBytes.toLong(), 2560L, 0x06)),
                    AttrSpec.Resident(0x50, "", RESIDENT_SECURITY_DESCRIPTOR),
                    AttrSpec.NonResident(0x80, "", attrDefLcn, 1L, 2560L, clusterSizeBytes.toLong(), 2560L)
                )
            )
        )

        // MFT[5]: '.' (Root Directory)
        val (rootIndxLcn, rootIndxCount) = dirIndxAllocations.getValue(5)
        val rootIndxBytes = rootIndxCount.toLong() * clusterSizeBytes.toLong()
        putRecord(
            5,
            buildMftRecord(
                recNo = 5,
                seqNo = 5,
                flags = FLAG_IN_USE or FLAG_DIRECTORY,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x26, 0, size48 = true)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, ".", 0L, 0L, 0x06, isDirectory = true)),
                    AttrSpec.Resident(0x50, "", RESIDENT_SECURITY_DESCRIPTOR),
                    AttrSpec.Resident(0x90, "\$I30", dirIndexRoots.getValue(5)),
                    AttrSpec.NonResident(0xA0, "\$I30", rootIndxLcn, rootIndxCount.toLong(), rootIndxBytes, rootIndxBytes, rootIndxBytes),
                    AttrSpec.Resident(0xB0, "\$I30", dirIndexBitmaps.getValue(5))
                )
            )
        )

        // MFT[6]: $Bitmap
        val volBmpAllocBytes = volBitmapClusters * clusterSizeBytes.toLong()
        putRecord(
            6,
            buildMftRecord(
                recNo = 6,
                seqNo = 6,
                flags = FLAG_IN_USE,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0x0100, size48 = false)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$Bitmap", volBmpAllocBytes, volBitmapLengthBytes, 0x06)),
                    AttrSpec.NonResident(0x80, "", volBitmapLcn, volBitmapClusters, volBitmapLengthBytes, volBmpAllocBytes, volBitmapLengthBytes)
                )
            )
        )

        // MFT[7]: $Boot
        val bootBytes = 2L * clusterSizeBytes.toLong()
        putRecord(
            7,
            buildMftRecord(
                recNo = 7,
                seqNo = 7,
                flags = FLAG_IN_USE,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0, size48 = true)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$Boot", bootBytes, bootBytes, 0x06)),
                    AttrSpec.Resident(0x50, "", RESIDENT_SECURITY_DESCRIPTOR),
                    AttrSpec.NonResident(0x80, "", 0L, 2L, bootBytes, bootBytes, bootBytes)
                )
            )
        )

        // MFT[8]: $BadClus
        val badTotalBytes = totalClusters * clusterSizeBytes.toLong()
        putRecord(
            8,
            buildMftRecord(
                recNo = 8,
                seqNo = 8,
                flags = FLAG_IN_USE,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0x0100, size48 = false)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$BadClus", 0L, 0L, 0x06)),
                    AttrSpec.Resident(0x80, "", ByteArray(0)),
                    AttrSpec.NonResident(0x80, "\$Bad", 0L, totalClusters, badTotalBytes, badTotalBytes, 0L, sparse = true)
                )
            )
        )

        // MFT[9]: $Secure
        putRecord(
            9,
            buildMftRecord(
                recNo = 9,
                seqNo = 9,
                flags = FLAG_IN_USE or 0x08, // VIEW_INDEX
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x20000006, 0x0101, size48 = false)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$Secure", 0L, 0L, 0x20000006)),
                    AttrSpec.NonResident(0x80, "\$SDS", secureSdsLcn, secureSdsClusters, sdsDataBytes, sdsAllocBytes, sdsDataBytes),
                    AttrSpec.Resident(0x90, "\$SDH", SDH_INDEX_ROOT_BYTES),
                    AttrSpec.Resident(0x90, "\$SII", SII_INDEX_ROOT_BYTES)
                )
            )
        )

        // MFT[10]: $UpCase
        putRecord(
            10,
            buildMftRecord(
                recNo = 10,
                seqNo = 10,
                flags = FLAG_IN_USE,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0x0100, size48 = false)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$UpCase", 131_072L, 131_072L, 0x06)),
                    AttrSpec.NonResident(0x80, "", upCaseLcn, upCaseClusters, 131_072L, 131_072L, 131_072L)
                )
            )
        )

        // MFT[11]: $Extend
        putRecord(
            11,
            buildMftRecord(
                recNo = 11,
                seqNo = 11,
                flags = FLAG_IN_USE or FLAG_DIRECTORY,
                attrs = listOf(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0x0101, size48 = false)),
                    AttrSpec.Resident(0x30, "", buildFileNameValue(5, 5, "\$Extend", 0L, 0L, 0x06, isDirectory = true)),
                    AttrSpec.Resident(0x90, "\$I30", buildIndexRoot(0x30, 1, buildEndIndexEntry(null), isLarge = false))
                )
            )
        )

        // MFT[12..15]: In-use system reserved records
        for (r in 12..15) {
            putRecord(
                r,
                buildMftRecord(
                    recNo = r,
                    seqNo = r,
                    flags = FLAG_IN_USE,
                    attrs = listOf(
                        AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0, size48 = true)),
                        AttrSpec.Resident(0x50, "", RESIDENT_SECURITY_DESCRIPTOR),
                        AttrSpec.Resident(0x80, "", ByteArray(0))
                    )
                )
            )
        }

        // MFT[16..23]: Free reserved system records
        for (r in 16..23) {
            putRecord(
                r,
                buildMftRecord(
                    recNo = r,
                    seqNo = r,
                    flags = 0x0000,
                    attrs = listOf(
                        AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0, size48 = true))
                    )
                )
            )
        }

        // User directories & files (MFT[24 .. nextUserMftNo - 1])
        for (dir in allDirs) {
            val parentMft = dir.parent?.mftRecordNo ?: 5
            val parentSeq = if (parentMft == 5) 5 else 1

            if (dir !== rootDir) {
                val dirAttrs = mutableListOf<AttrSpec>(
                    AttrSpec.Resident(0x10, "", buildStandardInfo(ATTR_FILE_ARCHIVE, 0x0101, size48 = false)),
                    AttrSpec.Resident(
                        0x30,
                        "",
                        buildFileNameValue(parentMft, parentSeq, dir.name, 0L, 0L, ATTR_FILE_ARCHIVE, isDirectory = true)
                    ),
                    AttrSpec.Resident(0x90, "\$I30", dirIndexRoots.getValue(dir.mftRecordNo))
                )
                val indxAlloc = dirIndxAllocations[dir.mftRecordNo]
                if (indxAlloc != null) {
                    val (lcn, count) = indxAlloc
                    val bytesLen = count.toLong() * clusterSizeBytes.toLong()
                    dirAttrs.add(AttrSpec.NonResident(0xA0, "\$I30", lcn, count.toLong(), bytesLen, bytesLen, bytesLen))
                    dirAttrs.add(AttrSpec.Resident(0xB0, "\$I30", dirIndexBitmaps.getValue(dir.mftRecordNo)))
                }
                putRecord(
                    dir.mftRecordNo,
                    buildMftRecord(
                        recNo = dir.mftRecordNo,
                        seqNo = 1,
                        flags = FLAG_IN_USE or FLAG_DIRECTORY,
                        attrs = dirAttrs
                    )
                )
            }

            val thisDirSeq = if (dir === rootDir) 5 else 1
            for (file in dir.childrenFiles) {
                val allocBytes = file.clusterCount * clusterSizeBytes.toLong()
                val fn = buildFileNameValue(
                    parentMft = dir.mftRecordNo,
                    parentSeq = thisDirSeq,
                    name = file.name,
                    allocatedSize = allocBytes,
                    dataSize = file.sizeBytes,
                    fileAttrs = ATTR_FILE_ARCHIVE,
                    isDirectory = false
                )
                val dataAttr = if (file.clusterCount == 0L) {
                    AttrSpec.Resident(0x80, "", ByteArray(0))
                } else {
                    AttrSpec.NonResident(
                        type = 0x80,
                        name = "",
                        startLcn = file.startLcn,
                        clusterCount = file.clusterCount,
                        dataSize = file.sizeBytes,
                        allocatedSize = allocBytes,
                        initializedSize = file.sizeBytes
                    )
                }
                putRecord(
                    file.mftRecordNo,
                    buildMftRecord(
                        recNo = file.mftRecordNo,
                        seqNo = 1,
                        flags = FLAG_IN_USE,
                        attrs = listOf(
                            AttrSpec.Resident(0x10, "", buildStandardInfo(ATTR_FILE_ARCHIVE, 0x0101, size48 = false)),
                            AttrSpec.Resident(0x30, "", fn),
                            dataAttr
                        )
                    )
                )
            }
        }

        // Trailing free MFT records up to totalMftRecords
        for (r in nextUserMftNo until totalMftRecords) {
            putRecord(
                r,
                buildMftRecord(
                    recNo = r,
                    seqNo = 1,
                    flags = 0x0000,
                    attrs = listOf(
                        AttrSpec.Resident(0x10, "", buildStandardInfo(0x06, 0, size48 = true))
                    )
                )
            )
        }

        // Write $MFT to mftStartLcn and $MFTMirr (first 4 records = 4096B) to mftMirrLcn
        device.writeBlocks(clusterToLba(mftStartLcn), mftBytes.size / blockSize, mftBytes, 0)
        device.writeBlocks(clusterToLba(mftMirrLcn), clusterSizeBytes / blockSize, mftBytes, 0)
    }

    private fun buildRootSystemIndexItems(
        activeMftClusters: Long,
        activeMftBytes: Long
    ): List<IndexEntryItem> {
        val mftAlloc = activeMftClusters * clusterSizeBytes.toLong()
        val logBytes = logFileClusters * clusterSizeBytes.toLong()
        val bmpAlloc = volBitmapClusters * clusterSizeBytes.toLong()
        val bootBytes = 2L * clusterSizeBytes.toLong()
        return listOf(
            IndexEntryItem("\$MFT", 0, 1, buildFileNameValue(5, 5, "\$MFT", mftAlloc, activeMftBytes, 0x06)),
            IndexEntryItem("\$MFTMirr", 1, 1, buildFileNameValue(5, 5, "\$MFTMirr", clusterSizeBytes.toLong(), clusterSizeBytes.toLong(), 0x06)),
            IndexEntryItem("\$LogFile", 2, 2, buildFileNameValue(5, 5, "\$LogFile", logBytes, logBytes, 0x06)),
            IndexEntryItem("\$Volume", 3, 3, buildFileNameValue(5, 5, "\$Volume", 0L, 0L, 0x06)),
            IndexEntryItem("\$AttrDef", 4, 4, buildFileNameValue(5, 5, "\$AttrDef", clusterSizeBytes.toLong(), 2560L, 0x06)),
            IndexEntryItem(".", 5, 5, buildFileNameValue(5, 5, ".", 0L, 0L, 0x06, isDirectory = true)),
            IndexEntryItem("\$Bitmap", 6, 6, buildFileNameValue(5, 5, "\$Bitmap", bmpAlloc, volBitmapLengthBytes, 0x06)),
            IndexEntryItem("\$Boot", 7, 7, buildFileNameValue(5, 5, "\$Boot", bootBytes, bootBytes, 0x06)),
            IndexEntryItem("\$BadClus", 8, 8, buildFileNameValue(5, 5, "\$BadClus", 0L, 0L, 0x06)),
            IndexEntryItem("\$Secure", 9, 9, buildFileNameValue(5, 5, "\$Secure", 0L, 0L, 0x20000006)),
            IndexEntryItem("\$UpCase", 10, 10, buildFileNameValue(5, 5, "\$UpCase", 131_072L, 131_072L, 0x06)),
            IndexEntryItem("\$Extend", 11, 11, buildFileNameValue(5, 5, "\$Extend", 0L, 0L, 0x06, isDirectory = true))
        )
    }

    /**
     * Packs sorted directory entries into either a compact resident `$INDEX_ROOT`
     * or a multi-block B-tree of 4096-byte `INDX` blocks.
     */
    private fun packDirectoryBTree(
        sortedItems: List<IndexEntryItem>,
        forceLargeIndex: Boolean
    ): Triple<ByteArray, List<ByteArray>, ByteArray> {
        if (!forceLargeIndex) {
            val baos = ByteArrayOutputStream()
            for (item in sortedItems) {
                baos.write(buildIndexEntry(item.mftNo, item.seqNo, item.fnBytes, null))
            }
            baos.write(buildEndIndexEntry(null))
            val leafBytes = baos.toByteArray()
            if (leafBytes.size <= MAX_RESIDENT_INDEX_ENTRIES_BYTES) {
                return Triple(
                    buildIndexRoot(0x30, 1, leafBytes, isLarge = false),
                    emptyList(),
                    ByteArray(0)
                )
            }
        }

        val indxBlocks = mutableListOf<ByteArray>()
        var currentLevel = sortedItems
        var isLeafLevel = true

        while (true) {
            if (!isLeafLevel) {
                val baos = ByteArrayOutputStream()
                for (item in currentLevel) {
                    if (item.fnBytes.isEmpty()) {
                        baos.write(buildEndIndexEntry(item.subnodeVcn))
                    } else {
                        baos.write(buildIndexEntry(item.mftNo, item.seqNo, item.fnBytes, item.subnodeVcn))
                    }
                }
                val rootEntries = baos.toByteArray()
                if (rootEntries.size <= MAX_RESIDENT_INDEX_ENTRIES_BYTES) {
                    val bmpLen = maxOf(8, ((indxBlocks.size + 63) / 64) * 8)
                    val bmp = ByteArray(bmpLen)
                    for (b in indxBlocks.indices) {
                        bmp[b / 8] = (bmp[b / 8].toInt() or (1 shl (b % 8))).toByte()
                    }
                    return Triple(
                        buildIndexRoot(0x30, 1, rootEntries, isLarge = true),
                        indxBlocks,
                        bmp
                    )
                }
            }

            val nextLevel = mutableListOf<IndexEntryItem>()
            var i = 0
            val n = currentLevel.size
            if (n == 0) {
                val vcn = indxBlocks.size.toLong()
                indxBlocks.add(buildIndxBlock(vcn, buildEndIndexEntry(null), isNode = false))
                nextLevel.add(IndexEntryItem("", 0, 0, ByteArray(0), subnodeVcn = vcn))
            }
            while (i < n) {
                val chunk = ByteArrayOutputStream()
                var j = i
                while (j < n) {
                    val item = currentLevel[j]
                    val eb = if (item.fnBytes.isEmpty()) {
                        buildEndIndexEntry(item.subnodeVcn)
                    } else {
                        buildIndexEntry(item.mftNo, item.seqNo, item.fnBytes, item.subnodeVcn)
                    }
                    // Stop before overflow ONLY if j + 1 < n so a promoted separator at j always has a non-empty right sibling!
                    if (chunk.size() + eb.size + 32 > MAX_INDX_PAYLOAD_BYTES && j > i && (j + 1) < n) {
                        break
                    }
                    chunk.write(eb)
                    j++
                }

                val vcn = indxBlocks.size.toLong()
                if (j < n && currentLevel[j].fnBytes.isNotEmpty()) {
                    val promoted = currentLevel[j]
                    chunk.write(buildEndIndexEntry(if (isLeafLevel) null else promoted.subnodeVcn))
                    indxBlocks.add(buildIndxBlock(vcn, chunk.toByteArray(), isNode = !isLeafLevel))
                    nextLevel.add(
                        IndexEntryItem(
                            name = promoted.name,
                            mftNo = promoted.mftNo,
                            seqNo = promoted.seqNo,
                            fnBytes = promoted.fnBytes,
                            subnodeVcn = vcn
                        )
                    )
                    i = j + 1
                } else {
                    if (isLeafLevel) {
                        chunk.write(buildEndIndexEntry(null))
                    }
                    indxBlocks.add(buildIndxBlock(vcn, chunk.toByteArray(), isNode = !isLeafLevel))
                    nextLevel.add(IndexEntryItem("", 0, 0, ByteArray(0), subnodeVcn = vcn))
                    i = j
                }
            }

            currentLevel = nextLevel
            isLeafLevel = false
        }
    }

    private fun compareNtfsNames(a: String, b: String): Int {
        val minLen = minOf(a.length, b.length)
        for (i in 0 until minLen) {
            val ua = upCaseMap[a[i].code]
            val ub = upCaseMap[b[i].code]
            if (ua != ub) return ua.compareTo(ub)
        }
        return a.length.compareTo(b.length)
    }

    private fun buildBootSector(): ByteArray {
        val bpb = ByteArray(blockSize)
        bpb[0] = 0xEB.toByte()
        bpb[1] = 0x52
        bpb[2] = 0x90.toByte()
        ByteUtils.writeAscii(bpb, 3, "NTFS    ", 8)
        ByteUtils.writeU16LE(bpb, 11, blockSize)
        bpb[13] = sectorsPerCluster.toByte()
        bpb[21] = 0xF8.toByte()
        ByteUtils.writeU16LE(bpb, 24, 63)
        ByteUtils.writeU16LE(bpb, 26, 255)
        ByteUtils.writeI32LE(bpb, 28, hiddenSectors.toInt())
        ByteUtils.writeI32LE(bpb, 36, 0x00800080)
        ByteUtils.writeI64LE(bpb, 40, volumeNewestSectors)
        ByteUtils.writeI64LE(bpb, 48, mftStartLcn)
        ByteUtils.writeI64LE(bpb, 56, mftMirrLcn)
        bpb[64] = 0xF6.toByte() // -10 -> 2^10 = 1024 bytes per MFT record
        bpb[68] = 1             // 1 cluster (4096 bytes) per Index Block
        ByteUtils.writeI64LE(bpb, 72, volumeSerialNumber)
        bpb[510] = 0x55
        bpb[511] = 0xAA.toByte()
        return bpb
    }

    private sealed class AttrSpec {
        data class Resident(
            val type: Int,
            val name: String,
            val value: ByteArray
        ) : AttrSpec()

        data class NonResident(
            val type: Int,
            val name: String,
            val startLcn: Long,
            val clusterCount: Long,
            val dataSize: Long,
            val allocatedSize: Long,
            val initializedSize: Long,
            val sparse: Boolean = false
        ) : AttrSpec()
    }

    private fun buildMftRecord(
        recNo: Int,
        seqNo: Int,
        flags: Int,
        attrs: List<AttrSpec>
    ): ByteArray {
        val rec = ByteArray(MFT_RECORD_SIZE)
        ByteUtils.writeAscii(rec, 0, "FILE", 4)
        val usaOff = 48
        val usaCount = 1 + (MFT_RECORD_SIZE / SECTOR_SIZE_512)
        val attrOff = 56

        var pos = attrOff
        var instance = 0
        for (attr in attrs) {
            val ab = when (attr) {
                is AttrSpec.Resident -> encodeResidentAttr(attr.type, attr.name, attr.value, instance)
                is AttrSpec.NonResident -> encodeNonResidentAttr(
                    type = attr.type,
                    name = attr.name,
                    startLcn = attr.startLcn,
                    clusterCount = attr.clusterCount,
                    dataSize = attr.dataSize,
                    allocatedSize = attr.allocatedSize,
                    initializedSize = attr.initializedSize,
                    instance = instance,
                    sparse = attr.sparse
                )
            }
            System.arraycopy(ab, 0, rec, pos, ab.size)
            pos += ab.size
            instance++
        }

        ByteUtils.writeI32LE(rec, pos, -1) // 0xFFFFFFFF end marker
        val bytesUsed = pos + 8
        val hardLinks = if ((flags and FLAG_IN_USE) != 0) 1 else 0

        ByteUtils.writeU16LE(rec, 4, usaOff)
        ByteUtils.writeU16LE(rec, 6, usaCount)
        ByteUtils.writeI64LE(rec, 8, 0L)
        ByteUtils.writeU16LE(rec, 16, seqNo)
        ByteUtils.writeU16LE(rec, 18, hardLinks)
        ByteUtils.writeU16LE(rec, 20, attrOff)
        ByteUtils.writeU16LE(rec, 22, flags)
        ByteUtils.writeI32LE(rec, 24, bytesUsed)
        ByteUtils.writeI32LE(rec, 28, MFT_RECORD_SIZE)
        ByteUtils.writeI64LE(rec, 32, 0L)
        ByteUtils.writeU16LE(rec, 40, instance)
        ByteUtils.writeI32LE(rec, 44, recNo)

        applyUsaFixup(rec, usaOff, usaCount, DEFAULT_USN)
        return rec
    }

    private fun encodeResidentAttr(
        type: Int,
        name: String,
        value: ByteArray,
        instance: Int
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        val nameOff = 24
        val valOff = ((nameOff + nameBytes.size + 7) / 8) * 8
        val totalLen = ((valOff + value.size + 7) / 8) * 8
        val buf = ByteArray(totalLen)

        ByteUtils.writeI32LE(buf, 0, type)
        ByteUtils.writeI32LE(buf, 4, totalLen)
        buf[8] = 0 // Resident
        buf[9] = name.length.toByte()
        ByteUtils.writeU16LE(buf, 10, nameOff)
        ByteUtils.writeU16LE(buf, 12, 0)
        ByteUtils.writeU16LE(buf, 14, instance)
        ByteUtils.writeI32LE(buf, 16, value.size)
        ByteUtils.writeU16LE(buf, 20, valOff)
        buf[22] = if (type == 0x30 && name.isEmpty()) 1 else 0

        System.arraycopy(nameBytes, 0, buf, nameOff, nameBytes.size)
        System.arraycopy(value, 0, buf, valOff, value.size)
        return buf
    }

    private fun encodeNonResidentAttr(
        type: Int,
        name: String,
        startLcn: Long,
        clusterCount: Long,
        dataSize: Long,
        allocatedSize: Long,
        initializedSize: Long,
        instance: Int,
        sparse: Boolean
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        val nameOff = 64
        val runOff = ((nameOff + nameBytes.size + 7) / 8) * 8
        val runlist = encodeRunlist(startLcn, clusterCount, sparse)
        val totalLen = ((runOff + runlist.size + 7) / 8) * 8
        val buf = ByteArray(totalLen)

        val highestVcn = if (clusterCount > 0L) clusterCount - 1L else 0L
        ByteUtils.writeI32LE(buf, 0, type)
        ByteUtils.writeI32LE(buf, 4, totalLen)
        buf[8] = 1 // Non-resident
        buf[9] = name.length.toByte()
        ByteUtils.writeU16LE(buf, 10, nameOff)
        ByteUtils.writeU16LE(buf, 12, 0)
        ByteUtils.writeU16LE(buf, 14, instance)
        ByteUtils.writeI64LE(buf, 16, 0L)
        ByteUtils.writeI64LE(buf, 24, highestVcn)
        ByteUtils.writeU16LE(buf, 32, runOff)
        ByteUtils.writeU16LE(buf, 34, 0)
        ByteUtils.writeI32LE(buf, 36, 0)
        ByteUtils.writeI64LE(buf, 40, allocatedSize)
        ByteUtils.writeI64LE(buf, 48, dataSize)
        ByteUtils.writeI64LE(buf, 56, initializedSize)

        System.arraycopy(nameBytes, 0, buf, nameOff, nameBytes.size)
        System.arraycopy(runlist, 0, buf, runOff, runlist.size)
        return buf
    }

    private fun buildIndxBlock(vcn: Long, entriesBytes: ByteArray, isNode: Boolean): ByteArray {
        val buf = ByteArray(clusterSizeBytes)
        ByteUtils.writeAscii(buf, 0, "INDX", 4)
        val usaOff = 40
        val usaCount = 1 + (clusterSizeBytes / SECTOR_SIZE_512)
        val entriesOff = 40 // relative to offset 24 -> byte offset 64
        val totalIdxSize = entriesOff + entriesBytes.size
        val allocIdxSize = clusterSizeBytes - 24

        ByteUtils.writeU16LE(buf, 4, usaOff)
        ByteUtils.writeU16LE(buf, 6, usaCount)
        ByteUtils.writeI64LE(buf, 8, 0L)
        ByteUtils.writeI64LE(buf, 16, vcn)
        ByteUtils.writeI32LE(buf, 24, entriesOff)
        ByteUtils.writeI32LE(buf, 28, totalIdxSize)
        ByteUtils.writeI32LE(buf, 32, allocIdxSize)
        buf[36] = if (isNode) 1 else 0

        System.arraycopy(entriesBytes, 0, buf, 64, entriesBytes.size)
        applyUsaFixup(buf, usaOff, usaCount, DEFAULT_USN)
        return buf
    }

    companion object {
        private const val SECTOR_SIZE_512 = 512
        private const val MFT_RECORD_SIZE = 1024
        private const val MFT_RECORDS_PER_CLUSTER = 4
        private const val FIRST_USER_MFT_RECORD = 24
        private const val FLAG_IN_USE = 0x0001
        private const val FLAG_DIRECTORY = 0x0002
        private const val ATTR_FILE_ARCHIVE = 0x00000020
        private const val DEFAULT_USN = 2
        private const val MAX_RESIDENT_INDEX_ENTRIES_BYTES = 380
        private const val MAX_INDX_PAYLOAD_BYTES = 3400

        // Windows FILETIME (100-ns intervals since 1601-01-01): 2026-01-01 UTC
        private const val DEFAULT_NTFS_FILETIME = 134_117_856_000_000_000L

        private val RESIDENT_SECURITY_DESCRIPTOR = hexToBytes(
            "01000480480000005400000000000000140000000200340002000000000014009f011200010100000000000512000000" +
                "000018009f0112000102000000000005200000002002000001010000000000051200000001020000000000052000000020020000"
        )

        private val SDS_256_BYTES = hexToBytes(
            "f01203f80001000000000000000000007c00000001000480480000005800000000000000140000000200340002000000" +
                "000014008900120001010000000000051200000000001800890012000102000000000005200000002002000001020000" +
                "00000005200000002002000001020000000000052000000020020000000000005124b300010100008000000000000000" +
                "7c00000001000480480000005800000000000000140000000200340002000000000014009f0112000101000000000005" +
                "12000000000018009f011200010200000000000520000000200200000102000000000005200000002002000001020000" +
                "00000005200000002002000000000000"
        )

        private val SDH_INDEX_ROOT_BYTES = hexToBytes(
            "000000001200000000100000010000001000000080000000800000000000000018001400000000003000080000000000" +
                "5124b300010100005124b3000101000080000000000000007c0000004900490018001400000000003000080000000000" +
                "f01203f800010000f01203f80001000000000000000000007c0000004900490000000000000000001000000002000000"
        )

        private val SII_INDEX_ROOT_BYTES = hexToBytes(
            "000000001000000000100000010000001000000070000000700000000000000014001400000000002800040000000000" +
                "00010000f01203f80001000000000000000000007c00000014001400000000002800040000000000010100005124b300" +
                "0101000080000000000000007c00000000000000000000001000000002000000"
        )

        private fun buildAttrDefTable(): ByteArray {
            data class Def(val name: String, val type: Int, val flags: Int, val minSz: Long, val maxSz: Long)
            val defs = listOf(
                Def("\$STANDARD_INFORMATION", 0x10, 0x40, 48L, 72L),
                Def("\$ATTRIBUTE_LIST", 0x20, 0x80, 0L, -1L),
                Def("\$FILE_NAME", 0x30, 0x42, 68L, 578L),
                Def("\$OBJECT_ID", 0x40, 0x40, 0L, 256L),
                Def("\$SECURITY_DESCRIPTOR", 0x50, 0x80, 0L, -1L),
                Def("\$VOLUME_NAME", 0x60, 0x40, 2L, 256L),
                Def("\$VOLUME_INFORMATION", 0x70, 0x40, 12L, 12L),
                Def("\$DATA", 0x80, 0x00, 0L, -1L),
                Def("\$INDEX_ROOT", 0x90, 0x40, 0L, -1L),
                Def("\$INDEX_ALLOCATION", 0xA0, 0x80, 0L, -1L),
                Def("\$BITMAP", 0xB0, 0x80, 0L, -1L),
                Def("\$REPARSE_POINT", 0xC0, 0x80, 0L, 16384L),
                Def("\$EA_INFORMATION", 0xD0, 0x40, 8L, 8L),
                Def("\$EA", 0xE0, 0x00, 0L, 65536L),
                Def("\$LOGGED_UTILITY_STREAM", 0x100, 0x80, 0L, 65536L)
            )
            val out = ByteArray(2560)
            for ((idx, d) in defs.withIndex()) {
                val off = idx * 160
                val nb = d.name.toByteArray(Charsets.UTF_16LE)
                System.arraycopy(nb, 0, out, off, nb.size)
                ByteUtils.writeI32LE(out, off + 128, d.type)
                ByteUtils.writeI32LE(out, off + 132, 0)
                ByteUtils.writeI32LE(out, off + 136, 0)
                ByteUtils.writeI32LE(out, off + 140, d.flags)
                ByteUtils.writeI64LE(out, off + 144, d.minSz)
                ByteUtils.writeI64LE(out, off + 152, d.maxSz)
            }
            return out
        }

        private fun buildStandardInfo(fileAttrs: Int, securityId: Int, size48: Boolean): ByteArray {
            val buf = ByteArray(if (size48) 48 else 72)
            ByteUtils.writeI64LE(buf, 0, DEFAULT_NTFS_FILETIME)
            ByteUtils.writeI64LE(buf, 8, DEFAULT_NTFS_FILETIME)
            ByteUtils.writeI64LE(buf, 16, DEFAULT_NTFS_FILETIME)
            ByteUtils.writeI64LE(buf, 24, DEFAULT_NTFS_FILETIME)
            ByteUtils.writeI32LE(buf, 32, fileAttrs)
            if (!size48) {
                ByteUtils.writeI32LE(buf, 52, securityId)
            }
            return buf
        }

        private fun buildFileNameValue(
            parentMft: Int,
            parentSeq: Int,
            name: String,
            allocatedSize: Long,
            dataSize: Long,
            fileAttrs: Int,
            isDirectory: Boolean = false
        ): ByteArray {
            val nameBytes = name.toByteArray(Charsets.UTF_16LE)
            val buf = ByteArray(66 + nameBytes.size)
            val parentRef = ((parentSeq.toLong() and 0xFFFFL) shl 48) or (parentMft.toLong() and 0xFFFFFFFFFFFFL)
            val flags = fileAttrs or (if (isDirectory) 0x10000000 else 0)

            ByteUtils.writeI64LE(buf, 0, parentRef)
            ByteUtils.writeI64LE(buf, 8, DEFAULT_NTFS_FILETIME)
            ByteUtils.writeI64LE(buf, 16, DEFAULT_NTFS_FILETIME)
            ByteUtils.writeI64LE(buf, 24, DEFAULT_NTFS_FILETIME)
            ByteUtils.writeI64LE(buf, 32, DEFAULT_NTFS_FILETIME)
            ByteUtils.writeI64LE(buf, 40, allocatedSize)
            ByteUtils.writeI64LE(buf, 48, dataSize)
            ByteUtils.writeI32LE(buf, 56, flags)
            ByteUtils.writeI32LE(buf, 60, 0)
            buf[64] = name.length.toByte()
            buf[65] = 3 // Win32 & DOS namespace
            System.arraycopy(nameBytes, 0, buf, 66, nameBytes.size)
            return buf
        }

        private fun buildIndexEntry(
            mftNo: Int,
            seqNo: Int,
            fnBytes: ByteArray,
            subnodeVcn: Long?
        ): ByteArray {
            val ref = ((seqNo.toLong() and 0xFFFFL) shl 48) or (mftNo.toLong() and 0xFFFFFFFFFFFFL)
            val baseLen = ((16 + fnBytes.size + 7) / 8) * 8
            val totalLen = baseLen + (if (subnodeVcn != null) 8 else 0)
            val flags = if (subnodeVcn != null) 0x01 else 0x00
            val buf = ByteArray(totalLen)

            ByteUtils.writeI64LE(buf, 0, ref)
            ByteUtils.writeU16LE(buf, 8, totalLen)
            ByteUtils.writeU16LE(buf, 10, fnBytes.size)
            ByteUtils.writeU16LE(buf, 12, flags)
            System.arraycopy(fnBytes, 0, buf, 16, fnBytes.size)
            if (subnodeVcn != null) {
                ByteUtils.writeI64LE(buf, totalLen - 8, subnodeVcn)
            }
            return buf
        }

        private fun buildEndIndexEntry(subnodeVcn: Long?): ByteArray {
            return if (subnodeVcn == null) {
                ByteArray(16).apply {
                    ByteUtils.writeU16LE(this, 8, 16)
                    ByteUtils.writeU16LE(this, 12, 0x02) // INDEX_ENTRY_END
                }
            } else {
                ByteArray(24).apply {
                    ByteUtils.writeU16LE(this, 8, 24)
                    ByteUtils.writeU16LE(this, 12, 0x03) // INDEX_ENTRY_NODE | INDEX_ENTRY_END
                    ByteUtils.writeI64LE(this, 16, subnodeVcn)
                }
            }
        }

        private fun buildIndexRoot(
            attrType: Int,
            collationRule: Int,
            entriesBytes: ByteArray,
            isLarge: Boolean
        ): ByteArray {
            val out = ByteArray(32 + entriesBytes.size)
            val totalIdxSize = 16 + entriesBytes.size
            ByteUtils.writeI32LE(out, 0, attrType)
            ByteUtils.writeI32LE(out, 4, collationRule)
            ByteUtils.writeI32LE(out, 8, 4096)
            out[12] = 1
            ByteUtils.writeI32LE(out, 16, 16)
            ByteUtils.writeI32LE(out, 20, totalIdxSize)
            ByteUtils.writeI32LE(out, 24, totalIdxSize)
            out[28] = if (isLarge) 1 else 0
            System.arraycopy(entriesBytes, 0, out, 32, entriesBytes.size)
            return out
        }

        private fun encodeRunlist(startLcn: Long, count: Long, sparse: Boolean): ByteArray {
            if (count <= 0L) return byteArrayOf(0)
            val countBytes = minUnsignedBytesLE(count)
            if (sparse) {
                val out = ByteArray(1 + countBytes.size + 1)
                out[0] = (countBytes.size and 0x0F).toByte()
                System.arraycopy(countBytes, 0, out, 1, countBytes.size)
                return out
            }
            val lcnBytes = minSignedBytesLE(startLcn)
            val out = ByteArray(1 + countBytes.size + lcnBytes.size + 1)
            out[0] = ((lcnBytes.size shl 4) or (countBytes.size and 0x0F)).toByte()
            System.arraycopy(countBytes, 0, out, 1, countBytes.size)
            System.arraycopy(lcnBytes, 0, out, 1 + countBytes.size, lcnBytes.size)
            return out
        }

        private fun minUnsignedBytesLE(value: Long): ByteArray {
            val full = ByteArray(8)
            ByteUtils.writeI64LE(full, 0, value)
            var len = 8
            while (len > 1 && full[len - 1].toInt() == 0) {
                len--
            }
            return full.copyOf(len)
        }

        private fun minSignedBytesLE(value: Long): ByteArray {
            val full = ByteArray(8)
            ByteUtils.writeI64LE(full, 0, value)
            var len = 8
            while (len > 1) {
                val top = full[len - 1].toInt() and 0xFF
                val nextSign = full[len - 2].toInt() and 0x80
                if ((top == 0x00 && nextSign == 0) || (top == 0xFF && nextSign != 0)) {
                    len--
                } else {
                    break
                }
            }
            return full.copyOf(len)
        }

        private fun applyUsaFixup(buf: ByteArray, usaOff: Int, usaCount: Int, usn: Int) {
            ByteUtils.writeU16LE(buf, usaOff, usn)
            for (i in 1 until usaCount) {
                val sectorEnd = i * SECTOR_SIZE_512 - 2
                buf[usaOff + i * 2] = buf[sectorEnd]
                buf[usaOff + i * 2 + 1] = buf[sectorEnd + 1]
                ByteUtils.writeU16LE(buf, sectorEnd, usn)
            }
        }

        private fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }
    }
}
