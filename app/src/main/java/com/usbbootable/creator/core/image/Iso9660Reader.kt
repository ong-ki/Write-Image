package com.usbbootable.creator.core.image

import com.usbbootable.creator.core.util.ByteUtils
import java.io.IOException

/**
 * Pure-Kotlin ISO-9660 + Joliet (UCS-2BE) image filesystem reader.
 * Supports multi-extent ISO-9660 Level 3 files (> 4 GiB) as well as standard optical ISOs.
 */
class Iso9660Reader private constructor(
    private val source: ImageSource,
    private val blockSize: Int,
    override val formatName: String,
    override val volumeLabel: String,
    override val entries: List<ImageEntry>
) : ImageFileSystem {

    private data class IsoExtent(val lba: Long, val lengthBytes: Long)
    private data class IsoFileHandle(val extents: List<IsoExtent>)

    override fun streamFile(
        entry: ImageEntry,
        buffer: ByteArray,
        consumer: (data: ByteArray, length: Int) -> Unit
    ) {
        require(!entry.isDirectory) { "Cannot stream directory entry: ${entry.path}" }
        require(buffer.isNotEmpty()) { "Stream buffer must not be empty" }
        val handle = entry.opaqueHandle as? IsoFileHandle
            ?: throw IOException("Invalid ISO-9660 handle for ${entry.path}")

        for (ext in handle.extents) {
            var diskPos = ext.lba * blockSize.toLong()
            var remaining = ext.lengthBytes
            while (remaining > 0L) {
                val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                source.readExact(diskPos, buffer, 0, chunk)
                consumer(buffer, chunk)
                diskPos += chunk
                remaining -= chunk
            }
        }
    }

    companion object {
        private const val SECTOR_SIZE = 2048
        private const val MAX_DIRECTORY_BYTES = 16L * 1024L * 1024L
        private const val MAX_ENTRIES = 100_000

        fun tryParse(source: ImageSource): Iso9660Reader? {
            return try {
                parse(source)
            } catch (_: Exception) {
                null
            }
        }

        @Throws(IOException::class)
        fun parse(source: ImageSource): Iso9660Reader {
            if (source.sizeBytes < 17L * SECTOR_SIZE) {
                throw IOException("Image too small for ISO-9660 Primary Volume Descriptor.")
            }

            val sec = ByteArray(SECTOR_SIZE)
            var pvdBytes: ByteArray? = null
            var jolietBytes: ByteArray? = null

            for (sector in 16..48) {
                val pos = sector.toLong() * SECTOR_SIZE
                if (pos + SECTOR_SIZE > source.sizeBytes) break
                source.readExact(pos, sec, 0, SECTOR_SIZE)
                val sig = String(sec, 1, 5, Charsets.US_ASCII)
                if (sig != "CD001") break
                when (sec[0].toInt() and 0xFF) {
                    1 -> if (pvdBytes == null) pvdBytes = sec.copyOf()
                    2 -> {
                        // Joliet UCS-2 Level 1/2/3 escape sequences at offset 88
                        val b0 = sec[88].toInt() and 0xFF
                        val b1 = sec[89].toInt() and 0xFF
                        val b2 = sec[90].toInt() and 0xFF
                        if (b0 == 0x25 && b1 == 0x2F && (b2 == 0x40 || b2 == 0x43 || b2 == 0x45)) {
                            jolietBytes = sec.copyOf()
                        }
                    }
                    255 -> break
                }
            }

            val activeVd = jolietBytes ?: pvdBytes
                ?: throw IOException("ISO-9660 Primary Volume Descriptor not found.")
            val isJoliet = jolietBytes != null

            val blockSize = ByteUtils.readU16LE(activeVd, 128).takeIf { it in 512..65536 } ?: SECTOR_SIZE
            val volumeLabel = if (isJoliet) {
                decodeUtf16Be(activeVd, 40, 32).trim()
            } else {
                String(activeVd, 40, 32, Charsets.US_ASCII).trim()
            }.ifBlank { "ISO9660_VOLUME" }

            val rootLba = ByteUtils.readU32LE(activeVd, 156 + 2)
            val rootLen = ByteUtils.readU32LE(activeVd, 156 + 10)

            val entries = mutableListOf<ImageEntry>()
            // Track active ancestor LBAs per branch so cycles are rejected while deduplicated sibling dirs still traverse
            data class DirQueueItem(
                val parentPath: String,
                val dirLba: Long,
                val dirLen: Long,
                val ancestors: Set<Long>
            )
            val queue = ArrayDeque<DirQueueItem>()
            queue.add(DirQueueItem("", rootLba, rootLen, setOf(rootLba)))

            while (queue.isNotEmpty()) {
                val (parentPath, dirLba, dirLen, ancestors) = queue.removeFirst()
                val dirOffset = dirLba * blockSize.toLong()
                if (dirLba < 0L || dirOffset < 0L || dirOffset >= source.sizeBytes) continue
                val available = source.sizeBytes - dirOffset
                if (dirLen <= 0L) continue
                if (dirLen > MAX_DIRECTORY_BYTES || dirLen > available) {
                    throw IOException("ISO-9660 directory bounds invalid at LBA $dirLba (len=$dirLen, available=$available)")
                }
                val safeLen = dirLen.toInt()

                val dirData = ByteArray(safeLen)
                source.readExact(dirOffset, dirData, 0, safeLen)

                var pos = 0
                val pendingMultiExtents = LinkedHashMap<String, MutableList<IsoExtent>>()
                val pendingNames = HashMap<String, String>()

                while (pos < dirData.size) {
                    val recLen = dirData[pos].toInt() and 0xFF
                    if (recLen == 0) {
                        // Advance to next sector boundary
                        val nextSector = ((pos / blockSize) + 1) * blockSize
                        if (nextSector <= pos) break
                        pos = nextSector
                        continue
                    }
                    if (pos + recLen > dirData.size || recLen < 33) break

                    val extLba = ByteUtils.readU32LE(dirData, pos + 2)
                    val dataLen = ByteUtils.readU32LE(dirData, pos + 10)
                    val flags = dirData[pos + 25].toInt() and 0xFF
                    val idLen = dirData[pos + 32].toInt() and 0xFF

                    if (idLen > 0 && pos + 33 + idLen <= pos + recLen) {
                        val isSpecialDot = idLen == 1 && (dirData[pos + 33].toInt() == 0 || dirData[pos + 33].toInt() == 1)
                        if (!isSpecialDot) {
                            val rawName = if (isJoliet) {
                                decodeUtf16Be(dirData, pos + 33, idLen)
                            } else {
                                String(dirData, pos + 33, idLen, Charsets.ISO_8859_1)
                            }
                            val cleanName = cleanIsoName(rawName, isJoliet)
                            if (cleanName.isNotEmpty() && isSafeEntryName(cleanName)) {
                                val fullPath = if (parentPath.isEmpty()) cleanName else "$parentPath/$cleanName"
                                val isDir = (flags and 0x02) != 0
                                val isMultiExtent = (flags and 0x80) != 0

                                if (isDir) {
                                    if (extLba !in ancestors) {
                                        if (entries.size >= MAX_ENTRIES) {
                                            throw IOException("ISO-9660 entry limit exceeded ($MAX_ENTRIES entries).")
                                        }
                                        entries.add(
                                            ImageEntry(
                                                path = fullPath,
                                                name = cleanName,
                                                isDirectory = true,
                                                sizeBytes = 0L,
                                                opaqueHandle = Unit
                                            )
                                        )
                                        queue.add(DirQueueItem(fullPath, extLba, dataLen, ancestors + extLba))
                                    }
                                } else if (isMultiExtent || pendingMultiExtents.containsKey(fullPath)) {
                                    val list = pendingMultiExtents.getOrPut(fullPath) { mutableListOf() }
                                    list.add(IsoExtent(extLba, dataLen))
                                    pendingNames[fullPath] = cleanName
                                    if (!isMultiExtent) {
                                        if (entries.size >= MAX_ENTRIES) {
                                            throw IOException("ISO-9660 entry limit exceeded ($MAX_ENTRIES entries).")
                                        }
                                        val totalSize = list.sumOf { it.lengthBytes }
                                        entries.add(
                                            ImageEntry(
                                                path = fullPath,
                                                name = cleanName,
                                                isDirectory = false,
                                                sizeBytes = totalSize,
                                                opaqueHandle = IsoFileHandle(list.toList())
                                            )
                                        )
                                        pendingMultiExtents.remove(fullPath)
                                        pendingNames.remove(fullPath)
                                    }
                                } else {
                                    if (entries.size >= MAX_ENTRIES) {
                                        throw IOException("ISO-9660 entry limit exceeded ($MAX_ENTRIES entries).")
                                    }
                                    entries.add(
                                        ImageEntry(
                                            path = fullPath,
                                            name = cleanName,
                                            isDirectory = false,
                                            sizeBytes = dataLen,
                                            opaqueHandle = IsoFileHandle(listOf(IsoExtent(extLba, dataLen)))
                                        )
                                    )
                                }
                            }
                        }
                    }

                    pos += recLen
                }

                // Flush any remaining multi-extent entries
                for ((fullPath, extents) in pendingMultiExtents) {
                    if (entries.size >= MAX_ENTRIES) {
                        throw IOException("ISO-9660 entry limit exceeded ($MAX_ENTRIES entries).")
                    }
                    entries.add(
                        ImageEntry(
                            path = fullPath,
                            name = pendingNames[fullPath] ?: fullPath.substringAfterLast('/'),
                            isDirectory = false,
                            sizeBytes = extents.sumOf { it.lengthBytes },
                            opaqueHandle = IsoFileHandle(extents.toList())
                        )
                    )
                }
            }

            return Iso9660Reader(
                source = source,
                blockSize = blockSize,
                formatName = if (isJoliet) "ISO-9660 + Joliet" else "ISO-9660",
                volumeLabel = volumeLabel,
                entries = entries
            )
        }

        private fun cleanIsoName(raw: String, isJoliet: Boolean): String {
            val hadVersion = ';' in raw
            var name = raw.substringBefore(';').trim()
            if ((hadVersion || !isJoliet) && name.endsWith('.')) {
                name = name.dropLast(1).trimEnd()
            }
            return name
        }

        private fun isSafeEntryName(name: String): Boolean {
            return name != "." &&
                name != ".." &&
                '/' !in name &&
                '\\' !in name &&
                '\u0000' !in name
        }

        private fun decodeUtf16Be(buf: ByteArray, offset: Int, length: Int): String {
            val count = length / 2
            val chars = CharArray(count)
            for (i in 0 until count) {
                val hi = buf[offset + i * 2].toInt() and 0xFF
                val lo = buf[offset + i * 2 + 1].toInt() and 0xFF
                chars[i] = ((hi shl 8) or lo).toChar()
            }
            return String(chars).trimEnd('\u0000')
        }
    }
}
