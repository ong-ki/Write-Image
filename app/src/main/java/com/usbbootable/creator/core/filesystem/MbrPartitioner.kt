package com.usbbootable.creator.core.filesystem

import com.usbbootable.creator.core.usb.BlockDevice
import com.usbbootable.creator.core.util.ByteUtils
import java.io.IOException
import java.security.SecureRandom

data class PartitionRange(
    val startLba: Long,
    val sectorCount: Long
)

object MbrPartitioner {

    const val TYPE_FAT32_LBA: Int = 0x0C

    /**
     * Wipes stale MBR/GPT metadata (beginning and end of drive) and writes a single
     * 1 MiB-aligned active MBR partition table.
     */
    @Throws(IOException::class)
    fun writeSingleActivePartition(
        device: BlockDevice,
        partitionType: Int = TYPE_FAT32_LBA
    ): PartitionRange {
        val blockSize = device.blockSize
        val alignSectors = (1024L * 1024L) / blockSize.toLong() // 2048 sectors for 512B blocks
        val startLba = alignSectors
        val maxMbrSectors = 0xFFFFFFFFL
        val available = device.totalBlocks - startLba
        if (available < 65_536L) {
            throw IOException("USB drive is too small to create a FAT32 partition.")
        }
        val partSectors = minOf(available, maxMbrSectors)

        // Wipe initial metadata sectors (LBA 0..63) and trailing backup GPT sectors (last 64 blocks)
        // so stale GPT headers from prior flashes never confuse UEFI firmware.
        val wipeCount = minOf(64L, device.totalBlocks).toInt()
        val zeroBuf = ByteArray(wipeCount * blockSize)
        device.writeBlocks(0L, wipeCount, zeroBuf)
        if (device.totalBlocks > wipeCount.toLong() * 2L) {
            device.writeBlocks(device.totalBlocks - wipeCount, wipeCount, zeroBuf)
        }

        // Build MBR sector at LBA 0
        val mbr = ByteArray(blockSize)
        val diskSig = SecureRandom().nextInt()
        ByteUtils.writeI32LE(mbr, 440, diskSig)

        val e0 = 446
        mbr[e0] = 0x80.toByte()             // Active / Bootable flag
        mbr[e0 + 1] = 0xFE.toByte()         // Start head (LBA sentinel)
        mbr[e0 + 2] = 0xFF.toByte()         // Start sector/cyl
        mbr[e0 + 3] = 0xFF.toByte()         // Start cyl
        mbr[e0 + 4] = partitionType.toByte()
        mbr[e0 + 5] = 0xFE.toByte()         // End head
        mbr[e0 + 6] = 0xFF.toByte()         // End sector/cyl
        mbr[e0 + 7] = 0xFF.toByte()         // End cyl
        ByteUtils.writeI32LE(mbr, e0 + 8, startLba.toInt())
        ByteUtils.writeI32LE(mbr, e0 + 12, partSectors.toInt())

        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()

        device.writeBlocks(0L, 1, mbr)
        return PartitionRange(startLba = startLba, sectorCount = partSectors)
    }
}
