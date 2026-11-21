package com.usbbootable.creator

import com.usbbootable.creator.core.util.ByteUtils
import java.io.ByteArrayOutputStream

/**
 * Builds a minimal, genuine ECMA-167 / OSTA UDF 1.02 ISO image in memory for unit & integration testing.
 * Supports nested directories, real file payloads, and optionally overriding the declared 64-bit
 * InformationLength for a file (to test >4 GiB Windows install.wim classification without allocating 5 GB RAM).
 */
object SyntheticUdfBuilder {

    private const val SECTOR = 2048

    data class SpecFile(
        val path: String, // e.g. "efi/boot/bootx64.efi"
        val content: ByteArray,
        val declaredSizeOverride: Long? = null
    )

    private class DirNode(
        val name: String,
        val dirs: MutableMap<String, DirNode> = LinkedHashMap(),
        val files: MutableList<SpecFile> = mutableListOf(),
        var feLbn: Int = 0,
        var dataLbn: Int = 0,
        var dataBytes: ByteArray = ByteArray(0)
    )

    fun buildUdfIso(
        volumeLabel: String = "CCCOMA_X64FRE_EN-US_DV9",
        files: List<SpecFile>
    ): ByteArray {
        val root = DirNode("")
        for (f in files) {
            val parts = f.path.split('/').filter { it.isNotEmpty() }
            var cur = root
            for (i in 0 until parts.size - 1) {
                cur = cur.dirs.getOrPut(parts[i]) { DirNode(parts[i]) }
            }
            cur.files.add(f)
        }

        // Partition starts at sector 260 (right after AVDP at 256)
        val partStartSector = 260
        var nextLbn = 1 // LBN 0 = File Set Descriptor (FSD)

        // Collect all dirs BFS
        val allDirs = mutableListOf<DirNode>()
        val queue = ArrayDeque<DirNode>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            allDirs.add(d)
            queue.addAll(d.dirs.values)
        }

        // Assign FE LBNs to directories and files
        val fileFeLbns = HashMap<SpecFile, Int>()
        val fileDataLbns = HashMap<SpecFile, Int>()

        for (d in allDirs) {
            d.feLbn = nextLbn++
            for (f in d.files) {
                fileFeLbns[f] = nextLbn++
                val dataSectors = ((f.content.size + SECTOR - 1) / SECTOR).coerceAtLeast(1)
                fileDataLbns[f] = nextLbn
                nextLbn += dataSectors
            }
        }

        // Serialize directory FID streams & assign data LBNs
        for (d in allDirs) {
            val baos = ByteArrayOutputStream()
            for (sub in d.dirs.values) {
                baos.write(buildFid(sub.name, isDir = true, icbLbn = sub.feLbn))
            }
            for (f in d.files) {
                val name = f.path.substringAfterLast('/')
                baos.write(buildFid(name, isDir = false, icbLbn = fileFeLbns.getValue(f)))
            }
            d.dataBytes = baos.toByteArray()
            val dirSectors = ((d.dataBytes.size + SECTOR - 1) / SECTOR).coerceAtLeast(1)
            d.dataLbn = nextLbn
            nextLbn += dirSectors
        }

        val totalSectors = partStartSector + nextLbn + 16
        val image = ByteArray(totalSectors * SECTOR)

        // 1. Volume Recognition Sequence at sectors 16..18
        writeVrs(image, 16, "BEA01")
        writeVrs(image, 17, "NSR02")
        writeVrs(image, 18, "TEA01")

        // 2. Main Volume Descriptor Sequence at sectors 32..34
        // Sector 32: Partition Descriptor (Tag 5)
        val pdOff = 32 * SECTOR
        ByteUtils.writeU16LE(image, pdOff, 5)
        ByteUtils.writeU16LE(image, pdOff + 22, 0) // PartitionNumber = 0
        ByteUtils.writeI32LE(image, pdOff + 188, partStartSector)
        ByteUtils.writeI32LE(image, pdOff + 192, nextLbn + 8)

        // Sector 33: Logical Volume Descriptor (Tag 6)
        val lvdOff = 33 * SECTOR
        ByteUtils.writeU16LE(image, lvdOff, 6)
        writeDString(image, lvdOff + 84, 128, volumeLabel)
        ByteUtils.writeI32LE(image, lvdOff + 212, SECTOR)
        // FSD long_ad at 248: len=2048, lbn=0, partRef=0
        ByteUtils.writeI32LE(image, lvdOff + 248, SECTOR)
        ByteUtils.writeI32LE(image, lvdOff + 252, 0)
        ByteUtils.writeU16LE(image, lvdOff + 256, 0)
        // Map table: 1 Type-1 Physical Partition Map (6 bytes)
        ByteUtils.writeI32LE(image, lvdOff + 264, 6)
        ByteUtils.writeI32LE(image, lvdOff + 268, 1)
        image[lvdOff + 440] = 1
        image[lvdOff + 441] = 6
        ByteUtils.writeU16LE(image, lvdOff + 442, 1)
        ByteUtils.writeU16LE(image, lvdOff + 444, 0)

        // Sector 34: Terminating Descriptor (Tag 8)
        ByteUtils.writeU16LE(image, 34 * SECTOR, 8)

        // 3. AVDP at sector 256 (Tag 2)
        val avdpOff = 256 * SECTOR
        ByteUtils.writeU16LE(image, avdpOff, 2)
        ByteUtils.writeI32LE(image, avdpOff + 16, 16 * SECTOR) // Main VDS extent length
        ByteUtils.writeI32LE(image, avdpOff + 20, 32)          // Main VDS location = sector 32

        // 4. File Set Descriptor at partition LBN 0 (Tag 256)
        val fsdOff = partStartSector * SECTOR
        ByteUtils.writeU16LE(image, fsdOff, 256)
        ByteUtils.writeI32LE(image, fsdOff + 400, SECTOR)
        ByteUtils.writeI32LE(image, fsdOff + 404, root.feLbn)
        ByteUtils.writeU16LE(image, fsdOff + 408, 0)

        // 5. Write directory & file File Entries (Tag 261) + data payloads
        for (d in allDirs) {
            writeFileEntry(
                image = image,
                sector = partStartSector + d.feLbn,
                fileType = 4,
                infoLength = d.dataBytes.size.toLong(),
                extentLen = d.dataBytes.size,
                extentLbn = d.dataLbn
            )
            System.arraycopy(d.dataBytes, 0, image, (partStartSector + d.dataLbn) * SECTOR, d.dataBytes.size)

            for (f in d.files) {
                val feLbn = fileFeLbns.getValue(f)
                val dataLbn = fileDataLbns.getValue(f)
                writeFileEntry(
                    image = image,
                    sector = partStartSector + feLbn,
                    fileType = 5,
                    infoLength = f.declaredSizeOverride ?: f.content.size.toLong(),
                    extentLen = f.content.size,
                    extentLbn = dataLbn
                )
                System.arraycopy(f.content, 0, image, (partStartSector + dataLbn) * SECTOR, f.content.size)
            }
        }

        return image
    }

    private fun writeVrs(image: ByteArray, sector: Int, id: String) {
        val off = sector * SECTOR
        image[off] = 0
        ByteUtils.writeAscii(image, off + 1, id, 5)
        image[off + 6] = 1
    }

    private fun writeDString(image: ByteArray, offset: Int, fieldLen: Int, value: String) {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        val maxChars = minOf(bytes.size, fieldLen - 2)
        image[offset] = 8 // CS0 8-bit
        System.arraycopy(bytes, 0, image, offset + 1, maxChars)
        image[offset + fieldLen - 1] = (maxChars + 1).toByte()
    }

    private fun writeFileEntry(
        image: ByteArray,
        sector: Int,
        fileType: Int,
        infoLength: Long,
        extentLen: Int,
        extentLbn: Int
    ) {
        val off = sector * SECTOR
        ByteUtils.writeU16LE(image, off, 261) // File Entry
        image[off + 27] = fileType.toByte()
        ByteUtils.writeU16LE(image, off + 34, 0) // adType = 0 (short_ad)
        ByteUtils.writeI64LE(image, off + 56, infoLength)
        ByteUtils.writeI32LE(image, off + 168, 0) // L_EA = 0
        ByteUtils.writeI32LE(image, off + 172, 8) // L_AD = 8 (1 short_ad)
        ByteUtils.writeI32LE(image, off + 176, extentLen)
        ByteUtils.writeI32LE(image, off + 180, extentLbn)
    }

    private fun buildFid(name: String, isDir: Boolean, icbLbn: Int): ByteArray {
        val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
        val lenFi = 1 + nameBytes.size
        val recLen = ((38 + lenFi + 3) / 4) * 4
        val buf = ByteArray(recLen)
        ByteUtils.writeU16LE(buf, 0, 257) // FID
        buf[18] = if (isDir) 0x02 else 0x00
        buf[19] = lenFi.toByte()
        ByteUtils.writeI32LE(buf, 20, SECTOR)
        ByteUtils.writeI32LE(buf, 24, icbLbn)
        ByteUtils.writeU16LE(buf, 28, 0)
        ByteUtils.writeU16LE(buf, 36, 0) // L_IU = 0
        buf[38] = 8 // CS0 8-bit
        System.arraycopy(nameBytes, 0, buf, 39, nameBytes.size)
        return buf
    }
}
