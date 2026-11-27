package com.usbbootable.creator

import android.app.Application
import com.usbbootable.creator.core.storage.AppPreferencesStore
import com.usbbootable.creator.core.storage.CustomizationDataStore
import com.usbbootable.creator.core.usb.UsbDeviceMonitor
import com.usbbootable.creator.core.util.OperationLogger
import com.usbbootable.creator.service.UsbSafeEjectStore
import com.usbbootable.creator.service.UsbWriteStateStore

class UsbBootableApp : Application() {

    lateinit var logger: OperationLogger
        private set

    lateinit var preferencesStore: AppPreferencesStore
        private set

    lateinit var customizationStore: CustomizationDataStore
        private set

    lateinit var usbMonitor: UsbDeviceMonitor
        private set

    lateinit var writeStateStore: UsbWriteStateStore
        private set

    lateinit var safeEjectStore: UsbSafeEjectStore
        private set

    override fun onCreate() {
        super.onCreate()
        logger = OperationLogger()
        preferencesStore = AppPreferencesStore(this)
        customizationStore = CustomizationDataStore(this)
        writeStateStore = UsbWriteStateStore()
        safeEjectStore = UsbSafeEjectStore()
        usbMonitor = UsbDeviceMonitor(this, logger)
        usbMonitor.startMonitoring(initialRefresh = false)
        logger.info("App", "Write-Image initialized (USB OTG Host supported=${usbMonitor.isUsbHostSupported})")
    }
}
