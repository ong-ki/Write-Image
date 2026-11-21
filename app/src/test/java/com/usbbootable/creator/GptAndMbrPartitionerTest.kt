package com.usbbootable.creator

import com.usbbootable.creator.core.filesystem.GptPartitioner
import com.usbbootable.creator.core.filesystem.MbrPartitioner
import com.usbbootable.creator.core.util.ByteUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class GptAndMbrPartitionerTest {

    @Test
    fun mbrPartitionerWritesValidActiveFat32AndWipesStaleBackupGpt() {
        val dev = InMemoryBlockDevice(totalBlocks = 200_000L, blockSize = 512)
        // Plant fake stale backup GPT header at last LBA
        ByteUtils.writeAscii(dev.data, ((dev.totalBlocks - 1L) * 512).toInt(), "EFI PART", 8)

        val range = MbrPartitioner.writeSingleActivePartition(dev, MbrPartitioner.TYPE_FAT32_LBA)
        assertEquals(2048L, range.startLba)
        assertEquals(200_000L - 2048L, range.sectorCount)

        // Check MBR signature & partition entry 0
        assertEquals(0x80.toByte(), dev.data[446])
        assertEquals(0x0C.toByte(), dev.data[446 + 4])
        assertEquals(2048L, ByteUtils.readU32LE(dev.data, 446 + 8))
        assertEquals(200_000L - 2048L, ByteUtils.readU32LE(dev.data, 446 + 12))
        assertEquals(0x55.toByte(), dev.data[510])
        assertEquals(0xAA.toByte(), dev.data[511])

        // Verify stale backup GPT signature was wiped!
        val lastSecSig = String(dev.data, ((dev.totalBlocks - 1L) * 512).toInt(), 8, Charsets.US_ASCII)
        assertEquals("\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000", lastSecSig)
    }

    @Test
    fun gptPartitionerWritesValidProtectiveMbrPrimaryAndBackupHeadersWithCrc32() {
        val totalBlocks = 400_000L // ~200 MiB
        val dev = InMemoryBlockDevice(totalBlocks = totalBlocks, blockSize = 512)
        val (esp, data) = GptPartitioner.planDualWindowsUefiPartitions(dev, espSizeBytes = 64L * 1024L * 1024L)
        GptPartitioner.writeGptLayout(dev, listOf(esp, data))

        // 1. Protective MBR check
        assertEquals(0xEE.toByte(), dev.data[446 + 4])
        assertEquals(0x55.toByte(), dev.data[510])
        assertEquals(0xAA.toByte(), dev.data[511])

        // 2. Primary GPT Header at LBA 1
        val primary = ByteArray(512)
        dev.readBlocks(1L, 1, primary)
        assertEquals("EFI PART", String(primary, 0, 8, Charsets.US_ASCII))
        assertEquals(1L, ByteUtils.readI64LE(primary, 24))
        assertEquals(totalBlocks - 1L, ByteUtils.readI64LE(primary, 32))
        assertEquals(34L, ByteUtils.readI64LE(primary, 40))
        assertEquals(totalBlocks - 34L, ByteUtils.readI64LE(primary, 48))

        val storedPrimaryCrc = ByteUtils.readI32LE(primary, 16)
        val primaryCopy = primary.copyOf()
        ByteUtils.writeI32LE(primaryCopy, 16, 0)
        assertEquals(storedPrimaryCrc, ByteUtils.crc32(primaryCopy, 0, 92))

        // 3. Partition Entry Array CRC32 & GUID check
        val partArray = ByteArray(16384)
        dev.readBlocks(2L, 32, partArray)
        assertEquals(ByteUtils.readI32LE(primary, 88), ByteUtils.crc32(partArray))
        assertEquals(GptPartitioner.GUID_EFI_SYSTEM_PARTITION, ByteUtils.gptBytesToUuid(partArray, 0))
        assertEquals(GptPartitioner.GUID_MICROSOFT_BASIC_DATA, ByteUtils.gptBytesToUuid(partArray, 128))

        // 4. Backup GPT Header at last LBA
        val backup = ByteArray(512)
        dev.readBlocks(totalBlocks - 1L, 1, backup)
        assertEquals("EFI PART", String(backup, 0, 8, Charsets.US_ASCII))
        assertEquals(totalBlocks - 1L, ByteUtils.readI64LE(backup, 24))
        assertEquals(1L, ByteUtils.readI64LE(backup, 32))
        val storedBackupCrc = ByteUtils.readI32LE(backup, 16)
        val backupCopy = backup.copyOf()
        ByteUtils.writeI32LE(backupCopy, 16, 0)
        assertEquals(storedBackupCrc, ByteUtils.crc32(backupCopy, 0, 92))
    }
}
