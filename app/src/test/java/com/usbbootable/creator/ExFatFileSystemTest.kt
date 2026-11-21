package com.usbbootable.creator

import com.usbbootable.creator.core.filesystem.ExFatFileSystem
import com.usbbootable.creator.core.util.ByteUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExFatFileSystemTest {

    @Test
    fun formatsExFatWithValidBootChecksumsUpCaseTableBitmapAndSha256ReadBack() {
        val totalSectors = 65_536L // 32 MiB synthetic partition
        val dev = InMemoryBlockDevice(totalBlocks = totalSectors, blockSize = 512)
        val exFat = ExFatFileSystem(dev, partitionOffsetLba = 2048L, volumeLabel = "WIN_INSTALL")

        val rng = Random(999)
        val files = mapOf(
            "setup.exe" to rng.nextBytes(33_000),
            "sources/install.wim" to rng.nextBytes(310_000),
            "sources/Long Mixed Case File Name (x64).inf" to rng.nextBytes(9_001),
            "sources/zero_len.dat" to ByteArray(0)
        )

        val records = files.map { (path, data) ->
            exFat.writeFile(
                normalizedPath = path,
                sizeBytes = data.size.toLong(),
                streamer = { consumer ->
                    var pos = 0
                    while (pos < data.size) {
                        val chunk = minOf(8192, data.size - pos)
                        consumer(data.copyOfRange(pos, pos + chunk), chunk)
                        pos += chunk
                    }
                }
            )
        }

        exFat.finalizeFileSystem()

        // 1. Verify Main Boot Region (0..11) and Backup Boot Region (12..23) are byte-for-byte identical
        val mainRegion = ByteArray(12 * 512)
        val backupRegion = ByteArray(12 * 512)
        dev.readBlocks(0L, 12, mainRegion)
        dev.readBlocks(12L, 12, backupRegion)
        assertArrayEquals(mainRegion, backupRegion)

        // 2. Verify "EXFAT   " signature, extended boot signatures, and Boot Checksum Sector (sector 11)
        assertEquals("EXFAT   ", String(mainRegion, 3, 8, Charsets.US_ASCII))
        assertEquals(0x55.toByte(), mainRegion[510])
        assertEquals(0xAA.toByte(), mainRegion[511])
        for (sec in 1..8) {
            assertEquals(0xAA550000.toInt(), ByteUtils.readI32LE(mainRegion, sec * 512 + 508))
        }
        val expectedChecksum = ExFatFileSystem.computeBootChecksum(mainRegion, 11 * 512)
        for (off in 0 until 512 step 4) {
            assertEquals(expectedChecksum, ByteUtils.readI32LE(mainRegion, 11 * 512 + off))
        }

        // 3. Verify SHA-256 read-back of every written file
        for (rec in records) {
            assertTrue("exFAT read-back SHA-256 failed for ${rec.path}", exFat.verifyFile(rec))
        }
    }

    @Test
    fun compressedUpCaseTableDecompressesToExact65536BmpEntries() {
        val map = CharArray(65536) { it.toChar().uppercaseChar() }
        val compressed = ExFatFileSystem.buildCompressedUpCaseTable(map)

        val decoded = CharArray(65536)
        var outIdx = 0
        var pos = 0
        while (pos < compressed.size) {
            val v = ByteUtils.readU16LE(compressed, pos)
            pos += 2
            if (v == 0xFFFF) {
                assertTrue("Compressed table must not end with dangling 0xFFFF", pos + 2 <= compressed.size)
                val run = ByteUtils.readU16LE(compressed, pos)
                pos += 2
                for (k in 0 until run) {
                    decoded[outIdx] = outIdx.toChar()
                    outIdx++
                }
            } else {
                decoded[outIdx] = v.toChar()
                outIdx++
            }
        }

        assertEquals(65536, outIdx)
        for (i in 0 until 65536) {
            assertEquals("Mismatch at codepoint $i", map[i], decoded[i])
        }
    }
}
