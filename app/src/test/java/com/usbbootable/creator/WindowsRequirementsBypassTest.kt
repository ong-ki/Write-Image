package com.usbbootable.creator

import com.usbbootable.creator.core.image.ByteArrayImageSource
import com.usbbootable.creator.core.image.DetectedOsFamily
import com.usbbootable.creator.core.image.IsoImageAnalyzer
import com.usbbootable.creator.core.image.WriteStrategy
import com.usbbootable.creator.core.util.OperationLogger
import com.usbbootable.creator.core.writer.BootableUsbOrchestrator
import com.usbbootable.creator.core.writer.WindowsBypassGenerator
import com.usbbootable.creator.core.writer.WindowsBypassOptions
import com.usbbootable.creator.core.writer.WritePhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

class WindowsRequirementsBypassTest {

    private fun buildWin10Files(includeSplitSwm: Boolean = false): List<SyntheticUdfBuilder.SpecFile> {
        val rng = Random(1010)
        val files = mutableListOf(
            SyntheticUdfBuilder.SpecFile("efi/boot/bootx64.efi", rng.nextBytes(16_384)),
            SyntheticUdfBuilder.SpecFile("efi/microsoft/boot/bcd", rng.nextBytes(8_192)),
            SyntheticUdfBuilder.SpecFile("boot/bcd", rng.nextBytes(8_192)),
            SyntheticUdfBuilder.SpecFile("bootmgr.efi", rng.nextBytes(12_288)),
            SyntheticUdfBuilder.SpecFile("setup.exe", rng.nextBytes(4_096)),
            SyntheticUdfBuilder.SpecFile("sources/boot.wim", rng.nextBytes(65_536))
        )
        if (includeSplitSwm) {
            files.add(SyntheticUdfBuilder.SpecFile("sources/install.swm", rng.nextBytes(65_536)))
            files.add(SyntheticUdfBuilder.SpecFile("sources/install2.swm", rng.nextBytes(65_536)))
        } else {
            files.add(SyntheticUdfBuilder.SpecFile("sources/install.esd", rng.nextBytes(98_304)))
        }
        return files
    }

    private fun buildWin11Files(): List<SyntheticUdfBuilder.SpecFile> {
        val rng = Random(1111)
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

    private fun assertValidXml(xml: String) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        assertEquals("unattend", doc.documentElement.localName)
    }

    @Test
    fun bypassOptionsDefaultsAndEnableAllBehavior() {
        val none = WindowsBypassOptions.NONE
        assertFalse(none.hasAnyEnabled)
        assertFalse(none.allEnabled)
        assertTrue(WindowsBypassGenerator.generateFiles(none, setOf("efi/boot/bootx64.efi")).isEmpty())

        val all = none.withAllEnabled(true)
        assertTrue(all.allEnabled)
        assertTrue(all.hasAnyEnabled)
        assertTrue(all.bypassTpm && all.bypassSecureBoot && all.bypassRam && all.bypassCpu)

        // Unchecking one option clears allEnabled while keeping remaining options selected
        val withoutRam = all.copy(bypassRam = false)
        assertFalse(withoutRam.allEnabled)
        assertTrue(withoutRam.hasAnyEnabled)
        assertTrue(withoutRam.bypassTpm && withoutRam.bypassSecureBoot && withoutRam.bypassCpu)
        assertFalse(withoutRam.bypassRam)

        // Re-checking restores allEnabled
        assertTrue(withoutRam.copy(bypassRam = true).allEnabled)

        // Unchecking Enable All clears all four
        assertEquals(WindowsBypassOptions.NONE, all.withAllEnabled(false))
    }

    @Test
    fun eachBypassOptionGeneratesOnlyItsOwnConfiguration() {
        val isoPaths = setOf("efi/boot/bootx64.efi")

        // 1. TPM only
        val tpmXml = WindowsBypassGenerator.buildAutounattendXml(WindowsBypassOptions(bypassTpm = true))
        assertValidXml(tpmXml)
        assertTrue(tpmXml.contains("BypassTPMCheck"))
        assertTrue(tpmXml.contains("AllowUpgradesWithUnsupportedTPMOrCPU"))
        assertFalse(tpmXml.contains("BypassSecureBootCheck"))
        assertFalse(tpmXml.contains("BypassRAMCheck"))
        assertFalse(tpmXml.contains("BypassCPUCheck"))

        // 2. Secure Boot only
        val sbXml = WindowsBypassGenerator.buildAutounattendXml(WindowsBypassOptions(bypassSecureBoot = true))
        assertValidXml(sbXml)
        assertTrue(sbXml.contains("BypassSecureBootCheck"))
        assertFalse(sbXml.contains("BypassTPMCheck"))
        assertFalse(sbXml.contains("BypassRAMCheck"))
        assertFalse(sbXml.contains("BypassCPUCheck"))
        assertFalse(sbXml.contains("AllowUpgradesWithUnsupportedTPMOrCPU"))

        // 3. RAM only
        val ramXml = WindowsBypassGenerator.buildAutounattendXml(WindowsBypassOptions(bypassRam = true))
        assertValidXml(ramXml)
        assertTrue(ramXml.contains("BypassRAMCheck"))
        assertFalse(ramXml.contains("BypassTPMCheck"))
        assertFalse(ramXml.contains("BypassSecureBootCheck"))
        assertFalse(ramXml.contains("BypassCPUCheck"))
        assertFalse(ramXml.contains("AllowUpgradesWithUnsupportedTPMOrCPU"))

        // 4. CPU only
        val cpuXml = WindowsBypassGenerator.buildAutounattendXml(WindowsBypassOptions(bypassCpu = true))
        assertValidXml(cpuXml)
        assertTrue(cpuXml.contains("BypassCPUCheck"))
        assertTrue(cpuXml.contains("AllowUpgradesWithUnsupportedTPMOrCPU"))
        assertFalse(cpuXml.contains("BypassTPMCheck"))
        assertFalse(cpuXml.contains("BypassSecureBootCheck"))
        assertFalse(cpuXml.contains("BypassRAMCheck"))

        // 5. All enabled
        val allFiles = WindowsBypassGenerator.generateFiles(WindowsBypassOptions.ALL, isoPaths)
        assertEquals(3, allFiles.size)
        val xmlContent = String(allFiles.first { it.path == WindowsBypassGenerator.AUTOUNATTEND_PATH }.content, Charsets.UTF_8)
        val regContent = String(allFiles.first { it.path == WindowsBypassGenerator.BYPASS_REG_PATH }.content, Charsets.UTF_8)
        assertValidXml(xmlContent)
        assertTrue(xmlContent.contains("pass=\"windowsPE\""))
        assertTrue(xmlContent.contains("pass=\"specialize\""))
        assertTrue(xmlContent.contains("BypassTPMCheck"))
        assertTrue(xmlContent.contains("BypassSecureBootCheck"))
        assertTrue(xmlContent.contains("BypassRAMCheck"))
        assertTrue(xmlContent.contains("BypassCPUCheck"))
        assertTrue(xmlContent.contains("AllowUpgradesWithUnsupportedTPMOrCPU"))
        assertTrue(regContent.contains("Windows Registry Editor Version 5.00"))
        assertTrue(regContent.contains("\"BypassTPMCheck\"=dword:00000001"))
        assertTrue(regContent.contains("\"BypassSecureBootCheck\"=dword:00000001"))
        assertTrue(regContent.contains("\"BypassRAMCheck\"=dword:00000001"))
        assertTrue(regContent.contains("\"BypassCPUCheck\"=dword:00000001"))

        // 6. ARM64 architecture detection
        assertEquals(listOf("arm64"), WindowsBypassGenerator.detectArchitectures(setOf("efi/boot/bootaa64.efi")))
    }

    @Test
    fun windows10SingleFat32AppliesSelectedBypassAndPreservesOriginalIso() = runBlocking {
        val udfBytes = SyntheticUdfBuilder.buildUdfIso(
            volumeLabel = "WIN10_22H2",
            files = buildWin10Files(includeSplitSwm = true)
        )
        val originalCopy = udfBytes.copyOf()
        val source = ByteArrayImageSource(udfBytes)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/win10_split.iso", "win10_split.iso")
        assertEquals(DetectedOsFamily.WINDOWS, isoInfo.detectedOsFamily)
        assertEquals(WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32, isoInfo.writeStrategy)

        val bypass = WindowsBypassOptions(bypassTpm = true, bypassRam = true)
        val tmpDisk = File.createTempFile("usb_win10_bypass_", ".img")
        try {
            FileBackedBlockDevice(tmpDisk, totalBlocks = 163_840L, blockSize = 512).use { dev ->
                val result = BootableUsbOrchestrator(OperationLogger()).execute(
                    source = source,
                    device = dev,
                    isoInfo = isoInfo,
                    targetDeviceName = "Virtual USB 80MB",
                    windowsBypassOptions = bypass,
                    isCancelled = { false },
                    isDisconnected = { false },
                    onProgress = {}
                )

                assertEquals("Unexpected error: ${result.errorMessage}", WritePhase.COMPLETED, result.phase)
                val report = result.verificationReport
                assertNotNull(report)
                assertTrue(report!!.verified)
                // 8 ISO files + 3 generated bypass files (autounattend.xml, .reg, .cmd)
                assertEquals(11, report.filesVerified)
            }

            // Original source ISO buffer is never mutated
            assertArrayEquals(originalCopy, udfBytes)

            // Verify generated bypass payload actually exists on written disk image
            val diskText = String(tmpDisk.readBytes(), Charsets.ISO_8859_1)
            assertTrue(diskText.contains("BypassTPMCheck"))
            assertTrue(diskText.contains("BypassRAMCheck"))
            assertFalse(diskText.contains("BypassSecureBootCheck"))
            assertFalse(diskText.contains("BypassCPUCheck"))
        } finally {
            tmpDisk.delete()
        }
    }

    @Test
    fun windows11DualGptFat32NtfsAppliesEnableAllBypassesOnBothPartitions() = runBlocking {
        val udfBytes = SyntheticUdfBuilder.buildUdfIso(
            volumeLabel = "WIN11_24H2_NTFS",
            files = buildWin11Files()
        )
        val source = ByteArrayImageSource(udfBytes)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/win11_24h2.iso", "win11_24h2.iso")
            .copy(writeStrategy = WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS)

        val tmpDisk = File.createTempFile("usb_win11_ntfs_bypass_", ".img")
        try {
            FileBackedBlockDevice(tmpDisk, totalBlocks = 245_760L, blockSize = 512).use { dev ->
                val result = BootableUsbOrchestrator(OperationLogger()).execute(
                    source = source,
                    device = dev,
                    isoInfo = isoInfo,
                    targetDeviceName = "Virtual USB 120MB NTFS",
                    windowsBypassOptions = WindowsBypassOptions.ALL,
                    isCancelled = { false },
                    isDisconnected = { false },
                    onProgress = {}
                )

                assertEquals("Unexpected error: ${result.errorMessage}", WritePhase.COMPLETED, result.phase)
                val report = result.verificationReport
                assertNotNull(report)
                assertTrue(report!!.verified)
                // Baseline 12 files (5 ESP + 7 NTFS) + 1 ESP autounattend.xml + 3 NTFS bypass files = 16
                assertEquals(16, report.filesVerified)
            }

            val diskText = String(tmpDisk.readBytes(), Charsets.ISO_8859_1)
            // autounattend.xml must appear on BOTH FAT32 ESP and NTFS partitions (>= 2 occurrences of xml header/unattend)
            val unattendOccurrences = Regex("urn:schemas-microsoft-com:unattend").findAll(diskText).count()
            assertTrue("Expected autounattend.xml on both ESP and NTFS partitions, found $unattendOccurrences", unattendOccurrences >= 2)
            assertTrue(diskText.contains("BypassTPMCheck"))
            assertTrue(diskText.contains("BypassSecureBootCheck"))
            assertTrue(diskText.contains("BypassRAMCheck"))
            assertTrue(diskText.contains("BypassCPUCheck"))
            assertTrue(diskText.contains("AllowUpgradesWithUnsupportedTPMOrCPU"))
        } finally {
            tmpDisk.delete()
        }
    }

    @Test
    fun linuxHybridIsoNeverReceivesWindowsBypassConfiguration() = runBlocking {
        // Build a synthetic hybrid Linux ISO (MBR + ISO-9660 signature)
        val linuxBytes = ByteArray(1024 * 1024)
        linuxBytes[510] = 0x55
        linuxBytes[511] = 0xAA.toByte()
        linuxBytes[446 + 4] = 0x17
        linuxBytes[446 + 12] = 0x00
        linuxBytes[446 + 13] = 0x08
        val pvdOff = 16 * 2048
        linuxBytes[pvdOff] = 1
        "CD001".toByteArray().copyInto(linuxBytes, pvdOff + 1)
        "UBUNTU_24_04".toByteArray().copyInto(linuxBytes, pvdOff + 40)

        val source = ByteArrayImageSource(linuxBytes)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/ubuntu.iso", "ubuntu.iso")
        assertEquals(DetectedOsFamily.LINUX, isoInfo.detectedOsFamily)
        assertEquals(WriteStrategy.RAW_SECTOR_WRITE, isoInfo.writeStrategy)

        val dev = InMemoryBlockDevice(totalBlocks = 4096L, blockSize = 512)
        val result = BootableUsbOrchestrator(OperationLogger()).execute(
            source = source,
            device = dev,
            isoInfo = isoInfo,
            targetDeviceName = "Virtual Linux USB",
            windowsBypassOptions = WindowsBypassOptions.ALL,
            isCancelled = { false },
            isDisconnected = { false },
            onProgress = {}
        )

        assertEquals(WritePhase.COMPLETED, result.phase)
        assertTrue(result.verificationReport!!.verified)
        val written = ByteArray(linuxBytes.size)
        dev.readBlocks(0L, linuxBytes.size / 512, written, 0)
        assertArrayEquals("Linux raw image must be bit-for-bit identical without Windows bypass files", linuxBytes, written)
    }

    @Test
    fun nonWindowsUefiExtractableIsoNeverReceivesWindowsBypassConfiguration() = runBlocking {
        val rng = Random(4242)
        val linuxUefiFiles = listOf(
            SyntheticUdfBuilder.SpecFile("efi/boot/bootx64.efi", rng.nextBytes(16_384)),
            SyntheticUdfBuilder.SpecFile("casper/vmlinuz", rng.nextBytes(32_768)),
            SyntheticUdfBuilder.SpecFile("casper/initrd", rng.nextBytes(32_768))
        )
        val udfBytes = SyntheticUdfBuilder.buildUdfIso(volumeLabel = "LINUX_UEFI", files = linuxUefiFiles)
        val source = ByteArrayImageSource(udfBytes)
        val isoInfo = IsoImageAnalyzer.analyze(source, "content://test/linux_uefi.iso", "linux_uefi.iso")
        assertEquals(DetectedOsFamily.LINUX, isoInfo.detectedOsFamily)
        assertEquals(WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32, isoInfo.writeStrategy)

        val tmpDisk = File.createTempFile("usb_linux_uefi_", ".img")
        try {
            FileBackedBlockDevice(tmpDisk, totalBlocks = 163_840L, blockSize = 512).use { dev ->
                val result = BootableUsbOrchestrator(OperationLogger()).execute(
                    source = source,
                    device = dev,
                    isoInfo = isoInfo,
                    targetDeviceName = "Virtual Linux UEFI USB",
                    windowsBypassOptions = WindowsBypassOptions.ALL,
                    isCancelled = { false },
                    isDisconnected = { false },
                    onProgress = {}
                )

                assertEquals(WritePhase.COMPLETED, result.phase)
                val report = requireNotNull(result.verificationReport)
                assertTrue(report.verified)
                assertEquals(3, report.filesVerified)
            }

            val diskText = String(tmpDisk.readBytes(), Charsets.ISO_8859_1)
            assertFalse(diskText.contains("BypassTPMCheck"))
            assertFalse(diskText.contains("urn:schemas-microsoft-com:unattend"))
        } finally {
            tmpDisk.delete()
        }
    }
}
