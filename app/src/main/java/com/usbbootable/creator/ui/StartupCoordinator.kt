package com.usbbootable.creator.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates one-shot background startup initialization and SplashScreen dismissal state.
 * Stored inside [MainViewModel] so splash state survives configuration changes and never
 * replays during tab or screen navigation.
 */
class StartupCoordinator(
    private val stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS
) {
    private val _isStartupReady = MutableStateFlow(false)
    val isStartupReady: StateFlow<Boolean> = _isStartupReady.asStateFlow()

    private val _splashDismissed = MutableStateFlow(false)
    val splashDismissed: StateFlow<Boolean> = _splashDismissed.asStateFlow()

    private val started = AtomicBoolean(false)

    suspend fun runStartup(
        loadCustomization: suspend () -> Unit,
        refreshUsb: suspend () -> Unit,
        onError: (String, Throwable) -> Unit = { _, _ -> }
    ) {
        if (!started.compareAndSet(false, true)) {
            return
        }

        try {
            try {
                withTimeout(stepTimeoutMs) { loadCustomization() }
            } catch (te: TimeoutCancellationException) {
                onError("Timed out preloading theme customization", te)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                onError("Failed to preload theme customization", t)
            }

            try {
                withTimeout(stepTimeoutMs) { refreshUsb() }
            } catch (te: TimeoutCancellationException) {
                onError("Timed out refreshing USB devices during startup", te)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                onError("Failed to refresh USB devices during startup", t)
            }

            _isStartupReady.value = true
        } catch (ce: CancellationException) {
            if (!_isStartupReady.value) {
                started.set(false)
            }
            throw ce
        }
    }

    fun markSplashDismissed() {
        _splashDismissed.value = true
    }

    companion object {
        private const val DEFAULT_STEP_TIMEOUT_MS = 3_000L
    }
}
