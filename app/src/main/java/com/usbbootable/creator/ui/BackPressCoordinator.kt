package com.usbbootable.creator.ui

enum class BackAction {
    NAVIGATE_HOME,
    SHOW_EXIT_PROMPT,
    EXIT_APP
}

/**
 * Coordinates Android Back button / gesture navigation:
 * - Any non-Home tab returns directly to Home without arming exit confirmation.
 * - On Home, requires two Back presses within [exitWindowMs] (default 2s) to exit.
 */
class BackPressCoordinator(
    private val exitWindowMs: Long = 2_000L,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private var lastHomeBackPressMs: Long = Long.MIN_VALUE

    fun onBackPressed(currentTab: AppTab): BackAction {
        if (currentTab != AppTab.HOME) {
            lastHomeBackPressMs = Long.MIN_VALUE
            return BackAction.NAVIGATE_HOME
        }
        val now = clockMs()
        val elapsed = now - lastHomeBackPressMs
        return if (lastHomeBackPressMs != Long.MIN_VALUE && elapsed in 0L..exitWindowMs) {
            lastHomeBackPressMs = Long.MIN_VALUE
            BackAction.EXIT_APP
        } else {
            lastHomeBackPressMs = now
            BackAction.SHOW_EXIT_PROMPT
        }
    }

    fun reset() {
        lastHomeBackPressMs = Long.MIN_VALUE
    }
}
