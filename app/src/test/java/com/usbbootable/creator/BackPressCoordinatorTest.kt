package com.usbbootable.creator

import com.usbbootable.creator.ui.AppTab
import com.usbbootable.creator.ui.BackAction
import com.usbbootable.creator.ui.BackPressCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

class BackPressCoordinatorTest {

    @Test
    fun nonHomeScreensReturnToHomeWithoutTriggeringExitConfirmation() {
        var fakeMs = 10_000L
        val coordinator = BackPressCoordinator(exitWindowMs = 2_000L, clockMs = { fakeMs })

        // Back from History -> Home
        assertEquals(BackAction.NAVIGATE_HOME, coordinator.onBackPressed(AppTab.HISTORY))

        // Immediate Back on Home must still show prompt, NOT exit
        fakeMs += 100L
        assertEquals(BackAction.SHOW_EXIT_PROMPT, coordinator.onBackPressed(AppTab.HOME))

        // Back from Settings -> Home also clears any earlier Home confirmation
        fakeMs += 100L
        assertEquals(BackAction.NAVIGATE_HOME, coordinator.onBackPressed(AppTab.SETTINGS))

        fakeMs += 100L
        assertEquals(BackAction.SHOW_EXIT_PROMPT, coordinator.onBackPressed(AppTab.HOME))
    }

    @Test
    fun secondBackWithinTwoSecondsOnHomeExitsAppIncludingBoundary() {
        var fakeMs = 5_000L
        val coordinator = BackPressCoordinator(exitWindowMs = 2_000L, clockMs = { fakeMs })

        assertEquals(BackAction.SHOW_EXIT_PROMPT, coordinator.onBackPressed(AppTab.HOME))
        fakeMs += 2_000L // Exact 2-second boundary
        assertEquals(BackAction.EXIT_APP, coordinator.onBackPressed(AppTab.HOME))
    }

    @Test
    fun backAfterMoreThanTwoSecondsResetsExitConfirmation() {
        var fakeMs = 1_000L
        val coordinator = BackPressCoordinator(exitWindowMs = 2_000L, clockMs = { fakeMs })

        assertEquals(BackAction.SHOW_EXIT_PROMPT, coordinator.onBackPressed(AppTab.HOME))

        // > 2 seconds later: confirmation resets, shows prompt again
        fakeMs += 2_001L
        assertEquals(BackAction.SHOW_EXIT_PROMPT, coordinator.onBackPressed(AppTab.HOME))

        // Subsequent press within 2 seconds exits
        fakeMs += 900L
        assertEquals(BackAction.EXIT_APP, coordinator.onBackPressed(AppTab.HOME))
    }

    @Test
    fun resetClearsPendingExitConfirmation() {
        var fakeMs = 1_000L
        val coordinator = BackPressCoordinator(exitWindowMs = 2_000L, clockMs = { fakeMs })

        assertEquals(BackAction.SHOW_EXIT_PROMPT, coordinator.onBackPressed(AppTab.HOME))
        coordinator.reset()

        fakeMs += 500L
        assertEquals(BackAction.SHOW_EXIT_PROMPT, coordinator.onBackPressed(AppTab.HOME))
    }
}
