package com.usbbootable.creator.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.usbbootable.creator.core.util.OperationLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

sealed class UsbEvent {
    data class DeviceAttached(val deviceId: Int, val name: String) : UsbEvent()
    data class DeviceDetached(val deviceId: Int, val name: String) : UsbEvent()
    data class PermissionResult(val deviceId: Int, val granted: Boolean) : UsbEvent()
}

/**
 * Monitors connected USB OTG mass storage drives via Android's [UsbManager],
 * handles runtime USB permission dialogs, detects sudden disconnections, and probes
 * real SCSI capacity & write-protection status once permission is granted.
 */
class UsbDeviceMonitor(
    private val context: Context,
    private val logger: OperationLogger
) {
    private val appContext = context.applicationContext
    val usbManager: UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    val isUsbHostSupported: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val monitorLock = Any()
    private val probedCache = ConcurrentHashMap<Int, UsbDriveInfo>()
    private val rawDevices = ConcurrentHashMap<Int, UsbDevice>()
    private val probesInFlight = ConcurrentHashMap.newKeySet<Int>()
    private val ejectedDeviceIds = ConcurrentHashMap.newKeySet<Int>()

    private val _drives = MutableStateFlow<List<UsbDriveInfo>>(emptyList())
    val drives: StateFlow<List<UsbDriveInfo>> = _drives.asStateFlow()

    private val _events = MutableSharedFlow<UsbEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UsbEvent> = _events.asSharedFlow()

    @Volatile
    private var isWriteActive: Boolean = false

    @Volatile
    private var isEjectActive: Boolean = false

    private var registered = false

    private val usbHardwareReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDeviceExtra()
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val name = safeDeviceDisplayName(device)
                    logger.info(TAG, "USB device attached: $name")
                    if (device != null) {
                        ejectedDeviceIds.remove(device.deviceId)
                        UsbDeviceSession.clearBadBotState(device.deviceId)
                    }
                    refreshDevices()
                    if (device != null) {
                        _events.tryEmit(UsbEvent.DeviceAttached(device.deviceId, name))
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val name = safeDeviceDisplayName(device)
                    logger.warn(TAG, "USB device disconnected: $name")
                    if (device != null) {
                        synchronized(monitorLock) {
                            probedCache.remove(device.deviceId)
                            rawDevices.remove(device.deviceId)
                            _drives.value = _drives.value.filterNot { it.deviceId == device.deviceId }
                        }
                        UsbDeviceSession.clearBadBotState(device.deviceId)
                        _events.tryEmit(UsbEvent.DeviceDetached(device.deviceId, name))
                        refreshDevices(excludedDeviceId = device.deviceId)
                    } else {
                        // Fallback if EXTRA_DEVICE is missing on a vendor broadcast: diff active list safely.
                        val currentIds = safeDeviceList().values.map { it.deviceId }.toSet()
                        val missing = synchronized(monitorLock) {
                            val removed = rawDevices.entries
                                .filter { it.key !in currentIds }
                                .map { it.key to safeDeviceDisplayName(it.value) }
                            removed.forEach { (id, _) ->
                                probedCache.remove(id)
                                rawDevices.remove(id)
                            }
                            _drives.value = _drives.value.filter { it.deviceId in currentIds }
                            removed
                        }
                        missing.forEach { (id, removedName) ->
                            _events.tryEmit(UsbEvent.DeviceDetached(id, removedName))
                        }
                        refreshDevices()
                    }
                }
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDeviceExtra() ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            logger.info(
                TAG,
                "USB permission ${if (granted) "GRANTED" else "DENIED"} for ${device.deviceName}"
            )
            _events.tryEmit(UsbEvent.PermissionResult(device.deviceId, granted))
            refreshDevices()
        }
    }

    fun startMonitoring(initialRefresh: Boolean = true) {
        if (registered) return
        val hwFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            usbHardwareReceiver,
            hwFilter,
            ContextCompat.RECEIVER_EXPORTED
        )

        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            appContext,
            usbPermissionReceiver,
            permFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
        if (initialRefresh) {
            refreshDevices()
        }
    }

    fun setWriteActive(active: Boolean) {
        isWriteActive = active
    }

    fun setEjectActive(active: Boolean) {
        isEjectActive = active
    }

    fun isProbeInFlight(deviceId: Int): Boolean = deviceId in probesInFlight

    fun markDeviceEjected(deviceId: Int) {
        ejectedDeviceIds.add(deviceId)
    }

    fun clearEjectedMarker(deviceId: Int) {
        ejectedDeviceIds.remove(deviceId)
    }

    fun isDeviceEjected(deviceId: Int): Boolean = deviceId in ejectedDeviceIds

    fun consumeEjectedMarker(deviceId: Int): Boolean = ejectedDeviceIds.remove(deviceId)

    fun getUsbDevice(deviceId: Int): UsbDevice? {
        return rawDevices[deviceId] ?: safeDeviceList().values.firstOrNull { it.deviceId == deviceId }
    }

    fun isDeviceConnected(deviceId: Int): Boolean {
        return safeDeviceList().values.any { it.deviceId == deviceId }
    }

    fun refreshDevices(excludedDeviceId: Int? = null) {
        val currentMap = safeDeviceList()
        val toProbe = mutableListOf<Int>()
        synchronized(monitorLock) {
            val activeIds = currentMap.values
                .map { it.deviceId }
                .filter { it != excludedDeviceId }
                .toSet()

            // Remove stale entries for disconnected devices
            probedCache.keys.retainAll(activeIds)
            ejectedDeviceIds.retainAll(activeIds)
            rawDevices.clear()

            val result = mutableListOf<UsbDriveInfo>()
            for (device in currentMap.values) {
                if (device.deviceId == excludedDeviceId) continue
                val support = UsbDeviceSession.classifyDevice(device)
                if (support == UsbTransportSupport.UNSUPPORTED_NON_STORAGE) continue

                rawDevices[device.deviceId] = device
                val hasPerm = try {
                    usbManager.hasPermission(device)
                } catch (_: Exception) {
                    false
                }
                val cached = probedCache[device.deviceId]

                val serial = if (hasPerm) {
                    try {
                        device.serialNumber
                    } catch (_: SecurityException) {
                        null
                    }
                } else {
                    null
                }

                val info = if (cached != null && hasPerm) {
                    cached.copy(hasPermission = true, serialNumber = serial ?: cached.serialNumber)
                } else {
                    UsbDriveInfo(
                        deviceId = device.deviceId,
                        deviceName = device.deviceName,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        manufacturerName = device.manufacturerName,
                        productName = device.productName,
                        serialNumber = serial,
                        transportSupport = support,
                        hasPermission = hasPerm
                    )
                }
                result.add(info)

                if (hasPerm &&
                    support == UsbTransportSupport.SUPPORTED_BOT &&
                    !info.isProbed &&
                    !isWriteActive &&
                    !isEjectActive &&
                    device.deviceId !in ejectedDeviceIds
                ) {
                    toProbe.add(device.deviceId)
                }
            }

            _drives.value = result.sortedBy { it.deviceId }
        }
        toProbe.forEach { probeDriveAsync(it) }
    }

    fun requestPermission(deviceId: Int) {
        val device = getUsbDevice(deviceId) ?: run {
            logger.warn(TAG, "Cannot request permission: USB device #$deviceId is no longer connected.")
            refreshDevices()
            return
        }
        if (usbManager.hasPermission(device)) {
            probeDriveAsync(deviceId)
            return
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            // Explicit package is required for FLAG_MUTABLE PendingIntent on Android 14+ (API 34+)
            setPackage(appContext.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(appContext, deviceId, intent, flags)
        logger.info(TAG, "Requesting Android USB Host permission for ${device.deviceName}")
        usbManager.requestPermission(device, pendingIntent)
    }

    fun probeDriveAsync(deviceId: Int) {
        if (isWriteActive || isEjectActive || deviceId in ejectedDeviceIds) return
        if (!probesInFlight.add(deviceId)) return
        scope.launch {
            try {
                probeDriveBlocking(deviceId)
            } finally {
                probesInFlight.remove(deviceId)
            }
        }
    }

    private fun probeDriveBlocking(deviceId: Int) {
        if (isWriteActive || isEjectActive || deviceId in ejectedDeviceIds) return
        val device = getUsbDevice(deviceId) ?: return
        if (!usbManager.hasPermission(device)) return
        if (UsbDeviceSession.classifyDevice(device) != UsbTransportSupport.SUPPORTED_BOT) return

        val serial = try {
            device.serialNumber
        } catch (_: SecurityException) {
            null
        }

        try {
            logger.info(TAG, "Probing SCSI geometry for ${device.deviceName}...")
            UsbDeviceSession.open(
                usbManager = usbManager,
                device = device,
                isDisconnected = { !isDeviceConnected(deviceId) },
                logger = logger
            ).use { session ->
                val blk = session.blockDevice
                val probed = UsbDriveInfo(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    manufacturerName = device.manufacturerName,
                    productName = device.productName,
                    serialNumber = serial,
                    transportSupport = UsbTransportSupport.SUPPORTED_BOT,
                    hasPermission = true,
                    isProbed = true,
                    probeError = null,
                    scsiVendor = blk.inquiry.vendor,
                    scsiProduct = blk.inquiry.product,
                    logicalBlockSize = blk.blockSize,
                    totalBlocks = blk.totalBlocks,
                    capacityBytes = blk.capacityBytes,
                    isWriteProtected = blk.isReadOnly,
                    lun = blk.lun
                )
                synchronized(monitorLock) {
                    if (!rawDevices.containsKey(deviceId) || !isDeviceConnected(deviceId)) return
                    probedCache[deviceId] = probed
                    updateDriveEntryLocked(probed)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, "SCSI probe failed for ${device.deviceName}: ${e.message}")
            val failed = UsbDriveInfo(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturerName = device.manufacturerName,
                productName = device.productName,
                serialNumber = serial,
                transportSupport = UsbTransportSupport.SUPPORTED_BOT,
                hasPermission = true,
                isProbed = false,
                probeError = e.message ?: "Failed to probe USB mass storage drive."
            )
            synchronized(monitorLock) {
                if (!rawDevices.containsKey(deviceId) || !isDeviceConnected(deviceId)) return
                probedCache[deviceId] = failed
                updateDriveEntryLocked(failed)
            }
        }
    }

    private fun updateDriveEntryLocked(updated: UsbDriveInfo) {
        val current = _drives.value.toMutableList()
        val idx = current.indexOfFirst { it.deviceId == updated.deviceId }
        if (idx >= 0) {
            current[idx] = updated
        } else {
            current.add(updated)
        }
        _drives.value = current.sortedBy { it.deviceId }
    }

    private fun safeDeviceList(): Map<String, UsbDevice> = try {
        usbManager.deviceList ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun safeDeviceDisplayName(device: UsbDevice?): String {
        if (device == null) return "USB Device"
        return try {
            device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName
        } catch (_: Exception) {
            "USB Device"
        }
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? = try {
        IntentCompat.getParcelableExtra(this, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "UsbMonitor"
        const val ACTION_USB_PERMISSION = "com.usbbootable.creator.action.USB_PERMISSION"
    }
}
