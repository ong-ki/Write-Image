package com.usbbootable.creator.core.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.usbbootable.creator.core.util.OperationLogger
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class BotInterfaceMatch(
    val usbInterface: UsbInterface,
    val bulkIn: UsbEndpoint,
    val bulkOut: UsbEndpoint
)

/**
 * Owns an active [UsbDeviceConnection] + claimed Mass Storage Bulk-Only Transport interface
 * and exposes a ready-to-use [ScsiBlockDevice].
 */
class UsbDeviceSession private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    val blockDevice: ScsiBlockDevice,
    val recommendedBufferBytes: Int,
    private val logger: OperationLogger?
) : Closeable {

    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    /**
     * Reuses this open write/diagnostic [UsbDeviceConnection] and claimed BOT interface
     * to run the best-effort eject command sequence before the caller closes the session.
     */
    @Throws(CancellationException::class, UsbDisconnectedException::class)
    fun eject(): UsbEjectResult {
        if (closed.get()) throw UsbStorageException("USB session is already closed.")
        val result = blockDevice.ejectUnit(forceInitialBotReset = hasBadBotState(device.deviceId))
        if (blockDevice.hasBadBotState()) {
            markBadBotState(device.deviceId)
        } else if (result.anyCommandSucceeded) {
            clearBadBotState(device.deviceId)
        }
        return result
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (blockDevice.hasBadBotState()) {
            markBadBotState(device.deviceId)
        } else {
            clearBadBotState(device.deviceId)
        }
        try {
            blockDevice.close()
        } catch (_: Exception) {
        }
        releaseConnectionSafely(
            deviceId = device.deviceId,
            connection = connection,
            usbInterface = usbInterface,
            claimed = true,
            logger = logger,
            logMessage = "Released USB interface and closed session for ${device.deviceName}"
        )
    }

    private data class ClaimedBotSetup(
        val connection: UsbDeviceConnection,
        val match: BotInterfaceMatch,
        val io: AndroidUsbBulkIo,
        val transport: UsbBotTransport,
        val openLogLines: List<String> = emptyList()
    )

    companion object {
        private const val TAG = "UsbSession"
        // Process-wide session lock and active set prevent concurrent access (e.g. background probe
        // or health check colliding with an active flash write) on the same USB device.
        private val sessionOpenLock = Any()
        private val activeDeviceIds = mutableSetOf<Int>()
        private val badBotDeviceIds = mutableSetOf<Int>()
        const val SUBCLASS_SFF_8070I = 0x05
        const val SUBCLASS_SCSI_TRANSPARENT = 0x06
        const val PROTOCOL_BOT = 0x50
        const val PROTOCOL_UASP = 0x62

        fun classifyDevice(device: UsbDevice): UsbTransportSupport {
            if (findBotInterface(device) != null) {
                return UsbTransportSupport.SUPPORTED_BOT
            }
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    intf.interfaceProtocol == PROTOCOL_UASP
                ) {
                    return UsbTransportSupport.UNSUPPORTED_UASP_ONLY
                }
            }
            return UsbTransportSupport.UNSUPPORTED_NON_STORAGE
        }

        fun findBotInterface(device: UsbDevice): BotInterfaceMatch? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE) continue
                val validSubclass = intf.interfaceSubclass == SUBCLASS_SCSI_TRANSPARENT ||
                    intf.interfaceSubclass == SUBCLASS_SFF_8070I
                if (!validSubclass || intf.interfaceProtocol != PROTOCOL_BOT) continue

                var bulkIn: UsbEndpoint? = null
                var bulkOut: UsbEndpoint? = null
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN && bulkIn == null) {
                            bulkIn = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT && bulkOut == null) {
                            bulkOut = ep
                        }
                    }
                }
                if (bulkIn != null && bulkOut != null) {
                    return BotInterfaceMatch(intf, bulkIn, bulkOut)
                }
            }
            return null
        }

        @Throws(UsbStorageException::class)
        fun open(
            usbManager: UsbManager,
            device: UsbDevice,
            isDisconnected: () -> Boolean,
            isCancelled: () -> Boolean = { false },
            requestedBufferBytes: Int = 0,
            logger: OperationLogger? = null
        ): UsbDeviceSession = synchronized(sessionOpenLock) {
            val setup = claimBotTransportLocked(
                usbManager = usbManager,
                device = device,
                isDisconnected = isDisconnected,
                isCancelled = isCancelled,
                logger = logger
            )

            try {
                val autoBufferBytes = when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> 64 * 1024
                    setup.io.isSuperSpeed -> 256 * 1024
                    else -> 128 * 1024
                }
                val effectiveBufferBytes = if (requestedBufferBytes > 0) {
                    requestedBufferBytes.coerceIn(64 * 1024, 512 * 1024)
                } else {
                    autoBufferBytes
                }
                // Cap per-SCSI WRITE(10)/READ(10) command transfer at 64 KiB (128 sectors of 512B,
                // matching Linux kernel usb-storage max_sectors=128) so USB flash drive controller
                // FIFOs never overflow or STALL during large continuous ISO writes, while keeping
                // the double-buffered storage pipeline at effectiveBufferBytes (128-512 KiB).
                val scsiMaxTransfer = minOf(effectiveBufferBytes, ScsiBlockDevice.DEFAULT_MAX_TRANSFER_BYTES)

                logger?.info(
                    TAG,
                    "USB I/O tuning: superSpeed=${setup.io.isSuperSpeed}, maxBulkChunk=${setup.io.maxBulkChunkBytes}B, " +
                        "scsiTransfer=${scsiMaxTransfer}B, pipelineBuf=${effectiveBufferBytes}B"
                )

                val maxLun = setup.transport.getMaxLun()

                // Probe LUN 0 (or first responsive LUN if multi-LUN card reader)
                var chosenBlockDevice: ScsiBlockDevice? = null
                var lastErr: Exception? = null
                for (lun in 0..maxLun) {
                    if (isCancelled()) throw CancellationException("USB initialization cancelled.")
                    val candidate = ScsiBlockDevice(
                        transport = setup.transport,
                        lun = lun,
                        logger = logger,
                        maxTransferBytes = scsiMaxTransfer,
                        isCancelled = isCancelled
                    )
                    try {
                        candidate.initialize()
                        chosenBlockDevice = candidate
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UsbDisconnectedException) {
                        throw e
                    } catch (e: Exception) {
                        lastErr = e
                        logger?.warn(TAG, "LUN $lun probe skipped: ${e.message}")
                    }
                }

                val readyDevice = chosenBlockDevice
                    ?: throw (lastErr as? UsbStorageException
                        ?: UsbStorageException("Failed to initialize SCSI block device: ${lastErr?.message}", lastErr))

                return UsbDeviceSession(
                    device = device,
                    connection = setup.connection,
                    usbInterface = setup.match.usbInterface,
                    blockDevice = readyDevice,
                    recommendedBufferBytes = effectiveBufferBytes,
                    logger = logger
                )
            } catch (e: Throwable) {
                if (e is UsbBotCommandException || setup.transport.isBotStateBad) {
                    badBotDeviceIds.add(device.deviceId)
                }
                releaseConnectionSafely(
                    deviceId = device.deviceId,
                    connection = setup.connection,
                    usbInterface = setup.match.usbInterface,
                    claimed = true,
                    logger = null
                )
                throw e
            }
        }

        fun markBadBotState(deviceId: Int) {
            synchronized(sessionOpenLock) {
                badBotDeviceIds.add(deviceId)
            }
        }

        fun clearBadBotState(deviceId: Int) {
            synchronized(sessionOpenLock) {
                badBotDeviceIds.remove(deviceId)
            }
        }

        fun hasBadBotState(deviceId: Int): Boolean = synchronized(sessionOpenLock) {
            deviceId in badBotDeviceIds
        }

        /**
         * Opens the USB device, claims the BOT Mass Storage interface with force=true
         * (ignoring claim/command errors), runs the best-effort eject sequence
         * (SYNCHRONIZE CACHE -> TEST UNIT READY -> START STOP UNIT), releases the interface,
         * and closes the [UsbDeviceConnection]. Fails only if the device cannot be opened.
         */
        @Throws(UsbStorageException::class, CancellationException::class)
        fun eject(
            usbManager: UsbManager,
            device: UsbDevice,
            lun: Int = 0,
            isDisconnected: () -> Boolean,
            isCancelled: () -> Boolean = { false },
            logger: OperationLogger? = null
        ): UsbEjectResult = synchronized(sessionOpenLock) {
            val setup = openBotTransportForEjectLocked(
                usbManager = usbManager,
                device = device,
                isDisconnected = isDisconnected,
                isCancelled = isCancelled,
                logger = logger
            )
            val blockDevice = ScsiBlockDevice(
                transport = setup.transport,
                lun = lun.coerceAtLeast(0),
                logger = logger,
                isCancelled = isCancelled
            )
            return try {
                val result = blockDevice.ejectUnit(
                    forceInitialBotReset = device.deviceId in badBotDeviceIds,
                    preLogLines = setup.openLogLines
                )
                if (blockDevice.hasBadBotState()) {
                    badBotDeviceIds.add(device.deviceId)
                } else if (result.anyCommandSucceeded) {
                    badBotDeviceIds.remove(device.deviceId)
                }
                result
            } finally {
                try {
                    blockDevice.close()
                } catch (_: Exception) {
                }
                releaseConnectionSafely(
                    deviceId = device.deviceId,
                    connection = setup.connection,
                    usbInterface = setup.match.usbInterface,
                    claimed = true,
                    logger = logger,
                    logMessage = "Released USB interface and closed connection after eject for ${device.deviceName}"
                )
            }
        }

        private fun openBotTransportForEjectLocked(
            usbManager: UsbManager,
            device: UsbDevice,
            isDisconnected: () -> Boolean,
            isCancelled: () -> Boolean,
            logger: OperationLogger?
        ): ClaimedBotSetup {
            if (!usbManager.hasPermission(device)) {
                throw UsbStorageException("USB permission has not been granted for this drive.")
            }
            if (device.deviceId in activeDeviceIds) {
                throw UsbStorageException("USB drive is currently busy with another operation.")
            }
            val match = findBotInterface(device)
                ?: throw UsbStorageException(
                    "USB device does not expose a compatible Bulk-Only Transport (BOT) SCSI interface."
                )

            val connection = try {
                usbManager.openDevice(device)
            } catch (e: SecurityException) {
                throw UsbStorageException(
                    "Unable to open USB device connection. Grant permission and retry.",
                    e
                )
            } ?: throw UsbStorageException(
                "Unable to open USB device connection. Reconnect the OTG cable and grant permission again."
            )

            activeDeviceIds.add(device.deviceId)
            val logLines = mutableListOf<String>()
            val claimed = try {
                // force = true invokes USBDEVFS_DISCONNECT to detach the kernel usb-storage driver
                connection.claimInterface(match.usbInterface, true)
            } catch (e: Exception) {
                val msg = "claimInterface(force=true) threw during eject (ignored): ${e.message}"
                logLines += msg
                logger?.warn(TAG, msg)
                false
            }
            if (claimed) {
                val msg = "Claimed USB BOT interface #${match.usbInterface.id} with force=true " +
                    "(IN=0x%02X, OUT=0x%02X)".format(match.bulkIn.address, match.bulkOut.address)
                logLines += msg
                logger?.info(TAG, msg)
            } else if (logLines.isEmpty()) {
                val msg = "claimInterface(force=true) returned false on interface #${match.usbInterface.id}; continuing best-effort"
                logLines += msg
                logger?.warn(TAG, msg)
            }

            if (match.usbInterface.alternateSetting > 0) {
                try {
                    if (!connection.setInterface(match.usbInterface)) {
                        val msg = "setInterface(${match.usbInterface.alternateSetting}) returned false during eject (ignored)"
                        logLines += msg
                        logger?.warn(TAG, msg)
                    }
                } catch (e: Exception) {
                    val msg = "setInterface(${match.usbInterface.alternateSetting}) threw during eject (ignored): ${e.message}"
                    logLines += msg
                    logger?.warn(TAG, msg)
                }
            }

            val io = AndroidUsbBulkIo(
                connection = connection,
                interfaceId = match.usbInterface.id,
                bulkInEndpoint = match.bulkIn,
                bulkOutEndpoint = match.bulkOut,
                disconnectCheck = isDisconnected
            )
            val transport = UsbBotTransport(
                io = io,
                logger = logger,
                isCancelled = isCancelled
            )
            return ClaimedBotSetup(connection, match, io, transport, logLines)
        }

        private fun claimBotTransportLocked(
            usbManager: UsbManager,
            device: UsbDevice,
            isDisconnected: () -> Boolean,
            isCancelled: () -> Boolean,
            logger: OperationLogger?
        ): ClaimedBotSetup {
            if (!usbManager.hasPermission(device)) {
                throw UsbStorageException("USB permission has not been granted for this drive.")
            }
            if (device.deviceId in activeDeviceIds) {
                throw UsbStorageException("USB drive is currently busy with another operation.")
            }
            val match = findBotInterface(device)
                ?: throw UsbStorageException(
                    "USB device does not expose a compatible Bulk-Only Transport (BOT) SCSI interface."
                )

            val connection = usbManager.openDevice(device)
                ?: throw UsbStorageException(
                    "Unable to open USB device connection. Reconnect the OTG cable and grant permission again."
                )

            var claimed = false
            activeDeviceIds.add(device.deviceId)
            try {
                // force = true invokes USBDEVFS_DISCONNECT to detach the kernel usb-storage driver
                claimed = connection.claimInterface(match.usbInterface, true)
                if (!claimed) {
                    throw UsbClaimInterfaceException(
                        "Failed to claim exclusive USB Mass Storage interface. Android's system storage " +
                            "service may still have the drive mounted. Go to Android Settings -> Storage, " +
                            "eject/unmount the USB drive (keep it plugged in), and retry."
                    )
                }
                if (match.usbInterface.alternateSetting > 0 && !connection.setInterface(match.usbInterface)) {
                    throw UsbStorageException(
                        "Failed to activate USB BOT alternate interface setting ${match.usbInterface.alternateSetting}."
                    )
                }

                logger?.info(
                    TAG,
                    "Claimed USB BOT interface #${match.usbInterface.id} " +
                        "(alt=${match.usbInterface.alternateSetting}, " +
                        "IN=0x%02X, OUT=0x%02X)".format(match.bulkIn.address, match.bulkOut.address)
                )

                val io = AndroidUsbBulkIo(
                    connection = connection,
                    interfaceId = match.usbInterface.id,
                    bulkInEndpoint = match.bulkIn,
                    bulkOutEndpoint = match.bulkOut,
                    disconnectCheck = isDisconnected
                )
                val transport = UsbBotTransport(
                    io = io,
                    logger = logger,
                    isCancelled = isCancelled
                )
                return ClaimedBotSetup(connection, match, io, transport)
            } catch (e: Throwable) {
                releaseConnectionSafely(
                    deviceId = device.deviceId,
                    connection = connection,
                    usbInterface = match.usbInterface,
                    claimed = claimed,
                    logger = null
                )
                throw e
            }
        }

        private fun releaseConnectionSafely(
            deviceId: Int,
            connection: UsbDeviceConnection,
            usbInterface: UsbInterface,
            claimed: Boolean,
            logger: OperationLogger?,
            logMessage: String? = null
        ) {
            if (claimed) {
                try {
                    connection.releaseInterface(usbInterface)
                } catch (_: Exception) {
                }
            }
            try {
                connection.close()
            } catch (_: Exception) {
            }
            synchronized(sessionOpenLock) {
                activeDeviceIds.remove(deviceId)
            }
            if (logMessage != null) {
                logger?.info(TAG, logMessage)
            }
        }
    }
}
