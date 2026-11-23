package com.usbbootable.creator

import com.usbbootable.creator.ui.StartupCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashStartupLifecycleTest {

    @Test
    fun startupTransitionsReadyOnceAndDismissesOnce() = runTest {
        val coordinator = StartupCoordinator()
        assertFalse(coordinator.isStartupReady.value)
        assertFalse(coordinator.splashDismissed.value)

        var customizationCalls = 0
        var usbRefreshCalls = 0

        coordinator.runStartup(
            loadCustomization = { customizationCalls++ },
            refreshUsb = { usbRefreshCalls++ }
        )

        assertTrue(coordinator.isStartupReady.value)
        assertEquals(1, customizationCalls)
        assertEquals(1, usbRefreshCalls)

        // Calling runStartup a second time (e.g. defensive re-entry) must remain a no-op
        coordinator.runStartup(
            loadCustomization = { customizationCalls++ },
            refreshUsb = { usbRefreshCalls++ }
        )
        assertEquals(1, customizationCalls)
        assertEquals(1, usbRefreshCalls)

        coordinator.markSplashDismissed()
        assertTrue(coordinator.splashDismissed.value)
    }

    @Test
    fun customizationFailureDoesNotBlockUsbRefreshOrSplashReadiness() = runTest {
        val coordinator = StartupCoordinator()
        val errors = mutableListOf<String>()
        var usbRefreshed = false

        coordinator.runStartup(
            loadCustomization = { error("DataStore read error") },
            refreshUsb = { usbRefreshed = true },
            onError = { message, _ -> errors += message }
        )

        assertTrue(usbRefreshed)
        assertTrue(coordinator.isStartupReady.value)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("customization", ignoreCase = true))
    }

    @Test
    fun usbRefreshFailureDoesNotDeadlockSplashScreen() = runTest {
        val coordinator = StartupCoordinator()
        val errors = mutableListOf<String>()
        var customizationLoaded = false

        coordinator.runStartup(
            loadCustomization = { customizationLoaded = true },
            refreshUsb = { throw IllegalStateException("USB service unavailable") },
            onError = { message, _ -> errors += message }
        )

        assertTrue(customizationLoaded)
        assertTrue(coordinator.isStartupReady.value)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("USB", ignoreCase = true))
    }

    @Test
    fun stepTimeoutDoesNotDeadlockSplashReadiness() = runTest {
        val coordinator = StartupCoordinator(stepTimeoutMs = 50L)
        val errors = mutableListOf<String>()
        var usbRefreshed = false

        coordinator.runStartup(
            loadCustomization = { delay(5_000L) },
            refreshUsb = { usbRefreshed = true },
            onError = { message, _ -> errors += message }
        )

        assertTrue(usbRefreshed)
        assertTrue(coordinator.isStartupReady.value)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Timed out", ignoreCase = true))
    }

    @Test
    fun externalCancellationBeforeReadyAllowsRetryToComplete() = runTest {
        val coordinator = StartupCoordinator()
        var cancelledOnce = false
        try {
            coordinator.runStartup(
                loadCustomization = { throw CancellationException("Scope cancelled") },
                refreshUsb = {}
            )
        } catch (_: CancellationException) {
            cancelledOnce = true
        }

        assertTrue(cancelledOnce)
        assertFalse(coordinator.isStartupReady.value)

        var usbRefreshed = false
        coordinator.runStartup(
            loadCustomization = {},
            refreshUsb = { usbRefreshed = true }
        )
        assertTrue(usbRefreshed)
        assertTrue(coordinator.isStartupReady.value)
    }
}
