package com.usbbootable.creator.core.image

enum class FlashingMode(val label: String) {
    AUTO("Automatic ISO Detection"),
    WINDOWS("Windows Bootable USB"),
    LINUX("Linux Bootable USB")
}

enum class DetectedOsFamily {
    WINDOWS,
    LINUX,
    RAW_DISK_IMAGE,
    UNSUPPORTED
}

enum class ImageClassification {
    HYBRID_LINUX_ISO,
    RAW_DISK_IMAGE,
    WINDOWS_UEFI_SINGLE_FAT32,
    WINDOWS_UEFI_DUAL_PARTITION,
    UEFI_EXTRACTABLE_ISO,
    NON_HYBRID_OPTICAL_ISO,
    UNKNOWN_RAW_IMAGE
}

enum class WriteStrategy {
    RAW_SECTOR_WRITE,
    WINDOWS_UEFI_SINGLE_FAT32,
    WINDOWS_UEFI_DUAL_FAT32_NTFS,
    WINDOWS_UEFI_DUAL_GPT_FAT32_EXFAT
}

data class BootSupportSummary(
    val uefiSupported: Boolean,
    val legacyBiosSupported: Boolean,
    val secureBootFriendly: Boolean,
    val isGuaranteedBootable: Boolean,
    val headline: String,
    val technicalExplanation: String
)

data class SelectedIsoInfo(
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val isAccessible: Boolean,
    val volumeLabel: String,
    val classification: ImageClassification,
    val writeStrategy: WriteStrategy,
    val bootSupport: BootSupportSummary,
    val hasMbr: Boolean,
    val hasGpt: Boolean,
    val hasIso9660: Boolean,
    val hasUdf: Boolean,
    val hasElTorito: Boolean,
    val largestFileName: String? = null,
    val largestFileSize: Long = 0L,
    val extractedTotalBytes: Long = 0L,
    val extractedFileCount: Int = 0,
    val fat32BootBytesNeeded: Long = 0L,
    val warnings: List<String> = emptyList()
) {
    val detectedOsFamily: DetectedOsFamily
        get() = when (classification) {
            ImageClassification.WINDOWS_UEFI_SINGLE_FAT32,
            ImageClassification.WINDOWS_UEFI_DUAL_PARTITION -> DetectedOsFamily.WINDOWS
            ImageClassification.HYBRID_LINUX_ISO,
            ImageClassification.UEFI_EXTRACTABLE_ISO -> DetectedOsFamily.LINUX
            ImageClassification.RAW_DISK_IMAGE -> DetectedOsFamily.RAW_DISK_IMAGE
            ImageClassification.NON_HYBRID_OPTICAL_ISO,
            ImageClassification.UNKNOWN_RAW_IMAGE -> DetectedOsFamily.UNSUPPORTED
        }

    /**
     * Conservative minimum USB capacity needed for the selected [writeStrategy].
     */
    val requiredUsbBytes: Long
        get() = when (writeStrategy) {
            WriteStrategy.RAW_SECTOR_WRITE -> sizeBytes
            WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32 -> {
                // Extracted files + 64 MiB FAT32 & alignment overhead
                maxOf(sizeBytes, extractedTotalBytes) + 64L * 1024L * 1024L
            }
            WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS,
            WriteStrategy.WINDOWS_UEFI_DUAL_GPT_FAT32_EXFAT -> {
                // Partition 1 (FAT32 ESP >= 1 GiB or boot files + 256 MiB) + Partition 2 (NTFS/exFAT data + 128 MiB)
                val espBytes = maxOf(1024L * 1024L * 1024L, fat32BootBytesNeeded + 256L * 1024L * 1024L)
                val dataBytes = extractedTotalBytes + 128L * 1024L * 1024L
                espBytes + dataBytes + 16L * 1024L * 1024L
            }
        }
}
