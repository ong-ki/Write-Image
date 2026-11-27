package com.usbbootable.creator.core.usb

import com.usbbootable.creator.core.util.ByteUtils

data class ScsiSenseData(
    val responseCode: Int,
    val senseKey: Int,
    val asc: Int,
    val ascq: Int
) {
    val isUnitAttention: Boolean get() = senseKey == 0x06
    val isNotReady: Boolean get() = senseKey == 0x02
    val isIllegalRequest: Boolean get() = senseKey == 0x05
    val isDataProtect: Boolean get() = senseKey == 0x07
    val isMediumNotPresent: Boolean get() = senseKey == 0x02 && asc == 0x3A
    val isBecomingReady: Boolean get() = senseKey == 0x02 && asc == 0x04 && ascq == 0x01

    fun describe(): String {
        val keyName = when (senseKey) {
            0x00 -> "NO SENSE"
            0x01 -> "RECOVERED ERROR"
            0x02 -> "NOT READY"
            0x03 -> "MEDIUM ERROR"
            0x04 -> "HARDWARE ERROR"
            0x05 -> "ILLEGAL REQUEST"
            0x06 -> "UNIT ATTENTION"
            0x07 -> "DATA PROTECT (WRITE PROTECTED)"
            0x0B -> "ABORTED COMMAND"
            else -> "SENSE 0x%02X".format(senseKey)
        }
        val detail = when {
            senseKey == 0x02 && asc == 0x3A -> "Medium not present"
            senseKey == 0x02 && asc == 0x04 -> "Logical unit becoming ready"
            senseKey == 0x07 && asc == 0x27 -> "Write protected media"
            senseKey == 0x06 && asc == 0x28 -> "Medium may have changed"
            senseKey == 0x06 && asc == 0x29 -> "Power-on or bus reset occurred"
            senseKey == 0x05 && asc == 0x21 -> "Logical block address out of range"
            senseKey == 0x05 && asc == 0x20 -> "Invalid command operation code"
            senseKey == 0x03 -> "Media read/write defect on flash memory"
            else -> "ASC=0x%02X ASCQ=0x%02X".format(asc, ascq)
        }
        return "$keyName ($detail)"
    }
}

object ScsiCommandSet {

    const val OP_TEST_UNIT_READY: Byte = 0x00
    const val OP_REQUEST_SENSE: Byte = 0x03
    const val OP_INQUIRY: Byte = 0x12
    const val OP_MODE_SENSE_6: Byte = 0x1A
    const val OP_START_STOP_UNIT: Byte = 0x1B
    const val OP_READ_CAPACITY_10: Byte = 0x25
    const val OP_READ_10: Byte = 0x28
    const val OP_WRITE_10: Byte = 0x2A
    const val OP_SYNCHRONIZE_CACHE_10: Byte = 0x35
    const val OP_READ_16: Byte = 0x88.toByte()
    const val OP_WRITE_16: Byte = 0x8A.toByte()
    const val OP_SERVICE_ACTION_IN_16: Byte = 0x9E.toByte()
    const val SA_READ_CAPACITY_16: Byte = 0x10

    fun testUnitReady(): ByteArray = ByteArray(6).apply {
        this[0] = OP_TEST_UNIT_READY
    }

    fun requestSense(allocationLength: Int = 18): ByteArray = ByteArray(6).apply {
        this[0] = OP_REQUEST_SENSE
        this[4] = (allocationLength and 0xFF).toByte()
    }

    fun inquiry(allocationLength: Int = 36): ByteArray = ByteArray(6).apply {
        this[0] = OP_INQUIRY
        this[4] = (allocationLength and 0xFF).toByte()
    }

    fun modeSense6(pageCode: Int = 0x3F, allocationLength: Int = 192): ByteArray = ByteArray(6).apply {
        this[0] = OP_MODE_SENSE_6
        this[2] = (pageCode and 0x3F).toByte()
        this[4] = (allocationLength and 0xFF).toByte()
    }

    fun readCapacity10(): ByteArray = ByteArray(10).apply {
        this[0] = OP_READ_CAPACITY_10
    }

    fun readCapacity16(allocationLength: Int = 32): ByteArray = ByteArray(16).apply {
        this[0] = OP_SERVICE_ACTION_IN_16
        this[1] = SA_READ_CAPACITY_16
        ByteUtils.writeI32BE(this, 10, allocationLength)
    }

    fun read10(lba: Long, blockCount: Int): ByteArray {
        require(lba in 0L..0xFFFFFFFFL) { "LBA out of READ(10) range: $lba" }
        require(blockCount in 1..0xFFFF) { "Block count out of READ(10) range: $blockCount" }
        return ByteArray(10).apply {
            this[0] = OP_READ_10
            ByteUtils.writeI32BE(this, 2, lba.toInt())
            ByteUtils.writeU16BE(this, 7, blockCount)
        }
    }

    fun synchronizeCache10(): ByteArray = ByteArray(10).apply {
        this[0] = OP_SYNCHRONIZE_CACHE_10
    }

    fun startStopUnit(
        start: Boolean = false,
        loadEject: Boolean = true,
        immediate: Boolean = false
    ): ByteArray = ByteArray(6).apply {
        this[0] = OP_START_STOP_UNIT
        this[1] = if (immediate) 0x01 else 0x00
        this[4] = ((if (loadEject) 0x02 else 0x00) or (if (start) 0x01 else 0x00)).toByte()
    }

    fun parseInquiry(data: ByteArray): ScsiInquiryData {
        if (data.size < 36) {
            throw UsbStorageException("INQUIRY response too short: ${data.size} bytes")
        }
        val peripheralType = data[0].toInt() and 0x1F
        val removable = (data[1].toInt() and 0x80) != 0
        val vendor = String(data, 8, 8, Charsets.US_ASCII).trim()
        val product = String(data, 16, 16, Charsets.US_ASCII).trim()
        val revision = String(data, 32, 4, Charsets.US_ASCII).trim()
        return ScsiInquiryData(
            peripheralDeviceType = peripheralType,
            isRemovable = removable,
            vendor = vendor,
            product = product,
            revision = revision
        )
    }

    fun parseSense(data: ByteArray, validLength: Int = data.size): ScsiSenseData {
        val len = validLength.coerceIn(0, data.size)
        if (len == 0) {
            return ScsiSenseData(responseCode = 0, senseKey = 0, asc = 0, ascq = 0)
        }
        val responseCode = data[0].toInt() and 0x7F
        return if (responseCode == 0x72 || responseCode == 0x73) {
            // Descriptor format sense data (header is 4+ bytes)
            ScsiSenseData(
                responseCode = responseCode,
                senseKey = if (len > 1) data[1].toInt() and 0x0F else 0,
                asc = if (len > 2) data[2].toInt() and 0xFF else 0,
                ascq = if (len > 3) data[3].toInt() and 0xFF else 0
            )
        } else {
            // Fixed format sense data (0x70 / 0x71)
            ScsiSenseData(
                responseCode = responseCode,
                senseKey = if (len > 2) data[2].toInt() and 0x0F else 0,
                asc = if (len > 12) data[12].toInt() and 0xFF else 0,
                ascq = if (len > 13) data[13].toInt() and 0xFF else 0
            )
        }
    }

    /**
     * Parses READ CAPACITY (10): returns Pair(totalBlocks, blockSize).
     * If lastLba == 0xFFFFFFFFL, returns Pair(-1L, blockSize) to signal READ CAPACITY (16) is required.
     */
    fun parseReadCapacity10(data: ByteArray): Pair<Long, Int> {
        if (data.size < 8) {
            throw UsbStorageException("READ CAPACITY (10) response too short: ${data.size} bytes")
        }
        val lastLba = ByteUtils.readU32BE(data, 0)
        val blockSize = ByteUtils.readI32BE(data, 4)
        if (lastLba == 0xFFFFFFFFL) {
            return Pair(-1L, blockSize)
        }
        return Pair(lastLba + 1L, blockSize)
    }

    fun parseReadCapacity16(data: ByteArray): Pair<Long, Int> {
        if (data.size < 12) {
            throw UsbStorageException("READ CAPACITY (16) response too short: ${data.size} bytes")
        }
        val lastLba = ByteUtils.readI64BE(data, 0)
        if (lastLba < 0L || lastLba == Long.MAX_VALUE) {
            throw UsbStorageException("READ CAPACITY (16) lastLba out of range: $lastLba")
        }
        val blockSize = ByteUtils.readI32BE(data, 8)
        return Pair(lastLba + 1L, blockSize)
    }
}
