package com.usbbootable.creator.core.usb

import java.io.IOException

enum class UsbTransportSupport {
    SUPPORTED_BOT,
    UNSUPPORTED_UASP_ONLY,
    UNSUPPORTED_NON_STORAGE
}

data class ScsiInquiryData(
    val peripheralDeviceType: Int,
    val isRemovable: Boolean,
    val vendor: String,
    val product: String,
    val revision: String
) {
    val displayModel: String
        get() = listOf(vendor, product).filter { it.isNotBlank() }.joinToString(" ").ifBlank {
            "USB Mass Storage Device"
        }
}

data class UsbDriveInfo(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val transportSupport: UsbTransportSupport,
    val hasPermission: Boolean,
    val isProbed: Boolean = false,
    val probeError: String? = null,
    val scsiVendor: String? = null,
    val scsiProduct: String? = null,
    val logicalBlockSize: Int = 512,
    val totalBlocks: Long = 0L,
    val capacityBytes: Long = 0L,
    val isWriteProtected: Boolean = false,
    val lun: Int = 0
) {
    val displayName: String
        get() {
            val scsiName = listOfNotNull(scsiVendor, scsiProduct)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (scsiName.isNotEmpty()) return scsiName

            val usbName = listOfNotNull(manufacturerName, productName)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (usbName.isNotEmpty()) return usbName

            return "USB Drive (${vidPidHex})"
        }

    val vidPidHex: String
        get() = "%04X:%04X".format(vendorId, productId)
}

open class UsbStorageException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class UsbDisconnectedException(
    message: String = "USB drive was disconnected during operation."
) : UsbStorageException(message)

class UsbWriteProtectedException(
    message: String = "USB drive is write-protected (read-only)."
) : UsbStorageException(message)

class UsbClaimInterfaceException(
    message: String
) : UsbStorageException(message)

data class ScsiCommandDiagnostic(
    val commandName: String,
    val opcode: Int,
    val succeeded: Boolean,
    val cswStatus: Int? = null,
    val usbErrorCode: Int? = null,
    val senseData: ScsiSenseData? = null,
    val clearInRc: Int? = null,
    val clearOutRc: Int? = null,
    val detail: String = ""
) {
    val opcodeHex: String
        get() = "0x%02X".format(opcode and 0xFF)

    fun formatFailureSummary(): String {
        val senseText = if (senseData != null) {
            "senseKey=0x%02X/ASC=0x%02X/ASCQ=0x%02X".format(
                senseData.senseKey,
                senseData.asc,
                senseData.ascq
            )
        } else {
            "senseKey=N/A/ASC=N/A"
        }
        val usbParts = buildList {
            usbErrorCode?.let { add("usb=$it") }
            cswStatus?.let { add("csw=0x%02X".format(it and 0xFF)) }
            clearInRc?.let { add("clearIn=$it") }
            clearOutRc?.let { add("clearOut=$it") }
        }.ifEmpty { listOf("usb=N/A") }.joinToString(",")
        return "$opcodeHex($commandName) [$senseText, $usbParts]"
    }

    fun formatLogLine(): String {
        val senseText = if (senseData != null) {
            "senseKey=0x%02X, ASC=0x%02X, ASCQ=0x%02X (${senseData.describe()})".format(
                senseData.senseKey,
                senseData.asc,
                senseData.ascq
            )
        } else {
            "senseKey=N/A, ASC=N/A"
        }
        val usbParts = buildList {
            usbErrorCode?.let { add("usbCode=$it") }
            cswStatus?.let { add("cswStatus=0x%02X".format(it and 0xFF)) }
            clearInRc?.let { add("clearInRc=$it") }
            clearOutRc?.let { add("clearOutRc=$it") }
        }.ifEmpty { listOf("usbCode=N/A") }.joinToString(", ")
        val extra = if (detail.isNotBlank()) " | $detail" else ""
        return "opcode=$opcodeHex ($commandName) | $senseText | $usbParts$extra"
    }
}

data class UsbEjectResult(
    val syncCacheSucceeded: Boolean,
    val testUnitReadySucceeded: Boolean,
    val startStopUnitSucceeded: Boolean,
    val diagnostics: List<ScsiCommandDiagnostic> = emptyList(),
    val logText: String = ""
) {
    val anyCommandSucceeded: Boolean
        get() = syncCacheSucceeded || testUnitReadySucceeded || startStopUnitSucceeded

    val allCommandsFailed: Boolean
        get() = !anyCommandSucceeded

    val failingDiagnostics: List<ScsiCommandDiagnostic>
        get() = diagnostics.filterNot { it.succeeded }

    fun formatFailingHistoryNote(): String? {
        val failed = failingDiagnostics
        if (failed.isEmpty()) return null
        return failed.joinToString("; ") { it.formatFailureSummary() }
    }
}
