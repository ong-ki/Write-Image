package com.usbbootable.creator

import com.usbbootable.creator.core.image.BootSupportSummary
import com.usbbootable.creator.core.image.FlashingMode
import com.usbbootable.creator.core.image.ImageClassification
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.image.WriteStrategy
import com.usbbootable.creator.core.safety.PreflightValidator
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.usb.UsbTransportSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreflightValidatorTest {

    private fun sampleIso(
        sizeBytes: Long = 4L * 1024L * 1024L * 1024L,
        guaranteedBootable: Boolean = true
    ) = SelectedIsoInfo(
        uriString = "content://test/os.iso",
        displayName = "ubuntu.iso",
        sizeBytes = sizeBytes,
        isAccessible = true,
        volumeLabel = "UBUNTU",
        classification = ImageClassification.HYBRID_LINUX_ISO,
        writeStrategy = WriteStrategy.RAW_SECTOR_WRITE,
        bootSupport = BootSupportSummary(
            uefiSupported = guaranteedBootable,
            legacyBiosSupported = guaranteedBootable,
            secureBootFriendly = guaranteedBootable,
            isGuaranteedBootable = guaranteedBootable,
            headline = "Test",
            technicalExplanation = "Test"
        ),
        hasMbr = true,
        hasGpt = true,
        hasIso9660 = true,
        hasUdf = false,
        hasElTorito = true
    )

    private fun sampleDrive(
        capacityBytes: Long = 16L * 1024L * 1024L * 1024L,
        hasPermission: Boolean = true,
        isProbed: Boolean = true,
        writeProtected: Boolean = false,
        support: UsbTransportSupport = UsbTransportSupport.SUPPORTED_BOT
    ) = UsbDriveInfo(
        deviceId = 1001,
        deviceName = "/dev/bus/usb/001/002",
        vendorId = 0x0781,
        productId = 0x5581,
        manufacturerName = "SanDisk",
        productName = "Ultra",
        serialNumber = "ABC123",
        transportSupport = support,
        hasPermission = hasPermission,
        isProbed = isProbed,
        logicalBlockSize = 512,
        totalBlocks = capacityBytes / 512L,
        capacityBytes = capacityBytes,
        isWriteProtected = writeProtected
    )

    @Test
    fun passesWhenIsoAndUsbMeetAllRequirements() {
        val res = PreflightValidator.validate(true, sampleIso(), sampleDrive())
        assertTrue(res.canProceed)
        assertTrue(res.blockers.isEmpty())
        assertFalse(res.requiresNonBootableAck)
    }

    @Test
    fun blocksWhenUsbCapacityTooSmall() {
        val res = PreflightValidator.validate(
            isUsbHostSupported = true,
            iso = sampleIso(sizeBytes = 6L * 1024L * 1024L * 1024L),
            drive = sampleDrive(capacityBytes = 4L * 1024L * 1024L * 1024L)
        )
        assertFalse(res.canProceed)
        assertTrue(res.blockers.any { it.contains("Insufficient USB capacity") })
    }

    @Test
    fun blocksWhenUsbIsWriteProtected() {
        val res = PreflightValidator.validate(true, sampleIso(), sampleDrive(writeProtected = true))
        assertFalse(res.canProceed)
        assertTrue(res.blockers.any { it.contains("write-protected") })
    }

    @Test
    fun blocksUaspOnlyDevicesHonestly() {
        val res = PreflightValidator.validate(
            true,
            sampleIso(),
            sampleDrive(support = UsbTransportSupport.UNSUPPORTED_UASP_ONLY)
        )
        assertFalse(res.canProceed)
        assertTrue(res.blockers.any { it.contains("UASP") })
    }

    @Test
    fun requiresExplicitAcknowledgmentForNonBootableImages() {
        val res = PreflightValidator.validate(true, sampleIso(guaranteedBootable = false), sampleDrive())
        assertTrue(res.canProceed)
        assertTrue(res.requiresNonBootableAck)
    }

    @Test
    fun blocksLinuxModeWhenWindowsIsoSelectedToPreventBlindRawWrite() {
        val windowsIso = sampleIso().copy(
            displayName = "Win11_24H2_x64.iso",
            classification = ImageClassification.WINDOWS_UEFI_DUAL_PARTITION,
            writeStrategy = WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS,
            largestFileName = "sources/install.wim",
            largestFileSize = 5_400_000_000L
        )
        val res = PreflightValidator.validate(
            isUsbHostSupported = true,
            iso = windowsIso,
            drive = sampleDrive(),
            flashingMode = FlashingMode.LINUX
        )
        assertFalse(res.canProceed)
        assertTrue(res.blockers.any { it.contains("Raw-writing a Windows ISO in Linux mode") })
    }

    @Test
    fun blocksWindowsModeWhenLinuxIsoSelected() {
        val res = PreflightValidator.validate(
            isUsbHostSupported = true,
            iso = sampleIso(),
            drive = sampleDrive(),
            flashingMode = FlashingMode.WINDOWS
        )
        assertFalse(res.canProceed)
        assertTrue(res.blockers.any { it.contains("Incompatible Flashing Mode") })
    }

    @Test
    fun allowsWindowsModeWhenWindows11IsoSelected() {
        val windowsIso = sampleIso().copy(
            displayName = "Win11_24H2_x64.iso",
            classification = ImageClassification.WINDOWS_UEFI_DUAL_PARTITION,
            writeStrategy = WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS,
            largestFileName = "sources/install.wim",
            largestFileSize = 5_400_000_000L
        )
        val res = PreflightValidator.validate(
            isUsbHostSupported = true,
            iso = windowsIso,
            drive = sampleDrive(),
            flashingMode = FlashingMode.WINDOWS
        )
        assertTrue(res.canProceed)
        assertTrue(res.blockers.isEmpty())
    }
}
