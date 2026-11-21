package com.usbbootable.creator

import com.usbbootable.creator.core.image.ByteArrayImageSource
import com.usbbootable.creator.core.image.IsoImageAnalyzer
import com.usbbootable.creator.core.image.WriteStrategy
import com.usbbootable.creator.core.util.OperationLogger
import com.usbbootable.creator.core.writer.BootableUsbOrchestrator
import com.usbbootable.creator.core.writer.WritePhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class WindowsUefiWriterTest {

    private fun buildWindowsFiles(): List<SyntheticUdfBuilder.SpecFile> {
        val rng = Random(777)
        return listOf(
            SyntheticUdfBuilder.SpecFile("efi/boot/bootx64.efi", rng.nextBytes(16_384)),
            SyntheticUdfBuilder.SpecFile("efi/microsoft/boot/bcd", rng.nextBytes(8_192)),
            SyntheticUdfBuilder.SpecFile("boot/bcd", rng.nextBytes(8_192)),
            SyntheticUdfBuilder.SpecFile("bootmgr.efi", rng.nextBytes(12_288)),
            SyntheticUdfBuilder.SpecFile("setup.exe", rng.nextBytes(4_096)),
            SyntheticUdfBuilder.SpecFile("sources/boot.wim", rng.nextBytes(98_304)),
            SyntheticUdfBuilder.SpecFile("sources/install.wim", rng.nextBytes(131_072))
        )
    }

    @Test
    fun singleFat32WindowsUefiWriteAndVerifySucceeds() = runBlocking {
        val udfBytes = SyntheticUdfBuilder.buildUdfIso(
            volumeLabel = "WIN10_X64",
            files = buildWindowsFiles()
        )
        val source = ByteArrayImageSource(udfBytes)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/win10.iso", "win10.iso")
        assertEquals(WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32, isoInfo.writeStrategy)

        val tmpDisk = File.createTempFile("usb_win_single_", ".img")
        try {
            FileBackedBlockDevice(tmpDisk, totalBlocks = 163_840L, blockSize = 512).use { dev ->
                val orchestrator = BootableUsbOrchestrator(OperationLogger())
                val result = orchestrator.execute(
                    source = source,
                    device = dev,
                    isoInfo = isoInfo,
                    targetDeviceName = "Virtual USB 80MB",
                    isCancelled = { false },
                    isDisconnected = { false },
                    onProgress = {}
                )

                assertEquals("Unexpected error: ${result.errorMessage}", WritePhase.COMPLETED, result.phase)
                val report = result.verificationReport
                assertNotNull(report)
                assertTrue(report!!.verified)
                assertEquals(7, report.filesVerified)
                assertTrue(dev.syncCount >= 1)
            }
        } finally {
            tmpDisk.delete()
        }
    }

    @Test
    fun dualPartitionGptFat32ExFatWindowsUefiWriteAndVerifySucceeds() = runBlocking {
        val udfBytes = SyntheticUdfBuilder.buildUdfIso(
            volumeLabel = "WIN11_24H2",
            files = buildWindowsFiles()
        )
        val source = ByteArrayImageSource(udfBytes)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/win11.iso", "win11.iso")
            .copy(writeStrategy = WriteStrategy.WINDOWS_UEFI_DUAL_GPT_FAT32_EXFAT)

        val tmpDisk = File.createTempFile("usb_win_dual_", ".img")
        try {
            FileBackedBlockDevice(tmpDisk, totalBlocks = 245_760L, blockSize = 512).use { dev ->
                val orchestrator = BootableUsbOrchestrator(OperationLogger())
                val result = orchestrator.execute(
                    source = source,
                    device = dev,
                    isoInfo = isoInfo,
                    targetDeviceName = "Virtual USB 120MB",
                    isCancelled = { false },
                    isDisconnected = { false },
                    onProgress = {}
                )

                assertEquals("Unexpected error: ${result.errorMessage}", WritePhase.COMPLETED, result.phase)
                val report = result.verificationReport
                assertNotNull(report)
                assertTrue(report!!.verified)
                assertEquals(12, report.filesVerified)
                assertTrue(dev.syncCount >= 1)
            }

            val partedBin = File("/usr/sbin/parted")
            if (partedBin.exists() && partedBin.canExecute()) {
                val proc = ProcessBuilder(partedBin.absolutePath, "-s", tmpDisk.absolutePath, "print")
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                assertEquals("parted reported GPT issue:\n$out", 0, exit)
                assertTrue("Expected GPT label in parted output:\n$out", out.contains("Partition Table: gpt"))
                assertTrue("Expected WIN_UEFI_BOOT partition:\n$out", out.contains("WIN_UEFI_BOOT"))
                assertTrue("Expected WIN_INSTALL partition:\n$out", out.contains("WIN_INSTALL"))
            }
        } finally {
            tmpDisk.delete()
        }
    }

    @Test
    fun dualPartitionGptFat32NtfsWindowsUefiWriteAndVerifySucceeds() = runBlocking {
        val udfBytes = SyntheticUdfBuilder.buildUdfIso(
            volumeLabel = "WIN11_24H2_NTFS",
            files = buildWindowsFiles()
        )
        val source = ByteArrayImageSource(udfBytes)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/win11_ntfs.iso", "win11_ntfs.iso")
            .copy(writeStrategy = WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS)

        val tmpDisk = File.createTempFile("usb_win_dual_ntfs_", ".img")
        try {
            FileBackedBlockDevice(tmpDisk, totalBlocks = 245_760L, blockSize = 512).use { dev ->
                val orchestrator = BootableUsbOrchestrator(OperationLogger())
                val result = orchestrator.execute(
                    source = source,
                    device = dev,
                    isoInfo = isoInfo,
                    targetDeviceName = "Virtual USB 120MB (GPT FAT32 + NTFS)",
                    isCancelled = { false },
                    isDisconnected = { false },
                    onProgress = {}
                )

                assertEquals("Unexpected error: ${result.errorMessage}", WritePhase.COMPLETED, result.phase)
                val report = result.verificationReport
                assertNotNull(report)
                assertTrue(report!!.verified)
                assertEquals(12, report.filesVerified)
                assertTrue(dev.syncCount >= 1)
            }

            val partedBin = File("/usr/sbin/parted")
            if (partedBin.exists() && partedBin.canExecute()) {
                val proc = ProcessBuilder(partedBin.absolutePath, "-s", tmpDisk.absolutePath, "print")
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                assertEquals("parted reported GPT issue:\n$out", 0, exit)
                assertTrue("Expected GPT label in parted output:\n$out", out.contains("Partition Table: gpt"))
                assertTrue("Expected WIN_UEFI_BOOT partition:\n$out", out.contains("WIN_UEFI_BOOT"))
                assertTrue("Expected WIN_INSTALL partition:\n$out", out.contains("WIN_INSTALL"))
                assertTrue("Expected ntfs filesystem detected by parted:\n$out", out.lowercase().contains("ntfs"))
            }
        } finally {
            tmpDisk.delete()
        }
    }
}
