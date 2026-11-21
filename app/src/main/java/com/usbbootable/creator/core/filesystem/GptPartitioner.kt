package com.usbbootable.creator.core.filesystem

import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.util.ByteUtils
import java.io.IOException
import java.util.UUID

data class GptPartitionSpec(
    val typeGuid: UUID,
    val name: String,
    val startLba: Long,
    val endLbaInclusive: Long,
    val uniqueGuid: UUID = UUID.randomUUID(),
    val attributes: Long = 0L
) {
    val sectorCount: Long get() = endLbaInclusive - startLba + 1L
}

object GptPartitioner {

    /** UEFI EFI System Partition (ESP) GUID */
    val GUID_EFI_SYSTEM_PARTITION: UUID = UUID.fromString("C12A7328-F81F-11D2-BA4B-00A0C93EC93B")

    /** Microsoft Basic Data Partition GUID (FAT32 / exFAT / NTFS) */
    val GUID_MICROSOFT_BASIC_DATA: UUID = UUID.fromString("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")

    private const val NUM_PART_ENTRIES = 128
    private const val PART_ENTRY_SIZE = 128
    private const val PART_ARRAY_BYTES = NUM_PART_ENTRIES * PART_ENTRY_SIZE // 16,384 bytes

    /**
     * Writes a UEFI-compliant GPT layout (Protective MBR + Primary Header/Entries + Backup Entries/Header)
     * with 1 MiB-aligned partitions.
     */
    @Throws(IOException::class)
    fun writeGptLayout(
        device: BlockDevice,
        partitions: List<GptPartitionSpec>,
        diskGuid: UUID = UUID.randomUUID()
    ) {
        val blockSize = device.blockSize
        val totalBlocks = device.totalBlocks
        require(PART_ARRAY_BYTES % blockSize == 0) {
            "Unsupported block size for GPT: $blockSize"
        }
        val arraySectors = (PART_ARRAY_BYTES / blockSize).toLong()
        val firstUsableLba = 2L + arraySectors
        val lastUsableLba = totalBlocks - 2L - arraySectors

        require(lastUsableLba > firstUsableLba) { "Device too small for GPT." }
        require(partitions.isNotEmpty() && partitions.size <= NUM_PART_ENTRIES)

        for (p in partitions) {
            require(p.startLba >= firstUsableLba && p.endLbaInclusive <= lastUsableLba && p.startLba <= p.endLbaInclusive) {
                "Partition '${p.name}' [${p.startLba}..${p.endLbaInclusive}] outside usable GPT range [$firstUsableLba..$lastUsableLba]"
            }
        }

        // 1. Write Protective MBR at LBA 0
        val pmbr = ByteArray(blockSize)
        val e0 = 446
        pmbr[e0] = 0x00
        pmbr[e0 + 1] = 0x00
        pmbr[e0 + 2] = 0x02
        pmbr[e0 + 3] = 0x00
        pmbr[e0 + 4] = 0xEE.toByte() // GPT Protective MBR
        pmbr[e0 + 5] = 0xFF.toByte()
        pmbr[e0 + 6] = 0xFF.toByte()
        pmbr[e0 + 7] = 0xFF.toByte()
        ByteUtils.writeI32LE(pmbr, e0 + 8, 1)
        val pmbrSectors = minOf(totalBlocks - 1L, 0xFFFFFFFFL)
        ByteUtils.writeI32LE(pmbr, e0 + 12, pmbrSectors.toInt())
        pmbr[510] = 0x55
        pmbr[511] = 0xAA.toByte()
        device.writeBlocks(0L, 1, pmbr)

        // 2. Build 16 KiB Partition Entry Array
        val partArray = ByteArray(PART_ARRAY_BYTES)
        for ((index, spec) in partitions.withIndex()) {
            val off = index * PART_ENTRY_SIZE
            System.arraycopy(ByteUtils.uuidToGptBytes(spec.typeGuid), 0, partArray, off, 16)
            System.arraycopy(ByteUtils.uuidToGptBytes(spec.uniqueGuid), 0, partArray, off + 16, 16)
            ByteUtils.writeI64LE(partArray, off + 32, spec.startLba)
            ByteUtils.writeI64LE(partArray, off + 40, spec.endLbaInclusive)
            ByteUtils.writeI64LE(partArray, off + 48, spec.attributes)

            val nameChars = spec.name.take(36)
            for (cIdx in nameChars.indices) {
                ByteUtils.writeU16LE(partArray, off + 56 + cIdx * 2, nameChars[cIdx].code)
            }
        }
        val partArrayCrc = ByteUtils.crc32(partArray)

        // 3. Build Primary GPT Header (LBA 1)
        val primaryHeader = buildGptHeader(
            blockSize = blockSize,
            myLba = 1L,
            alternateLba = totalBlocks - 1L,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            partEntryLba = 2L,
            partArrayCrc = partArrayCrc
        )

        // 4. Build Backup GPT Header (last LBA)
        val backupArrayStartLba = totalBlocks - 1L - arraySectors
        val backupHeader = buildGptHeader(
            blockSize = blockSize,
            myLba = totalBlocks - 1L,
            alternateLba = 1L,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            partEntryLba = backupArrayStartLba,
            partArrayCrc = partArrayCrc
        )

        // 5. Write Primary GPT Header + Array, then Backup Array + Header
        device.writeBlocks(1L, 1, primaryHeader)
        device.writeBlocks(2L, arraySectors.toInt(), partArray)
        device.writeBlocks(backupArrayStartLba, arraySectors.toInt(), partArray)
        device.writeBlocks(totalBlocks - 1L, 1, backupHeader)
    }

    /**
     * Computes a 1 MiB-aligned dual-partition GPT plan:
     * Partition 1: FAT32 EFI System Partition (ESP)
     * Partition 2: exFAT Microsoft Basic Data Partition (rest of USB drive)
     */
    fun planDualWindowsUefiPartitions(
        device: BlockDevice,
        espSizeBytes: Long
    ): Pair<GptPartitionSpec, GptPartitionSpec> {
        val blockSize = device.blockSize.toLong()
        val alignSectors = (1024L * 1024L) / blockSize // 1 MiB alignment
        val arraySectors = PART_ARRAY_BYTES / blockSize
        val lastUsableLba = device.totalBlocks - 2L - arraySectors

        val espStart = alignSectors
        val requestedEspSectors = ((espSizeBytes + (1024L * 1024L - 1)) / (1024L * 1024L)) * alignSectors
        val minEspSectors = (64L * 1024L * 1024L) / blockSize // At least 64 MiB for valid FAT32 >= 65525 clusters
        val espSectors = maxOf(requestedEspSectors, minEspSectors)
        val espEnd = espStart + espSectors - 1L

        val dataStart = espEnd + 1L
        // Align data end down to whole blocks within lastUsableLba
        val dataEnd = lastUsableLba
        if (dataEnd <= dataStart + alignSectors) {
            throw IOException("USB drive is too small for dual-partition Windows UEFI layout.")
        }

        val espSpec = GptPartitionSpec(
            typeGuid = GUID_EFI_SYSTEM_PARTITION,
            name = "WIN_UEFI_BOOT",
            startLba = espStart,
            endLbaInclusive = espEnd
        )
        val dataSpec = GptPartitionSpec(
            typeGuid = GUID_MICROSOFT_BASIC_DATA,
            name = "WIN_INSTALL",
            startLba = dataStart,
            endLbaInclusive = dataEnd
        )
        return Pair(espSpec, dataSpec)
    }

    private fun buildGptHeader(
        blockSize: Int,
        myLba: Long,
        alternateLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: UUID,
        partEntryLba: Long,
        partArrayCrc: Int
    ): ByteArray {
        val header = ByteArray(blockSize)
        ByteUtils.writeAscii(header, 0, "EFI PART", 8)
        ByteUtils.writeI32LE(header, 8, 0x00010000)      // Revision 1.0
        ByteUtils.writeI32LE(header, 12, 92)             // Header size
        // Bytes 16..19 = HeaderCRC32 (0 during calculation)
        // Bytes 20..23 = Reserved (0)
        ByteUtils.writeI64LE(header, 24, myLba)
        ByteUtils.writeI64LE(header, 32, alternateLba)
        ByteUtils.writeI64LE(header, 40, firstUsableLba)
        ByteUtils.writeI64LE(header, 48, lastUsableLba)
        System.arraycopy(ByteUtils.uuidToGptBytes(diskGuid), 0, header, 56, 16)
        ByteUtils.writeI64LE(header, 72, partEntryLba)
        ByteUtils.writeI32LE(header, 80, NUM_PART_ENTRIES)
        ByteUtils.writeI32LE(header, 84, PART_ENTRY_SIZE)
        ByteUtils.writeI32LE(header, 88, partArrayCrc)

        val headerCrc = ByteUtils.crc32(header, 0, 92)
        ByteUtils.writeI32LE(header, 16, headerCrc)
        return header
    }
}
