package com.usbbootable.creator.core.safety

import com.usbbootable.creator.core.image.DetectedOsFamily
import com.usbbootable.creator.core.image.FlashingMode
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.image.WriteStrategy
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.usb.UsbTransportSupport
import com.usbbootable.creator.core.util.ByteUtils

data class PreflightCheckResult(
    val canProceed: Boolean,
    val blockers: List<String>,
    val warnings: List<String>,
    val requiresNonBootableAck: Boolean
)

/**
 * Enforces strict safety, capacity, protocol, and boot-compatibility checks before allowing
 * any destructive USB write operation.
 *
 * Architectural guarantee: Target devices are strictly external USB Host OTG mass-storage
 * endpoints enumerated by Android's UsbManager — Android internal storage block nodes
 * are never opened, exposed, or writable by this application.
 */
object PreflightValidator {

    fun validate(
        isUsbHostSupported: Boolean,
        iso: SelectedIsoInfo?,
        drive: UsbDriveInfo?,
        flashingMode: FlashingMode = FlashingMode.AUTO
    ): PreflightCheckResult {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!isUsbHostSupported) {
            blockers.add("This Android device does not report USB Host (OTG) hardware support.")
        }

        if (iso == null) {
            blockers.add("Select a Windows or Linux ISO / disk image file.")
        } else {
            if (!iso.isAccessible) {
                blockers.add("Selected ISO file is no longer accessible. Re-select it via SELECT ISO.")
            }
            if (iso.sizeBytes <= 0L) {
                blockers.add("Selected ISO file is empty (0 bytes).")
            }

            // Enforce FlashingMode compatibility so Windows ISOs are never blindly raw-written
            // and Linux ISOs are not forced through Windows WIM/BCD extraction.
            when (flashingMode) {
                FlashingMode.AUTO -> Unit
                FlashingMode.WINDOWS -> {
                    if (iso.detectedOsFamily != DetectedOsFamily.WINDOWS) {
                        blockers.add(
                            "Incompatible Flashing Mode: 'Windows Bootable USB' is selected, but '${iso.displayName}' " +
                                "is detected as ${describeDetectedFamily(iso)}. Switch to 'Linux Bootable USB' or 'Automatic ISO Detection'."
                        )
                    }
                }
                FlashingMode.LINUX -> {
                    if (iso.detectedOsFamily == DetectedOsFamily.WINDOWS) {
                        blockers.add(
                            "Incompatible Flashing Mode: '${iso.displayName}' is a Windows 10/11 UDF ISO without isohybrid " +
                                "MBR/GPT boot sectors. Raw-writing a Windows ISO in Linux mode will NOT produce a bootable USB. " +
                                "Switch to 'Windows Bootable USB' or 'Automatic ISO Detection'."
                        )
                    }
                }
            }

            warnings.addAll(iso.warnings)
        }

        if (drive == null) {
            blockers.add("Connect and select a target USB OTG flash drive.")
        } else {
            when (drive.transportSupport) {
                UsbTransportSupport.UNSUPPORTED_UASP_ONLY -> {
                    blockers.add(
                        "USB drive only exposes UASP (protocol 0x62) without a Bulk-Only Transport (BOT) " +
                            "interface. Android USB Host user-space mass storage requires BOT (0x50)."
                    )
                }
                UsbTransportSupport.UNSUPPORTED_NON_STORAGE -> {
                    blockers.add("Selected USB device is not a USB Mass Storage block device.")
                }
                UsbTransportSupport.SUPPORTED_BOT -> Unit
            }

            if (!drive.hasPermission) {
                blockers.add("Grant USB Host permission for ${drive.displayName} in SELECT USB DRIVE.")
            } else if (!drive.isProbed) {
                blockers.add(
                    drive.probeError
                        ?: "Waiting for SCSI capacity probe on ${drive.displayName}. Tap Refresh/Retry."
                )
            }

            if (drive.isWriteProtected) {
                blockers.add("Target USB drive is write-protected (read-only). Disable the physical lock switch.")
            }

            if (drive.isProbed && drive.capacityBytes <= 0L) {
                blockers.add("Target USB drive reported 0 bytes capacity (no media present).")
            }

            if (iso != null && drive.isProbed && drive.capacityBytes > 0L) {
                val needed = iso.requiredUsbBytes
                if (drive.capacityBytes < needed) {
                    val deficit = needed - drive.capacityBytes
                    blockers.add(
                        "Insufficient USB capacity: requires at least ${ByteUtils.formatBytes(needed)}, " +
                            "but ${drive.displayName} only has ${ByteUtils.formatBytes(drive.capacityBytes)} " +
                            "(short by ${ByteUtils.formatBytes(deficit)})."
                    )
                }

                if (drive.logicalBlockSize != 512) {
                    if (iso.writeStrategy == WriteStrategy.RAW_SECTOR_WRITE) {
                        warnings.add(
                            "Target USB reports ${drive.logicalBlockSize}-byte logical sectors instead of 512 bytes. " +
                                "Raw ISO/MBR partition tables created for 512-byte sectors may not boot on 4Kn drives."
                        )
                    } else if (drive.logicalBlockSize > 4096) {
                        blockers.add(
                            "Target USB reports ${drive.logicalBlockSize}-byte logical sectors; structured " +
                                "Windows/UEFI formatting supports logical sector sizes up to 4096 bytes."
                        )
                    } else {
                        warnings.add(
                            "Target USB reports ${drive.logicalBlockSize}-byte logical sectors. Partitions and " +
                                "filesystems will be formatted for ${drive.logicalBlockSize}-byte sectors."
                        )
                    }
                }
            }
        }

        val requiresNonBootableAck = iso != null && !iso.bootSupport.isGuaranteedBootable

        return PreflightCheckResult(
            canProceed = blockers.isEmpty(),
            blockers = blockers,
            warnings = warnings.distinct(),
            requiresNonBootableAck = requiresNonBootableAck
        )
    }

    private fun describeDetectedFamily(iso: SelectedIsoInfo): String = when (iso.detectedOsFamily) {
        DetectedOsFamily.WINDOWS -> "a Windows 10/11 UEFI ISO"
        DetectedOsFamily.LINUX -> "a Linux bootable ISO"
        DetectedOsFamily.RAW_DISK_IMAGE -> "a raw disk image (.img)"
        DetectedOsFamily.UNSUPPORTED -> "an unsupported or non-hybrid optical ISO"
    }
}
