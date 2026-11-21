package com.usbbootable.creator

import com.usbbootable.creator.core.filesystem.Fat32FileSystem
import com.usbbootable.creator.core.usb.PartitionBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class Fat32FileSystemTest {

    @Test
    fun formatsFat32WritesNestedLfnFilesVerifiesSha256AndPassesFsckFat() {
        // 70 MiB FAT32 partition (~143,360 sectors) -> strictly >= 65,525 clusters
        val tmpFile = File.createTempFile("usb_boot_fat32_test_", ".img")
        try {
            val sectors = 143_360L
            FileBackedBlockDevice(tmpFile, totalBlocks = sectors, blockSize = 512).use { rawDev ->
                val partDev = PartitionBlockDevice(rawDev, startLba = 0L, totalBlocks = sectors)
                val fat32 = Fat32FileSystem(partDev, hiddenSectors = 2048L, volumeLabel = "WIN11_BOOT")

                val rng = Random(2026)
                val files = mapOf(
                    "efi/boot/bootx64.efi" to rng.nextBytes(65_537),
                    "boot/bcd" to rng.nextBytes(12_345),
                    "sources/boot.wim" to rng.nextBytes(250_000),
                    "sources/very_long_filename_collision_001.dll" to rng.nextBytes(4099),
                    "sources/very_long_filename_collision_002.dll" to rng.nextBytes(8193),
                    "sources/exact13charsX" to rng.nextBytes(513),
                    "sources/empty_marker.txt" to ByteArray(0)
                )

                val records = files.map { (path, bytes) ->
                    fat32.writeFile(
                        normalizedPath = path,
                        sizeBytes = bytes.size.toLong(),
                        streamer = { consumer ->
                            var pos = 0
                            while (pos < bytes.size) {
                                val chunk = minOf(4096, bytes.size - pos)
                                consumer(bytes.copyOfRange(pos, pos + chunk), chunk)
                                pos += chunk
                            }
                        }
                    )
                }

                fat32.finalizeFileSystem()
                rawDev.syncCache()

                // Verify every single file read-back SHA-256 matches!
                for (rec in records) {
                    assertTrue("FAT32 SHA-256 verification failed for ${rec.path}", fat32.verifyFile(rec))
                }
            }

            // If Linux fsck.fat is available on host, run strict verification on our formatted partition!
            val fsckBin = File("/usr/sbin/fsck.fat")
            if (fsckBin.exists() && fsckBin.canExecute()) {
                val proc = ProcessBuilder(fsckBin.absolutePath, "-n", "-v", tmpFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                val exitCode = proc.waitFor()
                assertEquals("fsck.fat reported filesystem issues:\n$output", 0, exitCode)
            }
        } finally {
            tmpFile.delete()
        }
    }
}
