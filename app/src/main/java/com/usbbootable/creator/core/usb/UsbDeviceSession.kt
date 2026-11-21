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
    val maxLun: Int,
    val recommendedBufferBytes: Int,
    private val logger: OperationLogger?
) : Closeable {

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            blockDevice.close()
        } catch (_: Exception) {
        }
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
        logger?.info(TAG, "Released USB interface and closed session for ${device.deviceName}")
    }

    companion object {
        private const val TAG = "UsbSession"
        // Process-wide session lock prevents background capacity probes from overlapping
        // with an active write session on the same USB Host controller.
        private val sessionOpenLock = Any()
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
            if (!usbManager.hasPermission(device)) {
                throw UsbStorageException("USB permission has not been granted for this drive.")
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
                if (match.usbInterface.alternateSetting > 0) {
                    connection.setInterface(match.usbInterface)
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
                val autoBufferBytes = when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> 64 * 1024
                    io.isSuperSpeed -> 256 * 1024
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
                    "USB I/O tuning: superSpeed=${io.isSuperSpeed}, maxBulkChunk=${io.maxBulkChunkBytes}B, " +
                        "scsiTransfer=${scsiMaxTransfer}B, pipelineBuf=${effectiveBufferBytes}B"
                )

                val transport = UsbBotTransport(
                    io = io,
                    logger = logger,
                    isCancelled = isCancelled
                )
                val maxLun = transport.getMaxLun()

                // Probe LUN 0 (or first responsive LUN if multi-LUN card reader)
                var chosenBlockDevice: ScsiBlockDevice? = null
                var lastErr: Exception? = null
                for (lun in 0..maxLun) {
                    val candidate = ScsiBlockDevice(
                        transport = transport,
                        lun = lun,
                        logger = logger,
                        maxTransferBytes = scsiMaxTransfer,
                        isCancelled = isCancelled
                    )
                    try {
                        candidate.initialize()
                        chosenBlockDevice = candidate
                        break
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
                    connection = connection,
                    usbInterface = match.usbInterface,
                    blockDevice = readyDevice,
                    maxLun = maxLun,
                    recommendedBufferBytes = effectiveBufferBytes,
                    logger = logger
                )
            } catch (e: Exception) {
                if (claimed) {
                    try {
                        connection.releaseInterface(match.usbInterface)
                    } catch (_: Exception) {
                    }
                }
                try {
                    connection.close()
                } catch (_: Exception) {
                }
                throw e
            }
        }
    }
}
