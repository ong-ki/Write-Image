package com.usbbootable.creator

import com.usbbootable.creator.core.usb.UsbBotCommandException
import com.usbbootable.creator.service.UsbEjectPhase
import com.usbbootable.creator.service.UsbSafeEjectStore
import com.usbbootable.creator.service.buildEjectExceptionDetailLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeEjectStateTest {

    @Test
    fun ejectStoreTransitionsCleanlyAndPreservesDisconnectWarning() {
        val store = UsbSafeEjectStore()
        assertEquals(UsbEjectPhase.IDLE, store.state.value.phase)
        assertFalse(store.state.value.isEjecting)

        store.beginEject(deviceId = 7, deviceName = "SanDisk USB")
        assertEquals(UsbEjectPhase.EJECTING, store.state.value.phase)
        assertTrue(store.state.value.isEjecting)
        assertEquals(7, store.state.value.targetDeviceId)

        // Simulate user unplugging before eject completes
        store.markDisconnectedWarning(
            deviceId = 7,
            deviceName = "SanDisk USB",
            warningMessage = "Unplugged before eject completed"
        )
        assertEquals(UsbEjectPhase.DISCONNECTED_WARNING, store.state.value.phase)
        assertFalse(store.state.value.isEjecting)

        // Trailing completion or failure for the same device must not overwrite the warning
        store.markSucceeded(deviceId = 7, deviceName = "SanDisk USB")
        assertEquals(UsbEjectPhase.DISCONNECTED_WARNING, store.state.value.phase)

        store.markFailed(deviceId = 7, deviceName = "SanDisk USB", errorMessage = "Some error")
        assertEquals(UsbEjectPhase.DISCONNECTED_WARNING, store.state.value.phase)

        store.reset()
        assertEquals(UsbEjectPhase.IDLE, store.state.value.phase)
        assertNull(store.state.value.targetDeviceId)
    }

    @Test
    fun ejectStoreHandlesSuccessAndFailureRetryCycle() {
        val store = UsbSafeEjectStore()

        store.beginEject(deviceId = 3, deviceName = "Kingston USB")
        store.markFailed(deviceId = 3, deviceName = "Kingston USB", errorMessage = "Cache flush failed")
        assertEquals(UsbEjectPhase.FAILED, store.state.value.phase)
        assertEquals("Cache flush failed", store.state.value.errorMessage)

        store.beginEject(deviceId = 3, deviceName = "Kingston USB")
        assertEquals(UsbEjectPhase.EJECTING, store.state.value.phase)
        assertNull(store.state.value.errorMessage)

        store.markSucceeded(deviceId = 3, deviceName = "Kingston USB")
        assertEquals(UsbEjectPhase.SUCCEEDED, store.state.value.phase)
        assertNull(store.state.value.errorMessage)
        assertTrue(store.state.value.isEjectedOrWarning)
    }

    @Test
    fun ejectStoreSupportsCompletedWarningAndPreservesDetailLog() {
        val store = UsbSafeEjectStore()
        store.beginEject(deviceId = 9, deviceName = "SanDisk USB")
        store.markCompletedWithWarning(
            deviceId = 9,
            deviceName = "SanDisk USB",
            warningMessage = "Ejected. If your USB was just written, it's safe to remove.",
            detailLog = "opcode=0x35 (SYNCHRONIZE_CACHE_10) | usbCode=-1"
        )
        assertEquals(UsbEjectPhase.COMPLETED_WARNING, store.state.value.phase)
        assertTrue(store.state.value.isEjectedOrWarning)
        assertEquals(
            "Ejected. If your USB was just written, it's safe to remove.",
            store.state.value.errorMessage
        )
        assertEquals(
            "opcode=0x35 (SYNCHRONIZE_CACHE_10) | usbCode=-1",
            store.state.value.detailLog
        )

        val err = UsbBotCommandException(
            commandName = "SYNCHRONIZE_CACHE_10(0x35)",
            endpointInfo = "IF#0 IN=0x81(512B) OUT=0x02(512B)",
            transferredBytes = 0,
            expectedBytes = 0,
            usbErrorCode = -1,
            recoveryStatus = "CSW retry returned -1" ,
            resetPerformed = true
        )
        val detail = buildEjectExceptionDetailLog(err)
        assertTrue(detail.contains("SYNCHRONIZE_CACHE_10"))
        assertTrue(detail.contains("CSW retry returned -1"))
    }
}
