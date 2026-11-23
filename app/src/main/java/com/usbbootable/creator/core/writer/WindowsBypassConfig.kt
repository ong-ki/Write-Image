package com.usbbootable.creator.core.writer

import java.util.Locale

/**
 * User-selected Windows Setup hardware requirements bypass options.
 * All checks default to false so security/compatibility checks are never silently bypassed.
 */
data class WindowsBypassOptions(
    val bypassTpm: Boolean = false,
    val bypassSecureBoot: Boolean = false,
    val bypassRam: Boolean = false,
    val bypassCpu: Boolean = false
) {
    val allEnabled: Boolean
        get() = bypassTpm && bypassSecureBoot && bypassRam && bypassCpu

    val hasAnyEnabled: Boolean
        get() = bypassTpm || bypassSecureBoot || bypassRam || bypassCpu

    fun withAllEnabled(enabled: Boolean): WindowsBypassOptions = WindowsBypassOptions(
        bypassTpm = enabled,
        bypassSecureBoot = enabled,
        bypassRam = enabled,
        bypassCpu = enabled
    )

    fun enabledLabels(): List<String> = buildList {
        if (bypassTpm) add("TPM 2.0")
        if (bypassSecureBoot) add("Secure Boot")
        if (bypassRam) add("RAM")
        if (bypassCpu) add("CPU")
    }

    companion object {
        val NONE = WindowsBypassOptions()
        val ALL = WindowsBypassOptions(
            bypassTpm = true,
            bypassSecureBoot = true,
            bypassRam = true,
            bypassCpu = true
        )
    }
}

/**
 * In-memory generated file to write onto the target USB installation media without
 * modifying the user's original source ISO image.
 */
data class GeneratedBypassFile(
    val path: String,
    val content: ByteArray,
    val includeOnEsp: Boolean
) {
    val normalizedPathLower: String
        get() = path.trimStart('/').lowercase(Locale.ROOT)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GeneratedBypassFile) return false
        return path == other.path &&
            includeOnEsp == other.includeOnEsp &&
            content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + content.contentHashCode()
        result = 31 * result + includeOnEsp.hashCode()
        return result
    }
}

/**
 * Generates documented Windows Setup compatibility bypass artifacts for Windows 10/11 USB media:
 *
 * 1. `autounattend.xml` (placed on root of installer partition, and on FAT32 ESP in GPT dual-partition mode):
 *    - `windowsPE` pass (`Microsoft-Windows-Setup/RunSynchronous`): injects ONLY user-selected
 *      `HKLM\SYSTEM\Setup\LabConfig` (and `MoSetup` for TPM/CPU) keys before Setup evaluates hardware checks.
 *    - `specialize` pass (`Microsoft-Windows-Deployment/RunSynchronous`): persists the selected keys
 *      into the installed OS so post-reboot Setup stages recognize them without interfering with interactive OOBE.
 *
 * 2. `sources/win_requirements_bypass.reg` & `sources/setup_with_bypass.cmd` (installer partition):
 *    - Supports in-place upgrade scenarios started from an existing Windows desktop where WinPE's
 *      `autounattend.xml` pass is not automatically invoked.
 */
object WindowsBypassGenerator {

    const val AUTOUNATTEND_PATH = "autounattend.xml"
    const val BYPASS_REG_PATH = "sources/win_requirements_bypass.reg"
    const val BYPASS_CMD_PATH = "sources/setup_with_bypass.cmd"

    private data class BypassRegistryEntry(
        val keyPath: String,
        val regFullKeyPath: String,
        val valueName: String,
        val description: String
    )

    fun detectArchitectures(lowerIsoPaths: Set<String>): List<String> {
        val detected = mutableListOf<String>()
        if (lowerIsoPaths.contains("efi/boot/bootx64.efi")) detected.add("amd64")
        if (lowerIsoPaths.contains("efi/boot/bootaa64.efi")) detected.add("arm64")
        if (lowerIsoPaths.contains("efi/boot/bootia32.efi")) detected.add("x86")
        return if (detected.isNotEmpty()) detected else listOf("amd64")
    }

    fun generateFiles(
        options: WindowsBypassOptions,
        lowerIsoPaths: Set<String>
    ): List<GeneratedBypassFile> {
        if (!options.hasAnyEnabled) return emptyList()

        val architectures = detectArchitectures(lowerIsoPaths)
        val autounattendBytes = buildAutounattendXml(options, architectures).toByteArray(Charsets.UTF_8)
        val regBytes = buildRegistryFile(options).toByteArray(Charsets.UTF_8)
        val cmdBytes = buildUpgradeHelperScript(options).toByteArray(Charsets.UTF_8)

        return listOf(
            GeneratedBypassFile(
                path = AUTOUNATTEND_PATH,
                content = autounattendBytes,
                includeOnEsp = true
            ),
            GeneratedBypassFile(
                path = BYPASS_REG_PATH,
                content = regBytes,
                includeOnEsp = false
            ),
            GeneratedBypassFile(
                path = BYPASS_CMD_PATH,
                content = cmdBytes,
                includeOnEsp = false
            )
        )
    }

    private fun selectedRegistryEntries(options: WindowsBypassOptions): List<BypassRegistryEntry> = buildList {
        if (options.bypassTpm) {
            add(
                BypassRegistryEntry(
                    keyPath = "HKLM\\SYSTEM\\Setup\\LabConfig",
                    regFullKeyPath = "HKEY_LOCAL_MACHINE\\SYSTEM\\Setup\\LabConfig",
                    valueName = "BypassTPMCheck",
                    description = "Bypass TPM 2.0 Check"
                )
            )
        }
        if (options.bypassSecureBoot) {
            add(
                BypassRegistryEntry(
                    keyPath = "HKLM\\SYSTEM\\Setup\\LabConfig",
                    regFullKeyPath = "HKEY_LOCAL_MACHINE\\SYSTEM\\Setup\\LabConfig",
                    valueName = "BypassSecureBootCheck",
                    description = "Bypass Secure Boot Check"
                )
            )
        }
        if (options.bypassRam) {
            add(
                BypassRegistryEntry(
                    keyPath = "HKLM\\SYSTEM\\Setup\\LabConfig",
                    regFullKeyPath = "HKEY_LOCAL_MACHINE\\SYSTEM\\Setup\\LabConfig",
                    valueName = "BypassRAMCheck",
                    description = "Bypass RAM Requirement"
                )
            )
        }
        if (options.bypassCpu) {
            add(
                BypassRegistryEntry(
                    keyPath = "HKLM\\SYSTEM\\Setup\\LabConfig",
                    regFullKeyPath = "HKEY_LOCAL_MACHINE\\SYSTEM\\Setup\\LabConfig",
                    valueName = "BypassCPUCheck",
                    description = "Bypass CPU Compatibility Check"
                )
            )
        }
        if (options.bypassTpm || options.bypassCpu) {
            add(
                BypassRegistryEntry(
                    keyPath = "HKLM\\SYSTEM\\Setup\\MoSetup",
                    regFullKeyPath = "HKEY_LOCAL_MACHINE\\SYSTEM\\Setup\\MoSetup",
                    valueName = "AllowUpgradesWithUnsupportedTPMOrCPU",
                    description = "Allow Upgrades With Unsupported TPM or CPU"
                )
            )
        }
    }

    fun buildAutounattendXml(
        options: WindowsBypassOptions,
        architectures: List<String> = listOf("amd64")
    ): String {
        val entries = selectedRegistryEntries(options)
        require(entries.isNotEmpty()) { "At least one bypass option must be enabled." }
        val archList = architectures.ifEmpty { listOf("amd64") }.distinct()

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
        sb.append("<unattend xmlns=\"urn:schemas-microsoft-com:unattend\">\r\n")

        // Pass 1: windowsPE — executes before Windows Setup checks hardware requirements
        sb.append("  <settings pass=\"windowsPE\">\r\n")
        for (arch in archList) {
            sb.append(
                "    <component name=\"Microsoft-Windows-Setup\" processorArchitecture=\"$arch\" " +
                    "publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\" " +
                    "xmlns:wcm=\"http://schemas.microsoft.com/WMIConfig/2002/State\" " +
                    "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n"
            )
            sb.append("      <RunSynchronous>\r\n")
            entries.forEachIndexed { idx, entry ->
                sb.append("        <RunSynchronousCommand wcm:action=\"add\">\r\n")
                sb.append("          <Order>${idx + 1}</Order>\r\n")
                sb.append("          <Description>${entry.description}</Description>\r\n")
                sb.append("          <Path>reg add ${entry.keyPath} /v ${entry.valueName} /t REG_DWORD /d 1 /f</Path>\r\n")
                sb.append("        </RunSynchronousCommand>\r\n")
            }
            sb.append("      </RunSynchronous>\r\n")
            sb.append("    </component>\r\n")
        }
        sb.append("  </settings>\r\n")

        // Pass 2: specialize — persists selected bypass keys into the installed OS without altering interactive OOBE
        sb.append("  <settings pass=\"specialize\">\r\n")
        for (arch in archList) {
            sb.append(
                "    <component name=\"Microsoft-Windows-Deployment\" processorArchitecture=\"$arch\" " +
                    "publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" versionScope=\"nonSxS\" " +
                    "xmlns:wcm=\"http://schemas.microsoft.com/WMIConfig/2002/State\" " +
                    "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n"
            )
            sb.append("      <RunSynchronous>\r\n")
            entries.forEachIndexed { idx, entry ->
                sb.append("        <RunSynchronousCommand wcm:action=\"add\">\r\n")
                sb.append("          <Order>${idx + 1}</Order>\r\n")
                sb.append("          <Description>Persist ${entry.description}</Description>\r\n")
                sb.append("          <Path>reg add ${entry.keyPath} /v ${entry.valueName} /t REG_DWORD /d 1 /f</Path>\r\n")
                sb.append("        </RunSynchronousCommand>\r\n")
            }
            sb.append("      </RunSynchronous>\r\n")
            sb.append("    </component>\r\n")
        }
        sb.append("  </settings>\r\n")

        sb.append("</unattend>\r\n")
        return sb.toString()
    }

    fun buildRegistryFile(options: WindowsBypassOptions): String {
        val entries = selectedRegistryEntries(options)
        require(entries.isNotEmpty()) { "At least one bypass option must be enabled." }

        val sb = StringBuilder()
        sb.append("Windows Registry Editor Version 5.00\r\n")
        val grouped = entries.groupBy { it.regFullKeyPath }
        for ((section, items) in grouped) {
            sb.append("\r\n[$section]\r\n")
            for (item in items) {
                sb.append("\"${item.valueName}\"=dword:00000001\r\n")
            }
        }
        return sb.toString()
    }

    fun buildUpgradeHelperScript(options: WindowsBypassOptions): String {
        val labels = options.enabledLabels().joinToString(", ")
        return buildString {
            append("@echo off\r\n")
            append("rem Windows Requirements Bypass helper generated by Write-Image\r\n")
            append("rem Selected bypasses: $labels\r\n")
            append("net session >nul 2>&1\r\n")
            append("if %errorlevel% neq 0 (\r\n")
            append("  echo Please run this script as Administrator to apply selected Windows Setup bypasses.\r\n")
            append("  pause\r\n")
            append("  exit /b 1\r\n")
            append(")\r\n")
            append("reg import \"%~dp0win_requirements_bypass.reg\"\r\n")
            append("if exist \"%~dp0..\\setup.exe\" (\r\n")
            append("  \"%~dp0..\\setup.exe\" %*\r\n")
            append(")\r\n")
        }
    }
}
