package com.usbbootable.creator

import com.usbbootable.creator.core.filesystem.NtfsFileSystem
import com.usbbootable.creator.core.usb.PartitionBlockDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class NtfsFileSystemTest {

    @Test
    fun formatsNtfs31WritesNestedAndMultiBlockDirectoryFilesVerifiesSha256AndPassesNtfsTools() {
        val tmpFile = File.createTempFile("usb_boot_ntfs_test_", ".img")
        try {
            // 64 MiB NTFS partition (131,072 sectors of 512 bytes)
            val sectors = 131_072L
            FileBackedBlockDevice(tmpFile, totalBlocks = sectors, blockSize = 512).use { rawDev ->
                val partDev = PartitionBlockDevice(rawDev, startLba = 0L, totalBlocks = sectors)
                val ntfs = NtfsFileSystem(partDev, hiddenSectors = 2048L, volumeLabel = "WIN_INSTALL")

                val rng = Random(2026)
                val files = linkedMapOf(
                    "efi/boot/bootx64.efi" to rng.nextBytes(65_537),
                    "boot/bcd" to rng.nextBytes(12_345),
                    "bootmgr.efi" to rng.nextBytes(24_576),
                    "setup.exe" to rng.nextBytes(8_192),
                    "sources/boot.wim" to rng.nextBytes(150_000),
                    "sources/install.wim" to rng.nextBytes(320_000),
                    "sources/empty_marker.txt" to ByteArray(0)
                )
                // Add 55 files in /sources/sxs/ to force multi-block INDX ($INDEX_ALLOCATION) B-tree pages
                for (i in 1..55) {
                    val name = "sources/sxs/microsoft_windows_package_%03d_manifest.cab".format(i)
                    files[name] = rng.nextBytes(1024 + (i * 17))
                }

                val records = files.map { (path, bytes) ->
                    ntfs.writeFile(
                        normalizedPath = path,
                        sizeBytes = bytes.size.toLong(),
                        streamer = { consumer ->
                            var pos = 0
                            while (pos < bytes.size) {
                                val chunk = minOf(16_384, bytes.size - pos)
                                consumer(bytes.copyOfRange(pos, pos + chunk), chunk)
                                pos += chunk
                            }
                        }
                    )
                }

                ntfs.finalizeFileSystem()
                rawDev.syncCache()

                // Verify every single file read-back SHA-256 matches!
                for (rec in records) {
                    assertTrue("NTFS SHA-256 verification failed for ${rec.path}", ntfs.verifyFile(rec))
                }
            }

            // If Linux ntfsfix and ntfsinfo are available on host, run strict verification on our formatted partition!
            val ntfsFixBin = File("/usr/bin/ntfsfix")
            if (ntfsFixBin.exists() && ntfsFixBin.canExecute()) {
                val proc = ProcessBuilder(ntfsFixBin.absolutePath, "-n", tmpFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                val exitCode = proc.waitFor()
                assertEquals("ntfsfix -n reported NTFS issues:\n$output", 0, exitCode)
                assertTrue("Expected ntfsfix success message:\n$output", output.contains("NTFS partition") || output.contains("processed successfully"))
            }

            val ntfsInfoBin = File("/usr/bin/ntfsinfo")
            if (ntfsInfoBin.exists() && ntfsInfoBin.canExecute()) {
                val metaProc = ProcessBuilder(ntfsInfoBin.absolutePath, "-m", tmpFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val metaOut = metaProc.inputStream.bufferedReader().readText()
                val metaExit = metaProc.waitFor()
                assertEquals("ntfsinfo -m failed:\n$metaOut", 0, metaExit)
                assertTrue("Expected WIN_INSTALL volume name:\n$metaOut", metaOut.contains("WIN_INSTALL"))

                val fileProc = ProcessBuilder(ntfsInfoBin.absolutePath, "-F", "/sources/install.wim", tmpFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val fileOut = fileProc.inputStream.bufferedReader().readText()
                val fileExit = fileProc.waitFor()
                assertEquals("ntfsinfo -F /sources/install.wim failed:\n$fileOut", 0, fileExit)
                assertTrue("Expected install.wim data size 320000 in ntfsinfo output:\n$fileOut", fileOut.contains("320000"))

                val sxsProc = ProcessBuilder(
                    ntfsInfoBin.absolutePath,
                    "-F",
                    "/sources/sxs/microsoft_windows_package_042_manifest.cab",
                    tmpFile.absolutePath
                )
                    .redirectErrorStream(true)
                    .start()
                val sxsOut = sxsProc.inputStream.bufferedReader().readText()
                val sxsExit = sxsProc.waitFor()
                assertEquals("ntfsinfo -F multi-block INDX lookup failed:\n$sxsOut", 0, sxsExit)
            }
        } finally {
            tmpFile.delete()
        }
    }
}
