package com.usbbootable.creator.core.image

import com.usbbootable.creator.core.util.ByteUtils
import java.io.IOException

/**
 * Pure-Kotlin ECMA-167 / OSTA UDF (1.02 - 2.01) reader for Microsoft Windows 10/11 and UDF ISOs.
 * Supports > 4 GiB files (e.g. sources/install.wim), inline data, short/long/extended allocation
 * descriptors, continuation extents (AED), sparse extents, and OSTA CS0 8-bit & 16-bit Unicode.
 */
class UdfReader private constructor(
    private val source: ImageSource,
    private val blockSize: Int,
    private val partitionMapToStartSector: Map<Int, Long>,
    override val volumeLabel: String,
    override val entries: List<ImageEntry>
) : ImageFileSystem {

    override val formatName: String = "UDF"

    private data class UdfExtent(
        val fileOffsetBytes: Long, // -1 means sparse zeros, -2 means inline data
        val lengthBytes: Long,
        val inlineBytes: ByteArray? = null
    )

    private data class UdfFileHandle(
        val sizeBytes: Long,
        val extents: List<UdfExtent>
    )

    override fun streamFile(
        entry: ImageEntry,
        buffer: ByteArray,
        consumer: (data: ByteArray, length: Int) -> Unit
    ) {
        require(!entry.isDirectory) { "Cannot stream directory entry: ${entry.path}" }
        val handle = entry.opaqueHandle as? UdfFileHandle
            ?: throw IOException("Invalid UDF handle for ${entry.path}")

        var remaining = handle.sizeBytes
        if (remaining <= 0L) return

        for (extent in handle.extents) {
            if (remaining <= 0L) break
            var extentRem = minOf(remaining, extent.lengthBytes)
            when {
                extent.inlineBytes != null -> {
                    var pos = 0
                    while (extentRem > 0L) {
                        val chunk = minOf(extentRem, buffer.size.toLong()).toInt()
                        System.arraycopy(extent.inlineBytes, pos, buffer, 0, chunk)
                        consumer(buffer, chunk)
                        pos += chunk
                        extentRem -= chunk
                        remaining -= chunk
                    }
                }
                extent.fileOffsetBytes < 0L -> {
                    // Sparse / unrecorded extent -> emit zero bytes
                    while (extentRem > 0L) {
                        val chunk = minOf(extentRem, buffer.size.toLong()).toInt()
                        buffer.fill(0, 0, chunk)
                        consumer(buffer, chunk)
                        extentRem -= chunk
                        remaining -= chunk
                    }
                }
                else -> {
                    var diskPos = extent.fileOffsetBytes
                    while (extentRem > 0L) {
                        val chunk = minOf(extentRem, buffer.size.toLong()).toInt()
                        source.readExact(diskPos, buffer, 0, chunk)
                        consumer(buffer, chunk)
                        diskPos += chunk
                        extentRem -= chunk
                        remaining -= chunk
                    }
                }
            }
        }
        if (remaining > 0L) {
            // Pad tail with zeros if recorded extents ended before informationLength
            while (remaining > 0L) {
                val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                buffer.fill(0, 0, chunk)
                consumer(buffer, chunk)
                remaining -= chunk
            }
        }
    }

    companion object {
        private const val SECTOR_SIZE = 2048

        private const val TAG_AVDP = 2
        private const val TAG_PARTITION_DESC = 5
        private const val TAG_LOGICAL_VOL_DESC = 6
        private const val TAG_TERMINATING_DESC = 8
        private const val TAG_FILE_SET_DESC = 256
        private const val TAG_FILE_ID_DESC = 257
        private const val TAG_ALLOC_EXTENT_DESC = 258
        private const val TAG_FILE_ENTRY = 261
        private const val TAG_EXTENDED_FILE_ENTRY = 266

        fun hasUdfSignature(source: ImageSource): Boolean {
            if (source.sizeBytes < 18L * SECTOR_SIZE) return false
            val buf = ByteArray(SECTOR_SIZE)
            var foundBea = false
            var foundNsr = false
            for (sector in 16..64) {
                val pos = sector.toLong() * SECTOR_SIZE
                if (pos + SECTOR_SIZE > source.sizeBytes) break
                source.readExact(pos, buf, 0, SECTOR_SIZE)
                val id = String(buf, 1, 5, Charsets.US_ASCII)
                when (id) {
                    "BEA01" -> foundBea = true
                    "NSR02", "NSR03" -> foundNsr = true
                    "TEA01" -> break
                }
            }
            return foundBea && foundNsr
        }

        fun tryParse(source: ImageSource): UdfReader? {
            return try {
                parse(source)
            } catch (_: Exception) {
                null
            }
        }

        @Throws(IOException::class)
        fun parse(source: ImageSource): UdfReader {
            val totalSectors = source.sizeBytes / SECTOR_SIZE
            if (totalSectors < 257L) {
                throw IOException("Image too small to contain a valid UDF anchor descriptor.")
            }

            // 1. Locate Anchor Volume Descriptor Pointer (AVDP)
            val avdpCandidates = listOf(256L, totalSectors - 256L, totalSectors - 1L).filter {
                it in 0L until totalSectors
            }
            val sectorBuf = ByteArray(SECTOR_SIZE)
            var avdpFound = false
            for (candidate in avdpCandidates) {
                source.readExact(candidate * SECTOR_SIZE, sectorBuf, 0, SECTOR_SIZE)
                if (ByteUtils.readU16LE(sectorBuf, 0) == TAG_AVDP) {
                    avdpFound = true
                    break
                }
            }
            if (!avdpFound) {
                throw IOException("UDF Anchor Volume Descriptor Pointer (AVDP) not found.")
            }

            val vdsLen = ByteUtils.readU32LE(sectorBuf, 16)
            val vdsLoc = ByteUtils.readU32LE(sectorBuf, 20)
            val vdsSectors = ((vdsLen + SECTOR_SIZE - 1) / SECTOR_SIZE).coerceAtMost(256L)

            // 2. Parse Main Volume Descriptor Sequence
            val partNumToStart = mutableMapOf<Int, Long>()
            var logicalBlockSize = SECTOR_SIZE
            var volumeLabel = "UDF_VOLUME"
            var fsdLbn = -1L
            var fsdPartRef = 0
            val partRefToPartNum = mutableMapOf<Int, Int>()

            for (i in 0L until vdsSectors) {
                val secPos = (vdsLoc + i) * SECTOR_SIZE
                if (secPos + SECTOR_SIZE > source.sizeBytes) break
                source.readExact(secPos, sectorBuf, 0, SECTOR_SIZE)
                when (ByteUtils.readU16LE(sectorBuf, 0)) {
                    TAG_PARTITION_DESC -> {
                        val partNum = ByteUtils.readU16LE(sectorBuf, 22)
                        val startLoc = ByteUtils.readU32LE(sectorBuf, 188)
                        partNumToStart[partNum] = startLoc
                    }
                    TAG_LOGICAL_VOL_DESC -> {
                        volumeLabel = decodeDString(sectorBuf, 84, 128).ifBlank { "UDF_VOLUME" }
                        val lbs = ByteUtils.readU32LE(sectorBuf, 212).toInt()
                        if (lbs in 512..65536) logicalBlockSize = lbs
                        fsdLbn = ByteUtils.readU32LE(sectorBuf, 248 + 4)
                        fsdPartRef = ByteUtils.readU16LE(sectorBuf, 248 + 8)

                        val mapTableLen = ByteUtils.readU32LE(sectorBuf, 264).toInt()
                        val numMaps = ByteUtils.readU32LE(sectorBuf, 268).toInt()
                        var mapOff = 440
                        var refIdx = 0
                        while (refIdx < numMaps && mapOff + 2 <= 440 + mapTableLen && mapOff + 2 <= SECTOR_SIZE) {
                            val mapType = sectorBuf[mapOff].toInt() and 0xFF
                            val mapLen = sectorBuf[mapOff + 1].toInt() and 0xFF
                            if (mapLen <= 0 || mapOff + mapLen > SECTOR_SIZE) break
                            if (mapType == 1 && mapLen >= 6) {
                                val pNum = ByteUtils.readU16LE(sectorBuf, mapOff + 4)
                                partRefToPartNum[refIdx] = pNum
                            }
                            mapOff += mapLen
                            refIdx++
                        }
                    }
                    TAG_TERMINATING_DESC -> break
                }
            }

            if (partNumToStart.isEmpty() || fsdLbn < 0L) {
                throw IOException("Incomplete UDF Volume Descriptor Sequence.")
            }

            val partRefToStartSector = mutableMapOf<Int, Long>()
            if (partRefToPartNum.isEmpty()) {
                val firstStart = partNumToStart.values.first()
                partRefToStartSector[0] = firstStart
            } else {
                for ((ref, pNum) in partRefToPartNum) {
                    val start = partNumToStart[pNum] ?: partNumToStart.values.first()
                    partRefToStartSector[ref] = start
                }
            }

            // 3. Read File Set Descriptor (FSD)
            val fsdSector = (partRefToStartSector[fsdPartRef] ?: partNumToStart.values.first()) + fsdLbn
            val fsdBuf = ByteArray(logicalBlockSize)
            source.readExact(fsdSector * logicalBlockSize.toLong(), fsdBuf, 0, logicalBlockSize)
            if (ByteUtils.readU16LE(fsdBuf, 0) != TAG_FILE_SET_DESC) {
                throw IOException("Invalid UDF File Set Descriptor tag.")
            }

            val rootIcbLbn = ByteUtils.readU32LE(fsdBuf, 400 + 4)
            val rootIcbPartRef = ByteUtils.readU16LE(fsdBuf, 400 + 8)

            // 4. Traverse Directory Tree iteratively (no stack overflow on deep trees)
            val entries = mutableListOf<ImageEntry>()
            val visitedIcbs = HashSet<Long>()
            val dirQueue = ArrayDeque<Triple<String, Long, Int>>()
            dirQueue.add(Triple("", rootIcbLbn, rootIcbPartRef))

            while (dirQueue.isNotEmpty()) {
                val (parentPath, dirLbn, dirPartRef) = dirQueue.removeFirst()
                val icbKey = (dirPartRef.toLong() shl 40) or dirLbn
                if (!visitedIcbs.add(icbKey)) continue

                val dirFe = readFileEntry(source, logicalBlockSize, partRefToStartSector, dirLbn, dirPartRef)
                val dirBytes = readAllExtentsToByteArray(source, dirFe)

                var pos = 0
                while (pos + 38 <= dirBytes.size) {
                    val tagId = ByteUtils.readU16LE(dirBytes, pos)
                    if (tagId != TAG_FILE_ID_DESC) break

                    val characteristics = dirBytes[pos + 18].toInt() and 0xFF
                    val lenFi = dirBytes[pos + 19].toInt() and 0xFF
                    val childLbn = ByteUtils.readU32LE(dirBytes, pos + 20 + 4)
                    val childPartRef = ByteUtils.readU16LE(dirBytes, pos + 20 + 8)
                    val lenIu = ByteUtils.readU16LE(dirBytes, pos + 36)

                    val recordSize = ((38 + lenIu + lenFi + 3) / 4) * 4
                    if (pos + recordSize > dirBytes.size) break

                    val isDeleted = (characteristics and 0x04) != 0
                    val isParent = (characteristics and 0x08) != 0
                    val isDir = (characteristics and 0x02) != 0

                    if (!isDeleted && !isParent && lenFi > 0) {
                        val name = decodeCs0(dirBytes, pos + 38 + lenIu, lenFi)
                        if (name.isNotEmpty() && name != "." && name != "..") {
                            val fullPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                            if (isDir) {
                                entries.add(
                                    ImageEntry(
                                        path = fullPath,
                                        name = name,
                                        isDirectory = true,
                                        sizeBytes = 0L,
                                        opaqueHandle = Unit
                                    )
                                )
                                dirQueue.add(Triple(fullPath, childLbn, childPartRef))
                            } else {
                                val fileFe = readFileEntry(
                                    source,
                                    logicalBlockSize,
                                    partRefToStartSector,
                                    childLbn,
                                    childPartRef
                                )
                                entries.add(
                                    ImageEntry(
                                        path = fullPath,
                                        name = name,
                                        isDirectory = false,
                                        sizeBytes = fileFe.sizeBytes,
                                        opaqueHandle = fileFe
                                    )
                                )
                            }
                        }
                    }

                    pos += recordSize
                }
            }

            return UdfReader(
                source = source,
                blockSize = logicalBlockSize,
                partitionMapToStartSector = partRefToStartSector,
                volumeLabel = volumeLabel,
                entries = entries
            )
        }

        private fun readFileEntry(
            source: ImageSource,
            blockSize: Int,
            partRefToStart: Map<Int, Long>,
            lbn: Long,
            partRef: Int
        ): UdfFileHandle {
            val partStart = partRefToStart[partRef] ?: partRefToStart.values.first()
            val feBuf = ByteArray(blockSize)
            source.readExact((partStart + lbn) * blockSize.toLong(), feBuf, 0, blockSize)

            val tagId = ByteUtils.readU16LE(feBuf, 0)
            if (tagId != TAG_FILE_ENTRY && tagId != TAG_EXTENDED_FILE_ENTRY) {
                throw IOException("Expected UDF File Entry (261/266) at LBN=$lbn, got tag=$tagId")
            }

            val icbFlags = ByteUtils.readU16LE(feBuf, 34)
            val adType = icbFlags and 0x07
            val infoLength = ByteUtils.readI64LE(feBuf, 56)

            val lenEa: Int
            val lenAd: Int
            val adStart: Int
            if (tagId == TAG_FILE_ENTRY) {
                lenEa = ByteUtils.readU32LE(feBuf, 168).toInt()
                lenAd = ByteUtils.readU32LE(feBuf, 172).toInt()
                adStart = 176 + lenEa
            } else {
                lenEa = ByteUtils.readU32LE(feBuf, 208).toInt()
                lenAd = ByteUtils.readU32LE(feBuf, 212).toInt()
                adStart = 216 + lenEa
            }

            if (adStart < 0 || adStart + lenAd > feBuf.size) {
                throw IOException("UDF allocation descriptor bounds out of block: start=$adStart len=$lenAd")
            }

            val extents = mutableListOf<UdfExtent>()
            if (adType == 3) {
                // Inline data inside FE/EFE
                val copyLen = minOf(lenAd.toLong(), infoLength).toInt().coerceAtLeast(0)
                val inline = feBuf.copyOfRange(adStart, adStart + copyLen)
                extents.add(UdfExtent(fileOffsetBytes = -2L, lengthBytes = copyLen.toLong(), inlineBytes = inline))
            } else {
                parseAllocationDescriptors(
                    source = source,
                    blockSize = blockSize,
                    partRefToStart = partRefToStart,
                    defaultPartRef = partRef,
                    adType = adType,
                    buf = feBuf,
                    startOffset = adStart,
                    length = lenAd,
                    out = extents,
                    depth = 0
                )
            }

            return UdfFileHandle(sizeBytes = infoLength, extents = extents)
        }

        private fun parseAllocationDescriptors(
            source: ImageSource,
            blockSize: Int,
            partRefToStart: Map<Int, Long>,
            defaultPartRef: Int,
            adType: Int,
            buf: ByteArray,
            startOffset: Int,
            length: Int,
            out: MutableList<UdfExtent>,
            depth: Int
        ) {
            if (depth > 64) throw IOException("UDF allocation extent continuation chain too deep.")

            val entrySize = when (adType) {
                0 -> 8  // short_ad
                1 -> 16 // long_ad
                2 -> 20 // ext_ad
                else -> throw IOException("Unsupported UDF allocation descriptor type: $adType")
            }

            var pos = startOffset
            val end = startOffset + length
            while (pos + entrySize <= end) {
                val rawLen = ByteUtils.readU32LE(buf, pos)
                val extType = ((rawLen ushr 30) and 0x03L).toInt()
                val byteLen = rawLen and 0x3FFFFFFFL
                if (byteLen == 0L) break

                val extLbn: Long
                val extPartRef: Int
                when (adType) {
                    0 -> {
                        extLbn = ByteUtils.readU32LE(buf, pos + 4)
                        extPartRef = defaultPartRef
                    }
                    1 -> {
                        extLbn = ByteUtils.readU32LE(buf, pos + 4)
                        extPartRef = ByteUtils.readU16LE(buf, pos + 8)
                    }
                    else -> {
                        // ext_ad: extentLen(4), recordedLen(4), infoLen(4), loc.lbn(4), loc.partRef(2)
                        extLbn = ByteUtils.readU32LE(buf, pos + 12)
                        extPartRef = ByteUtils.readU16LE(buf, pos + 16)
                    }
                }

                when (extType) {
                    0 -> {
                        val partStart = partRefToStart[extPartRef] ?: partRefToStart.values.first()
                        val diskOffset = (partStart + extLbn) * blockSize.toLong()
                        out.add(UdfExtent(fileOffsetBytes = diskOffset, lengthBytes = byteLen))
                    }
                    1, 2 -> {
                        // Allocated-not-recorded or unallocated (sparse zero fill)
                        out.add(UdfExtent(fileOffsetBytes = -1L, lengthBytes = byteLen))
                    }
                    3 -> {
                        // Continuation Allocation Extent Descriptor (AED Tag 258)
                        val partStart = partRefToStart[extPartRef] ?: partRefToStart.values.first()
                        val aedSize = minOf(byteLen, 64L * 1024L).toInt().coerceAtLeast(blockSize)
                        val aedBuf = ByteArray(aedSize)
                        source.readExact((partStart + extLbn) * blockSize.toLong(), aedBuf, 0, aedSize)
                        val aedTag = ByteUtils.readU16LE(aedBuf, 0)
                        if (aedTag == TAG_ALLOC_EXTENT_DESC) {
                            val contLen = ByteUtils.readU32LE(aedBuf, 20).toInt()
                            if (24 + contLen <= aedBuf.size) {
                                parseAllocationDescriptors(
                                    source = source,
                                    blockSize = blockSize,
                                    partRefToStart = partRefToStart,
                                    defaultPartRef = extPartRef,
                                    adType = adType,
                                    buf = aedBuf,
                                    startOffset = 24,
                                    length = contLen,
                                    out = out,
                                    depth = depth + 1
                                )
                            }
                        }
                        return
                    }
                }
                pos += entrySize
            }
        }

        private fun readAllExtentsToByteArray(source: ImageSource, handle: UdfFileHandle): ByteArray {
            val total = handle.sizeBytes.coerceAtMost(32L * 1024L * 1024L).toInt()
            val out = ByteArray(total)
            var outPos = 0
            for (ext in handle.extents) {
                if (outPos >= total) break
                val copyLen = minOf(ext.lengthBytes, (total - outPos).toLong()).toInt()
                when {
                    ext.inlineBytes != null -> {
                        System.arraycopy(ext.inlineBytes, 0, out, outPos, minOf(copyLen, ext.inlineBytes.size))
                    }
                    ext.fileOffsetBytes >= 0L -> {
                        source.readExact(ext.fileOffsetBytes, out, outPos, copyLen)
                    }
                }
                outPos += copyLen
            }
            return out
        }

        private fun decodeDString(buf: ByteArray, offset: Int, fieldLen: Int): String {
            val strLen = buf[offset + fieldLen - 1].toInt() and 0xFF
            if (strLen <= 1 || strLen >= fieldLen) return ""
            return decodeCs0(buf, offset, strLen).trim()
        }

        private fun decodeCs0(buf: ByteArray, offset: Int, length: Int): String {
            if (length <= 1) return ""
            val compressionId = buf[offset].toInt() and 0xFF
            return when (compressionId) {
                8 -> {
                    val chars = CharArray(length - 1)
                    for (i in 0 until length - 1) {
                        chars[i] = (buf[offset + 1 + i].toInt() and 0xFF).toChar()
                    }
                    String(chars)
                }
                16 -> {
                    val charCount = (length - 1) / 2
                    val chars = CharArray(charCount)
                    for (i in 0 until charCount) {
                        val hi = buf[offset + 1 + i * 2].toInt() and 0xFF
                        val lo = buf[offset + 2 + i * 2].toInt() and 0xFF
                        chars[i] = ((hi shl 8) or lo).toChar()
                    }
                    String(chars)
                }
                else -> String(buf, offset, length, Charsets.ISO_8859_1)
            }
        }
    }
}
