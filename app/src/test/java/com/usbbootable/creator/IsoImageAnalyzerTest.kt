package com.usbbootable.creator

import com.usbbootable.creator.core.image.ByteArrayImageSource
import com.usbbootable.creator.core.image.DetectedOsFamily
import com.usbbootable.creator.core.image.ImageClassification
import com.usbbootable.creator.core.image.IsoImageAnalyzer
import com.usbbootable.creator.core.image.WriteStrategy
import com.usbbootable.creator.core.util.ByteUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoImageAnalyzerTest {

    @Test
    fun detectsLinuxHybridIso() {
        val img = ByteArray(20 * 2048)
        // MBR partition 0 + 0x55AA signature
        img[446] = 0x80.toByte()
        img[446 + 4] = 0x17
        ByteUtils.writeI32LE(img, 446 + 12, 100)
        img[510] = 0x55
        img[511] = 0xAA.toByte()
        // GPT signature at LBA 1
        ByteUtils.writeAscii(img, 512, "EFI PART", 8)
        // ISO-9660 PVD at sector 16
        val pvd = 16 * 2048
        img[pvd] = 1
        ByteUtils.writeAscii(img, pvd + 1, "CD001", 5)
        ByteUtils.writeAscii(img, pvd + 40, "UBUNTU 24.04 LTS", 32)
        // El Torito at sector 17
        val et = 17 * 2048
        img[et] = 0
        ByteUtils.writeAscii(img, et + 1, "CD001", 5)
        ByteUtils.writeAscii(img, et + 7, "EL TORITO SPECIFICATION", 23)

        val info = IsoImageAnalyzer.analyze(
            source = ByteArrayImageSource(img),
            uriString = "content://test/ubuntu.iso",
            displayName = "ubuntu-24.04-desktop-amd64.iso"
        )

        assertEquals(ImageClassification.HYBRID_LINUX_ISO, info.classification)
        assertEquals(DetectedOsFamily.LINUX, info.detectedOsFamily)
        assertEquals(WriteStrategy.RAW_SECTOR_WRITE, info.writeStrategy)
        assertTrue(info.bootSupport.isGuaranteedBootable)
        assertTrue(info.bootSupport.uefiSupported)
        assertTrue(info.bootSupport.legacyBiosSupported)
        assertTrue(info.hasMbr)
        assertTrue(info.hasGpt)
        assertTrue(info.hasIso9660)
        assertTrue(info.hasElTorito)
    }

    @Test
    fun detectsWindowsUdfIsoUnder4GbAsSingleFat32() {
        val isoBytes = SyntheticUdfBuilder.buildUdfIso(
            volumeLabel = "WIN10_ESD",
            files = listOf(
                SyntheticUdfBuilder.SpecFile("efi/boot/bootx64.efi", ByteArray(4096) { 0x4D }),
                SyntheticUdfBuilder.SpecFile("boot/bcd", ByteArray(2048) { 0x11 }),
                SyntheticUdfBuilder.SpecFile("bootmgr.efi", ByteArray(2048) { 0x22 }),
                SyntheticUdfBuilder.SpecFile("sources/boot.wim", ByteArray(8192) { 0x33 }),
                SyntheticUdfBuilder.SpecFile("sources/install.esd", ByteArray(16384) { 0x44 })
            )
        )

        val info = IsoImageAnalyzer.analyze(
            source = ByteArrayImageSource(isoBytes),
            uriString = "content://test/win10.iso",
            displayName = "Win10_22H2_x64.iso"
        )

        assertEquals(ImageClassification.WINDOWS_UEFI_SINGLE_FAT32, info.classification)
        assertEquals(DetectedOsFamily.WINDOWS, info.detectedOsFamily)
        assertEquals(WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32, info.writeStrategy)
        assertTrue(info.hasUdf)
        assertTrue(info.bootSupport.uefiSupported)
        assertFalse(info.bootSupport.legacyBiosSupported)
        assertTrue(info.bootSupport.secureBootFriendly)
        assertTrue(info.bootSupport.isGuaranteedBootable)
    }

    @Test
    fun detectsWindows11UdfIsoOver4GbAsDualPartitionGptFat32Ntfs() {
        val isoBytes = SyntheticUdfBuilder.buildUdfIso(
            volumeLabel = "CCCOMA_X64FRE_EN-US_DV9",
            files = listOf(
                SyntheticUdfBuilder.SpecFile("efi/boot/bootx64.efi", ByteArray(4096) { 0x4D }),
                SyntheticUdfBuilder.SpecFile("boot/bcd", ByteArray(2048) { 0x11 }),
                SyntheticUdfBuilder.SpecFile("bootmgr.efi", ByteArray(2048) { 0x22 }),
                SyntheticUdfBuilder.SpecFile("sources/boot.wim", ByteArray(8192) { 0x33 }),
                SyntheticUdfBuilder.SpecFile(
                    path = "sources/install.wim",
                    content = ByteArray(4096) { 0x55 },
                    declaredSizeOverride = 5_200_000_000L // > 4 GiB FAT32 ceiling!
                )
            )
        )

        val info = IsoImageAnalyzer.analyze(
            source = ByteArrayImageSource(isoBytes),
            uriString = "content://test/win11.iso",
            displayName = "Win11_24H2_English_x64.iso"
        )

        assertEquals(ImageClassification.WINDOWS_UEFI_DUAL_PARTITION, info.classification)
        assertEquals(DetectedOsFamily.WINDOWS, info.detectedOsFamily)
        assertEquals(WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS, info.writeStrategy)
        assertEquals("sources/install.wim", info.largestFileName)
        assertEquals(5_200_000_000L, info.largestFileSize)
        assertTrue(info.bootSupport.uefiSupported)
        assertFalse(info.bootSupport.legacyBiosSupported)
        assertTrue(info.bootSupport.isGuaranteedBootable)
    }

    @Test
    fun flagsNonHybridOpticalIsoAsNotGuaranteedBootable() {
        val img = ByteArray(20 * 2048)
        // ISO-9660 PVD only, no MBR/GPT, no /efi/boot/ files
        val pvd = 16 * 2048
        img[pvd] = 1
        ByteUtils.writeAscii(img, pvd + 1, "CD001", 5)
        ByteUtils.writeAscii(img, pvd + 40, "LEGACY_OPTICAL_DISC", 32)
        ByteUtils.writeU16LE(img, pvd + 128, 2048)
        ByteUtils.writeI32LE(img, pvd + 156 + 2, 18) // empty root dir at sector 18
        ByteUtils.writeI32LE(img, pvd + 156 + 10, 2048)

        val info = IsoImageAnalyzer.analyze(
            source = ByteArrayImageSource(img),
            uriString = "content://test/old_cd.iso",
            displayName = "old_optical.iso"
        )

        assertEquals(ImageClassification.NON_HYBRID_OPTICAL_ISO, info.classification)
        assertFalse(info.bootSupport.isGuaranteedBootable)
        assertTrue(info.warnings.isNotEmpty())
    }
}
