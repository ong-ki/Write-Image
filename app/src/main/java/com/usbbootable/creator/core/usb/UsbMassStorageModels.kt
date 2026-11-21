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
