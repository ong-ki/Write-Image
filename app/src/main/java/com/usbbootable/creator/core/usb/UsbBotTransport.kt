package com.usbbootable.creator.core.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Build
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.OperationLogger
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

enum class BotDirection(val cbwFlag: Byte) {
    NONE(0x00),
    IN(0x80.toByte()),
    OUT(0x00)
}

data class BotCommandResult(
    val status: Int,
    val dataResidue: Int,
    val bytesTransferred: Int
) {
    val isSuccess: Boolean get() = status == UsbBotTransport.CSW_STATUS_PASSED
    val isCommandFailed: Boolean get() = status == UsbBotTransport.CSW_STATUS_FAILED
}

data class BotResetRecoveryStatus(
    val resetResult: Int,
    val clearInResult: Int,
    val clearOutResult: Int
) {
    fun describe(): String =
        "BOT Reset Recovery [reset=$resetResult, clearIn=$clearInResult, clearOut=$clearOutResult]"
}

class UsbBotCommandException(
    val commandName: String,
    val endpointInfo: String,
    val transferredBytes: Int,
    val expectedBytes: Int,
    val usbErrorCode: Int,
    val recoveryStatus: String,
    val resetPerformed: Boolean,
    cause: Throwable? = null
) : UsbStorageException(
    buildDiagnosticMessage(
        commandName = commandName,
        endpointInfo = endpointInfo,
        transferredBytes = transferredBytes,
        expectedBytes = expectedBytes,
        usbErrorCode = usbErrorCode,
        recoveryStatus = recoveryStatus
    ),
    cause
) {
    companion object {
        fun buildDiagnosticMessage(
            commandName: String,
            endpointInfo: String,
            transferredBytes: Int,
            expectedBytes: Int,
            usbErrorCode: Int,
            recoveryStatus: String
        ): String {
            return "USB transfer failed on $commandName | " +
                "Endpoints: $endpointInfo | " +
                "Transferred: $transferredBytes/$expectedBytes B | " +
                "USB code: $usbErrorCode | " +
                "Recovery: $recoveryStatus"
        }
    }
}

/**
 * Low-level USB transport abstraction so [UsbBotTransport] can run against real Android
 * [UsbDeviceConnection] hardware and also be verified directly in JVM unit tests.
 */
interface UsbBulkIo {
    val interfaceId: Int
    val bulkInAddress: Int
    val bulkOutAddress: Int
    val maxBulkChunkBytes: Int
    val bulkInMaxPacketSize: Int
        get() = 512
    val bulkOutMaxPacketSize: Int
        get() = 512

    fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeoutMs: Int
    ): Int

    fun isConnected(): Boolean

    fun endpointSummary(): String =
        "IF#$interfaceId IN=0x%02X(%dB) OUT=0x%02X(%dB)".format(
            bulkInAddress,
            bulkInMaxPacketSize,
            bulkOutAddress,
            bulkOutMaxPacketSize
        )
}

class AndroidUsbBulkIo(
    private val connection: UsbDeviceConnection,
    override val interfaceId: Int,
    private val bulkInEndpoint: UsbEndpoint,
    private val bulkOutEndpoint: UsbEndpoint,
    private val disconnectCheck: () -> Boolean
) : UsbBulkIo {

    override val bulkInAddress: Int = bulkInEndpoint.address
    override val bulkOutAddress: Int = bulkOutEndpoint.address
    override val bulkInMaxPacketSize: Int = bulkInEndpoint.maxPacketSize.coerceAtLeast(64)
    override val bulkOutMaxPacketSize: Int = bulkOutEndpoint.maxPacketSize.coerceAtLeast(64)

    val isSuperSpeed: Boolean = bulkOutEndpoint.maxPacketSize >= 1024

    // Keep individual usbdevfs URB bulkTransfer slices bounded (16 KiB on API < 28,
    // 32 KiB on USB 2.0, 64 KiB on USB 3.x) so kernel scatter-gather never overflows
    // and cancellation latency remains within a few milliseconds.
    override val maxBulkChunkBytes: Int = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> 16_384
        isSuperSpeed -> 65_536
        else -> 32_768
    }

    override fun bulkOut(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        return connection.bulkTransfer(bulkOutEndpoint, buffer, offset, length, timeoutMs)
    }

    override fun bulkIn(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        return connection.bulkTransfer(bulkInEndpoint, buffer, offset, length, timeoutMs)
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
        timeoutMs: Int
    ): Int {
        return connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
    }

    override fun isConnected(): Boolean = !disconnectCheck()
}

/**
 * Implements the USB-IF Mass Storage Class Bulk-Only Transport (BOT 1.0) protocol
 * with serialized endpoint access, partial-transfer loops, stray-packet draining,
 * and compliant Reset Recovery + Halt Clear sequencing.
 */
class UsbBotTransport(
    private val io: UsbBulkIo,
    private val logger: OperationLogger? = null,
    private val transferTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val isCancelled: () -> Boolean = { false },
    private val sleepMs: (Long) -> Unit = { ms -> if (ms > 0L) Thread.sleep(ms) }
) {
    private val transportLock = Any()
    private val tagCounter = AtomicInteger(1)
    private val cbwBuffer = ByteArray(CBW_SIZE)
    private val cswBuffer = ByteArray(CSW_SIZE)

    val endpointSummary: String
        get() = io.endpointSummary()

    fun getMaxLun(): Int = synchronized(transportLock) {
        val buf = ByteArray(1)
        val ret = io.controlTransfer(
            requestType = 0xA1, // Device-to-Host | Class | Interface
            request = 0xFE,     // GET_MAX_LUN
            value = 0,
            index = io.interfaceId,
            buffer = buf,
            length = 1,
            timeoutMs = 2_000
        )
        return if (ret == 1) {
            val maxLun = buf[0].toInt() and 0x0F
            logger?.info(TAG, "GET_MAX_LUN returned $maxLun")
            maxLun
        } else {
            // Per USB MSC BOT 1.0 §3.2, single-LUN drives are permitted to STALL GET_MAX_LUN.
            logger?.info(TAG, "GET_MAX_LUN stalled/unsupported (ret=$ret); defaulting to LUN 0")
            0
        }
    }

    /**
     * Executes full USB MSC BOT 1.0 §5.3.4 Reset Recovery:
     * 1. Class-specific Bulk-Only Mass Storage Reset (0x21, 0xFF)
     * 2. Controller settle delay
     * 3. CLEAR_FEATURE(ENDPOINT_HALT) on Bulk-IN
     * 4. CLEAR_FEATURE(ENDPOINT_HALT) on Bulk-OUT
     * 5. Post-recovery settle delay before next CBW
     */
    fun resetRecovery(): BotResetRecoveryStatus = synchronized(transportLock) {
        logger?.warn(TAG, "Performing USB BOT Mass Storage Reset Recovery on ${io.endpointSummary()}")
        val resetRc = io.controlTransfer(
            requestType = 0x21, // Host-to-Device | Class | Interface
            request = 0xFF,     // BULK_ONLY_MASS_STORAGE_RESET
            value = 0,
            index = io.interfaceId,
            buffer = null,
            length = 0,
            timeoutMs = 5_000
        )
        sleepMs(RESET_SETTLE_DELAY_MS)
        val clearInRc = clearEndpointHalt(io.bulkInAddress)
        sleepMs(HALT_CLEAR_DELAY_MS)
        val clearOutRc = clearEndpointHalt(io.bulkOutAddress)
        sleepMs(RESET_SETTLE_DELAY_MS)
        val status = BotResetRecoveryStatus(
            resetResult = resetRc,
            clearInResult = clearInRc,
            clearOutResult = clearOutRc
        )
        logger?.info(TAG, status.describe())
        return status
    }

    fun clearEndpointHalt(endpointAddress: Int): Int = synchronized(transportLock) {
        return io.controlTransfer(
            requestType = 0x02, // Host-to-Device | Standard | Endpoint
            request = 0x01,     // CLEAR_FEATURE
            value = 0x0000,     // ENDPOINT_HALT
            index = endpointAddress,
            buffer = null,
            length = 0,
            timeoutMs = 2_000
        )
    }

    private fun cancelAfterCbwIfConnected(message: String): Nothing {
        if (io.isConnected()) {
            try {
                resetRecovery()
            } catch (_: Exception) {
            }
        }
        throw CancellationException(message)
    }

    @Throws(UsbStorageException::class, CancellationException::class)
    fun executeCommand(
        lun: Int,
        cdb: ByteArray,
        direction: BotDirection,
        dataBuffer: ByteArray? = null,
        dataOffset: Int = 0,
        dataLength: Int = 0,
        commandName: String = "SCSI_OP",
        timeoutMs: Int = transferTimeoutMs
    ): BotCommandResult = synchronized(transportLock) {
        if (isCancelled()) throw CancellationException("USB transfer cancelled before command dispatch.")
        if (!io.isConnected()) throw UsbDisconnectedException()
        require(cdb.size in 1..16) { "Invalid SCSI CDB length: ${cdb.size}" }
        if (dataLength > 0) {
            requireNotNull(dataBuffer) { "dataBuffer required when dataLength > 0" }
            require(dataOffset >= 0 && dataOffset + dataLength <= dataBuffer.size) {
                "Buffer range out of bounds: offset=$dataOffset len=$dataLength size=${dataBuffer.size}"
            }
        }

        val tag = tagCounter.getAndIncrement()
        encodeCbw(
            out = cbwBuffer,
            tag = tag,
            dataTransferLength = dataLength,
            direction = direction,
            lun = lun,
            cdb = cdb
        )

        // 1. Command Transport (CBW - 31 bytes)
        val cbwSent = io.bulkOut(cbwBuffer, 0, CBW_SIZE, timeoutMs)
        if (cbwSent != CBW_SIZE) {
            if (isCancelled()) throw CancellationException("USB transfer cancelled during CBW.")
            if (!io.isConnected()) throw UsbDisconnectedException()
            val rec = resetRecovery()
            throw UsbBotCommandException(
                commandName = "$commandName (CBW stage)",
                endpointInfo = io.endpointSummary(),
                transferredBytes = cbwSent.coerceAtLeast(0),
                expectedBytes = CBW_SIZE,
                usbErrorCode = cbwSent,
                recoveryStatus = rec.describe(),
                resetPerformed = true
            )
        }

        // 2. Data Transport (chunked to respect USBFS buffer limits and support responsive cancellation)
        var totalTransferred = 0
        var dataPhaseError = false
        var lastDataErrorCode = 0

        if (dataLength > 0 && dataBuffer != null) {
            var remaining = dataLength
            var pos = dataOffset
            val maxChunk = io.maxBulkChunkBytes.coerceAtLeast(512)
            val maxInPkt = io.bulkInMaxPacketSize.coerceAtLeast(1)

            while (remaining > 0) {
                if (isCancelled()) {
                    cancelAfterCbwIfConnected("USB transfer cancelled mid-data-phase.")
                }
                if (!io.isConnected()) throw UsbDisconnectedException()
                val chunk = minOf(remaining, maxChunk)
                val xfer = when (direction) {
                    BotDirection.IN -> io.bulkIn(dataBuffer, pos, chunk, timeoutMs)
                    BotDirection.OUT -> io.bulkOut(dataBuffer, pos, chunk, timeoutMs)
                    BotDirection.NONE -> 0
                }
                if (isCancelled()) {
                    cancelAfterCbwIfConnected("USB transfer cancelled after data chunk.")
                }
                if (xfer < 0) {
                    if (!io.isConnected()) throw UsbDisconnectedException()
                    dataPhaseError = true
                    lastDataErrorCode = xfer
                    val stalledEp = if (direction == BotDirection.IN) io.bulkInAddress else io.bulkOutAddress
                    val clearRc = clearEndpointHalt(stalledEp)
                    sleepMs(HALT_CLEAR_DELAY_MS)
                    logger?.warn(
                        TAG,
                        "$commandName data-phase error (xfer=$xfer at $totalTransferred/$dataLength B); " +
                            "cleared endpoint 0x%02X halt (rc=$clearRc)".format(stalledEp)
                    )
                    break
                }
                if (xfer == 0) {
                    if (direction == BotDirection.OUT) {
                        // A 0-byte write when remaining > 0 means the device stopped accepting data
                        dataPhaseError = true
                        lastDataErrorCode = 0
                    }
                    break
                }
                totalTransferred += xfer
                pos += xfer
                remaining -= xfer

                // Only terminate Device-to-Host (IN) early on a true USB short packet
                // (not on partial multi-packet URBs where xfer is an exact multiple of wMaxPacketSize).
                if (direction == BotDirection.IN && xfer < chunk && (xfer % maxInPkt != 0)) {
                    break
                }
            }
        }

        // 3. Status Transport (CSW - 13 bytes)
        if (isCancelled()) cancelAfterCbwIfConnected("USB transfer cancelled before CSW.")
        val cswTimeout = if (direction == BotDirection.OUT) maxOf(timeoutMs, WRITE_CSW_TIMEOUT_MS) else timeoutMs
        var cswRead = readCswPacket(cswTimeout)

        if (cswRead != CSW_SIZE) {
            if (isCancelled()) cancelAfterCbwIfConnected("USB transfer cancelled during CSW.")
            if (!io.isConnected()) throw UsbDisconnectedException()

            // Per BOT 1.0 §5.3.3 Figure 2: clear Bulk-IN halt once, settle, and re-read CSW.
            val clearInRc = clearEndpointHalt(io.bulkInAddress)
            sleepMs(HALT_CLEAR_DELAY_MS)
            logger?.warn(
                TAG,
                "$commandName initial CSW read=$cswRead; cleared Bulk-IN 0x%02X halt (rc=$clearInRc), retrying CSW".format(
                    io.bulkInAddress
                )
            )
            cswRead = readCswPacket(cswTimeout)

            if (cswRead != CSW_SIZE) {
                if (isCancelled()) cancelAfterCbwIfConnected("USB transfer cancelled during CSW retry.")
                if (!io.isConnected()) throw UsbDisconnectedException()
                val rec = resetRecovery()
                val errCode = if (dataPhaseError && lastDataErrorCode != 0) lastDataErrorCode else cswRead
                throw UsbBotCommandException(
                    commandName = commandName,
                    endpointInfo = io.endpointSummary(),
                    transferredBytes = totalTransferred,
                    expectedBytes = dataLength,
                    usbErrorCode = errCode,
                    recoveryStatus = "CSW retry returned $cswRead after Bulk-IN halt clear (rc=$clearInRc); ${rec.describe()}",
                    resetPerformed = true
                )
            }
        }

        val parsed = decodeCsw(cswBuffer)
        if (parsed.signature != CSW_SIGNATURE || parsed.tag != tag) {
            val rec = resetRecovery()
            throw UsbBotCommandException(
                commandName = commandName,
                endpointInfo = io.endpointSummary(),
                transferredBytes = totalTransferred,
                expectedBytes = dataLength,
                usbErrorCode = parsed.status,
                recoveryStatus = "Invalid CSW (sig=0x%08X expected=0x%08X, tag=%d expected=%d); %s".format(
                    parsed.signature,
                    CSW_SIGNATURE,
                    parsed.tag,
                    tag,
                    rec.describe()
                ),
                resetPerformed = true
            )
        }

        if (parsed.status !in CSW_STATUS_PASSED..CSW_STATUS_PHASE_ERROR ||
            parsed.dataResidue < 0 ||
            parsed.dataResidue > dataLength
        ) {
            val rec = resetRecovery()
            throw UsbBotCommandException(
                commandName = commandName,
                endpointInfo = io.endpointSummary(),
                transferredBytes = totalTransferred,
                expectedBytes = dataLength,
                usbErrorCode = parsed.status,
                recoveryStatus = "Non-meaningful CSW (status=0x%02X, residue=${parsed.dataResidue}/$dataLength); ${rec.describe()}".format(
                    parsed.status
                ),
                resetPerformed = true
            )
        }

        if (parsed.status == CSW_STATUS_PHASE_ERROR) {
            val rec = resetRecovery()
            throw UsbBotCommandException(
                commandName = commandName,
                endpointInfo = io.endpointSummary(),
                transferredBytes = totalTransferred,
                expectedBytes = dataLength,
                usbErrorCode = CSW_STATUS_PHASE_ERROR,
                recoveryStatus = "CSW reported Phase Error (0x02); ${rec.describe()}",
                resetPerformed = true
            )
        }

        // If Data-OUT terminated early with a transport error yet CSW claimed PASSED with 0 residue,
        // do NOT silently trust a partial write!
        if (direction == BotDirection.OUT && dataPhaseError && parsed.status == CSW_STATUS_PASSED) {
            val rec = resetRecovery()
            throw UsbBotCommandException(
                commandName = commandName,
                endpointInfo = io.endpointSummary(),
                transferredBytes = totalTransferred,
                expectedBytes = dataLength,
                usbErrorCode = lastDataErrorCode,
                recoveryStatus = "Incomplete Data-OUT write ($totalTransferred/$dataLength B); ${rec.describe()}",
                resetPerformed = true
            )
        }

        return BotCommandResult(
            status = parsed.status,
            dataResidue = parsed.dataResidue,
            bytesTransferred = totalTransferred
        )
    }

    /**
     * Reads a 13-byte CSW packet from Bulk-IN.
     * If a device returns a 1..12 byte residual short packet ahead of the 13-byte CSW
     * (which causes `read=1` on some controllers), drains it once to check if the valid 13-byte
     * "USBS" CSW immediately follows in the Bulk-IN FIFO.
     */
    private fun readCswPacket(timeoutMs: Int): Int {
        cswBuffer.fill(0)
        val n = io.bulkIn(cswBuffer, 0, CSW_SIZE, timeoutMs)
        if (n == CSW_SIZE) return CSW_SIZE
        if (n in 1 until CSW_SIZE) {
            val sig = ByteUtils.readI32LE(cswBuffer, 0)
            if (sig != CSW_SIGNATURE) {
                logger?.warn(TAG, "Draining $n-byte residual Bulk-IN packet before reading 13-byte CSW")
                cswBuffer.fill(0)
                val second = io.bulkIn(cswBuffer, 0, CSW_SIZE, timeoutMs)
                if (second == CSW_SIZE) return CSW_SIZE
                return if (second < 0) n else second
            }
        }
        return n
    }

    internal data class ParsedCsw(
        val signature: Int,
        val tag: Int,
        val dataResidue: Int,
        val status: Int
    )

    companion object {
        private const val TAG = "UsbBot"
        const val CBW_SIZE = 31
        const val CSW_SIZE = 13
        const val CBW_SIGNATURE = 0x43425355 // "USBC" LE
        const val CSW_SIGNATURE = 0x53425355 // "USBS" LE
        const val CSW_STATUS_PASSED = 0x00
        const val CSW_STATUS_FAILED = 0x01
        const val CSW_STATUS_PHASE_ERROR = 0x02
        const val DEFAULT_TIMEOUT_MS = 8_000
        const val WRITE_CSW_TIMEOUT_MS = 15_000
        private const val RESET_SETTLE_DELAY_MS = 80L
        private const val HALT_CLEAR_DELAY_MS = 25L

        fun encodeCbw(
            out: ByteArray,
            tag: Int,
            dataTransferLength: Int,
            direction: BotDirection,
            lun: Int,
            cdb: ByteArray
        ) {
            out.fill(0)
            ByteUtils.writeI32LE(out, 0, CBW_SIGNATURE)
            ByteUtils.writeI32LE(out, 4, tag)
            ByteUtils.writeI32LE(out, 8, dataTransferLength)
            out[12] = direction.cbwFlag
            out[13] = (lun and 0x0F).toByte()
            out[14] = (cdb.size and 0x1F).toByte()
            System.arraycopy(cdb, 0, out, 15, cdb.size)
        }

        internal fun decodeCsw(buf: ByteArray): ParsedCsw {
            return ParsedCsw(
                signature = ByteUtils.readI32LE(buf, 0),
                tag = ByteUtils.readI32LE(buf, 4),
                dataResidue = ByteUtils.readI32LE(buf, 8),
                status = buf[12].toInt() and 0xFF
            )
        }
    }
}
