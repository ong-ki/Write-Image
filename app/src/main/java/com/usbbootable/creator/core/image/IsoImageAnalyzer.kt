package com.usbbootable.creator.core.image

import com.usbbootable.creator.core.util.ByteUtils
import java.io.IOException

/**
 * Inspects an ISO or raw disk image to determine its exact partition/filesystem structures,
 * Windows vs. Linux compatibility requirements, FAT32 4 GiB threshold, and honest boot capability.
 */
object IsoImageAnalyzer {

    const val FAT32_MAX_FILE_SIZE: Long = 4_294_967_295L // 4 GiB - 1 byte

    fun openFileSystem(source: ImageSource): ImageFileSystem? {
        // Prefer UDF when present (Windows 10/11 ISOs put real files in UDF and only a stub README in ISO-9660)
        if (UdfReader.hasUdfSignature(source)) {
            val udf = UdfReader.tryParse(source)
            if (udf != null && udf.entries.isNotEmpty()) return udf
        }
        return Iso9660Reader.tryParse(source)
    }

    @Throws(IOException::class)
    fun analyze(
        source: ImageSource,
        uriString: String,
        displayName: String
    ): SelectedIsoInfo {
        val size = source.sizeBytes
        val warnings = mutableListOf<String>()

        // 1. Inspect MBR at sector 0 (offset 0..511)
        var hasMbrSignature = false
        var hasValidMbrPartition = false
        var hasProtectiveGptMbr = false
        if (size >= 512L) {
            val mbr = ByteArray(512)
            source.readExact(0L, mbr, 0, 512)
            hasMbrSignature = (mbr[510].toInt() and 0xFF) == 0x55 &&
                (mbr[511].toInt() and 0xFF) == 0xAA
            if (hasMbrSignature) {
                for (entry in 0 until 4) {
                    val off = 446 + entry * 16
                    val partType = mbr[off + 4].toInt() and 0xFF
                    val sectorCount = ByteUtils.readU32LE(mbr, off + 12)
                    if (partType == 0xEE && sectorCount > 0L) {
                        hasProtectiveGptMbr = true
                    }
                    if (partType != 0x00 && sectorCount > 0L) {
                        hasValidMbrPartition = true
                    }
                }
            }
        }

        // 2. Inspect GPT header at LBA 1 (512-byte offset 512, or 2048-byte isohybrid GPT offset 2048)
        var hasGpt = false
        for (gptOffset in listOf(512L, 2048L)) {
            if (size >= gptOffset + 8L) {
                val sig = ByteArray(8)
                source.readExact(gptOffset, sig, 0, 8)
                if (String(sig, Charsets.US_ASCII) == "EFI PART") {
                    hasGpt = true
                    break
                }
            }
        }

        // 3. Inspect ISO-9660 Primary Volume Descriptor & El Torito Boot Record
        var hasIso9660 = false
        var hasElTorito = false
        var pvdVolumeLabel = ""
        if (size >= 18L * 2048L) {
            val vd = ByteArray(2048)
            for (sector in 16..32) {
                val pos = sector.toLong() * 2048L
                if (pos + 2048L > size) break
                source.readExact(pos, vd, 0, 2048)
                val magic = String(vd, 1, 5, Charsets.US_ASCII)
                if (magic != "CD001") break
                val vdType = vd[0].toInt() and 0xFF
                if (vdType == 1) {
                    hasIso9660 = true
                    pvdVolumeLabel = String(vd, 40, 32, Charsets.US_ASCII).trim()
                } else if (vdType == 0) {
                    val bootSys = String(vd, 7, 23, Charsets.US_ASCII)
                    if (bootSys.startsWith("EL TORITO SPECIFICATION")) {
                        hasElTorito = true
                    }
                } else if (vdType == 255) {
                    break
                }
            }
        }

        // 4. Inspect UDF presence
        val hasUdf = UdfReader.hasUdfSignature(source)

        // 5. Inspect internal ISO/UDF filesystem tree when applicable
        val isRawBootableHybrid = (hasMbrSignature && hasValidMbrPartition) || hasGpt
        var fs: ImageFileSystem? = null
        if (hasUdf || hasIso9660) {
            fs = openFileSystem(source)
        }

        val allFiles = fs?.entries?.filter { !it.isDirectory }.orEmpty()
        val lowerPaths = allFiles.map { it.normalizedPathLower }.toSet()

        val hasEfiBootloader = lowerPaths.any {
            it == "efi/boot/bootx64.efi" ||
                it == "efi/boot/bootaa64.efi" ||
                it == "efi/boot/bootia32.efi"
        }
        val hasWindowsBootWim = lowerPaths.contains("sources/boot.wim")
        val hasWindowsInstallImage = lowerPaths.contains("sources/install.wim") ||
            lowerPaths.contains("sources/install.esd") ||
            lowerPaths.any { it.startsWith("sources/install") && it.endsWith(".swm") }
        val hasWindowsSignatures = (hasWindowsBootWim && (hasWindowsInstallImage || lowerPaths.contains("bootmgr.efi") || lowerPaths.contains("setup.exe"))) ||
            (lowerPaths.contains("bootmgr.efi") && lowerPaths.contains("boot/bcd") && hasEfiBootloader)

        val largestFile = allFiles.maxByOrNull { it.sizeBytes }
        val largestSize = largestFile?.sizeBytes ?: 0L
        val extractedTotalBytes = allFiles.sumOf { it.sizeBytes }
        val hasOversizedFat32File = largestSize > FAT32_MAX_FILE_SIZE

        // Compute how much space Partition 1 (FAT32 ESP) needs in dual-partition Windows mode:
        // everything in efi/, boot/, root bootmgr files, and sources/boot.wim
        val fat32BootBytesNeeded = allFiles
            .filter { isWindowsEspBootFile(it.normalizedPathLower) }
            .sumOf { it.sizeBytes }

        val volumeLabel = fs?.volumeLabel?.takeIf { it.isNotBlank() }
            ?: pvdVolumeLabel.takeIf { it.isNotBlank() }
            ?: displayName.substringBeforeLast('.').take(24)

        // 6. Classify honestly and choose exact write strategy
        return when {
            // Case A: Windows UEFI ISO (Official Windows 10 / 11 / Server / PE)
            hasWindowsSignatures && hasEfiBootloader && !isRawBootableHybrid -> {
                if (hasOversizedFat32File) {
                    warnings.add(
                        "${largestFile?.path} is ${ByteUtils.formatBytes(largestSize)} (> 4 GiB FAT32 limit). " +
                            "App will create a UEFI-compatible GPT dual-partition layout: FAT32 EFI System Partition + NTFS 3.1 Windows Installer partition."
                    )
                    warnings.add(
                        "UEFI boot mode is required on the target PC (Legacy BIOS / CSM mode is NOT supported for GPT UEFI Windows layout)."
                    )
                    SelectedIsoInfo(
                        uriString = uriString,
                        displayName = displayName,
                        sizeBytes = size,
                        isAccessible = true,
                        volumeLabel = volumeLabel,
                        classification = ImageClassification.WINDOWS_UEFI_DUAL_PARTITION,
                        writeStrategy = WriteStrategy.WINDOWS_UEFI_DUAL_FAT32_NTFS,
                        bootSupport = BootSupportSummary(
                            uefiSupported = true,
                            legacyBiosSupported = false,
                            secureBootFriendly = true,
                            isGuaranteedBootable = true,
                            headline = "Windows 10/11 UEFI Bootable (GPT: FAT32 EFI + NTFS)",
                            technicalExplanation = "Windows ISOs are optical UDF images (not isohybrid) and cannot be raw-written. " +
                                "Because ${largestFile?.name} exceeds FAT32's 4 GiB limit, the drive will be formatted with GPT: " +
                                "Partition 1 (FAT32 EFI System Partition) boots Windows PE via Microsoft's signed UEFI bootloader (Secure Boot compatible), " +
                                "and Partition 2 (NTFS 3.1) stores the full Windows installer including ${largestFile?.name}."
                        ),
                        hasMbr = hasMbrSignature && hasValidMbrPartition,
                        hasGpt = hasGpt,
                        hasIso9660 = hasIso9660,
                        hasUdf = hasUdf,
                        hasElTorito = hasElTorito,
                        largestFileName = largestFile?.path,
                        largestFileSize = largestSize,
                        extractedTotalBytes = extractedTotalBytes,
                        extractedFileCount = allFiles.size,
                        fat32BootBytesNeeded = fat32BootBytesNeeded,
                        warnings = warnings
                    )
                } else {
                    warnings.add(
                        "UEFI boot mode is required on the target PC (Legacy BIOS / CSM mode is not written by FAT32 UEFI extraction)."
                    )
                    SelectedIsoInfo(
                        uriString = uriString,
                        displayName = displayName,
                        sizeBytes = size,
                        isAccessible = true,
                        volumeLabel = volumeLabel,
                        classification = ImageClassification.WINDOWS_UEFI_SINGLE_FAT32,
                        writeStrategy = WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32,
                        bootSupport = BootSupportSummary(
                            uefiSupported = true,
                            legacyBiosSupported = false,
                            secureBootFriendly = true,
                            isGuaranteedBootable = true,
                            headline = "Windows UEFI Bootable (MBR + Single FAT32)",
                            technicalExplanation = "All files in this Windows ISO fit within FAT32 (largest file: ${ByteUtils.formatBytes(largestSize)}). " +
                                "The drive will be formatted as a single active FAT32 partition and all Windows installation files will be extracted directly over USB SCSI."
                        ),
                        hasMbr = hasMbrSignature && hasValidMbrPartition,
                        hasGpt = hasGpt,
                        hasIso9660 = hasIso9660,
                        hasUdf = hasUdf,
                        hasElTorito = hasElTorito,
                        largestFileName = largestFile?.path,
                        largestFileSize = largestSize,
                        extractedTotalBytes = extractedTotalBytes,
                        extractedFileCount = allFiles.size,
                        fat32BootBytesNeeded = extractedTotalBytes,
                        warnings = warnings
                    )
                }
            }

            // Case B: Hybrid Linux ISO (Ubuntu, Fedora, Debian, Arch, Mint, Kali, etc.)
            isRawBootableHybrid && (hasIso9660 || hasUdf) -> {
                val uefiCapable = hasGpt || hasEfiBootloader || hasProtectiveGptMbr
                SelectedIsoInfo(
                    uriString = uriString,
                    displayName = displayName,
                    sizeBytes = size,
                    isAccessible = true,
                    volumeLabel = volumeLabel,
                    classification = ImageClassification.HYBRID_LINUX_ISO,
                    writeStrategy = WriteStrategy.RAW_SECTOR_WRITE,
                    bootSupport = BootSupportSummary(
                        uefiSupported = uefiCapable,
                        legacyBiosSupported = hasMbrSignature,
                        secureBootFriendly = uefiCapable,
                        isGuaranteedBootable = true,
                        headline = if (uefiCapable && hasMbrSignature) {
                            "Linux Hybrid ISO (BIOS + UEFI Raw Sector Write)"
                        } else if (uefiCapable) {
                            "Linux Hybrid ISO (UEFI Raw Sector Write)"
                        } else {
                            "Linux Hybrid ISO (Legacy BIOS Raw Sector Write)"
                        },
                        technicalExplanation = "Detected isohybrid partition structures (MBR=${hasMbrSignature}, GPT=${hasGpt}) inside ISO image. " +
                            "Image will be written bit-for-bit starting at LBA 0 using direct USB SCSI WRITE commands, preserving its native bootloader and partitions."
                    ),
                    hasMbr = hasMbrSignature && hasValidMbrPartition,
                    hasGpt = hasGpt,
                    hasIso9660 = hasIso9660,
                    hasUdf = hasUdf,
                    hasElTorito = hasElTorito,
                    largestFileName = largestFile?.path,
                    largestFileSize = largestSize,
                    extractedTotalBytes = extractedTotalBytes,
                    extractedFileCount = allFiles.size,
                    warnings = warnings
                )
            }

            // Case C: Raw Disk Image (.img with MBR and/or GPT, e.g., Raspberry Pi OS, FreeBSD, OpenWrt)
            isRawBootableHybrid -> {
                SelectedIsoInfo(
                    uriString = uriString,
                    displayName = displayName,
                    sizeBytes = size,
                    isAccessible = true,
                    volumeLabel = volumeLabel,
                    classification = ImageClassification.RAW_DISK_IMAGE,
                    writeStrategy = WriteStrategy.RAW_SECTOR_WRITE,
                    bootSupport = BootSupportSummary(
                        uefiSupported = hasGpt,
                        legacyBiosSupported = hasMbrSignature,
                        secureBootFriendly = hasGpt,
                        isGuaranteedBootable = true,
                        headline = "Raw Disk Image (Direct Sector Write)",
                        technicalExplanation = "Detected valid partition table (MBR=${hasMbrSignature}, GPT=${hasGpt}). " +
                            "Image will be written raw starting at LBA 0 via USB SCSI block writes."
                    ),
                    hasMbr = hasMbrSignature && hasValidMbrPartition,
                    hasGpt = hasGpt,
                    hasIso9660 = false,
                    hasUdf = false,
                    hasElTorito = false,
                    warnings = warnings
                )
            }

            // Case D: Non-hybrid ISO that STILL contains standard UEFI bootloader (/efi/boot/boot*.efi) and all files <= 4 GiB
            hasEfiBootloader && !hasOversizedFat32File && allFiles.isNotEmpty() -> {
                warnings.add(
                    "This ISO is not isohybrid, but contains UEFI boot files (/efi/boot/). Extracting to FAT32 enables UEFI boot (Legacy BIOS not supported)."
                )
                SelectedIsoInfo(
                    uriString = uriString,
                    displayName = displayName,
                    sizeBytes = size,
                    isAccessible = true,
                    volumeLabel = volumeLabel,
                    classification = ImageClassification.UEFI_EXTRACTABLE_ISO,
                    writeStrategy = WriteStrategy.WINDOWS_UEFI_SINGLE_FAT32,
                    bootSupport = BootSupportSummary(
                        uefiSupported = true,
                        legacyBiosSupported = false,
                        secureBootFriendly = true,
                        isGuaranteedBootable = true,
                        headline = "UEFI-Extractable ISO (MBR + FAT32 Extraction)",
                        technicalExplanation = "No MBR/GPT hybrid header was found, but standard UEFI boot files were detected. " +
                            "Formatting the USB drive as FAT32 and extracting all ISO contents allows UEFI firmware to boot the media."
                    ),
                    hasMbr = false,
                    hasGpt = false,
                    hasIso9660 = hasIso9660,
                    hasUdf = hasUdf,
                    hasElTorito = hasElTorito,
                    largestFileName = largestFile?.path,
                    largestFileSize = largestSize,
                    extractedTotalBytes = extractedTotalBytes,
                    extractedFileCount = allFiles.size,
                    fat32BootBytesNeeded = extractedTotalBytes,
                    warnings = warnings
                )
            }

            // Case E: Non-hybrid optical-only ISO (e.g., old BIOS-only CD ISO or Windows ISO missing EFI files)
            hasIso9660 || hasUdf -> {
                warnings.add(
                    "NOT BOOTABLE AS RAW USB: This optical ISO lacks both isohybrid MBR/GPT headers and UEFI /efi/boot/ bootloaders. " +
                        "Writing it raw to a USB flash drive will NOT boot on most PCs."
                )
                SelectedIsoInfo(
                    uriString = uriString,
                    displayName = displayName,
                    sizeBytes = size,
                    isAccessible = true,
                    volumeLabel = volumeLabel,
                    classification = ImageClassification.NON_HYBRID_OPTICAL_ISO,
                    writeStrategy = WriteStrategy.RAW_SECTOR_WRITE,
                    bootSupport = BootSupportSummary(
                        uefiSupported = false,
                        legacyBiosSupported = false,
                        secureBootFriendly = false,
                        isGuaranteedBootable = false,
                        headline = "Non-Hybrid Optical ISO (Not Guaranteed Bootable on USB)",
                        technicalExplanation = "This ISO contains only optical disc descriptors (ISO-9660/UDF/El Torito) without an isohybrid MBR/GPT " +
                            "table or UEFI /efi/boot/ bootloader. Raw writing is available for archival/advanced use, but PCs will likely NOT boot from this USB."
                    ),
                    hasMbr = false,
                    hasGpt = false,
                    hasIso9660 = hasIso9660,
                    hasUdf = hasUdf,
                    hasElTorito = hasElTorito,
                    largestFileName = largestFile?.path,
                    largestFileSize = largestSize,
                    extractedTotalBytes = extractedTotalBytes,
                    extractedFileCount = allFiles.size,
                    warnings = warnings
                )
            }

            // Case F: Unrecognized raw binary file
            else -> {
                warnings.add(
                    "No MBR, GPT, ISO-9660, or UDF boot signature was detected in this file. Verify you selected a valid OS image before writing."
                )
                SelectedIsoInfo(
                    uriString = uriString,
                    displayName = displayName,
                    sizeBytes = size,
                    isAccessible = true,
                    volumeLabel = volumeLabel,
                    classification = ImageClassification.UNKNOWN_RAW_IMAGE,
                    writeStrategy = WriteStrategy.RAW_SECTOR_WRITE,
                    bootSupport = BootSupportSummary(
                        uefiSupported = false,
                        legacyBiosSupported = false,
                        secureBootFriendly = false,
                        isGuaranteedBootable = false,
                        headline = "Unrecognized Image Format (Not Verified Bootable)",
                        technicalExplanation = "No standard disk partition table or ISO boot header was detected. " +
                            "Raw writing will copy bytes verbatim to LBA 0, but bootability cannot be guaranteed."
                    ),
                    hasMbr = false,
                    hasGpt = false,
                    hasIso9660 = false,
                    hasUdf = false,
                    hasElTorito = false,
                    warnings = warnings
                )
            }
        }
    }

    /**
     * Determines which files from a Windows ISO must live on Partition 1 (FAT32 EFI System Partition)
     * in dual-partition mode so UEFI firmware boots Windows Boot Manager and loads sources/boot.wim
     * into RAM before Windows Setup locates sources/install.wim on Partition 2 (exFAT).
     */
    fun isWindowsEspBootFile(normalizedLowerPath: String): Boolean {
        return normalizedLowerPath.startsWith("efi/") ||
            normalizedLowerPath.startsWith("boot/") ||
            normalizedLowerPath == "bootmgr" ||
            normalizedLowerPath == "bootmgr.efi" ||
            normalizedLowerPath == "sources/boot.wim"
    }
}
