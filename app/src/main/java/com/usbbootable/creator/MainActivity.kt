package com.usbbootable.creator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.ui.AppTab
import com.usbbootable.creator.ui.BackAction
import com.usbbootable.creator.ui.BackPressCoordinator
import com.usbbootable.creator.ui.MainViewModel
import com.usbbootable.creator.ui.screens.AppSplashScreen
import com.usbbootable.creator.ui.screens.MainTabsScaffold
import com.usbbootable.creator.ui.theme.UsbBootableCreatorTheme
import com.usbbootable.creator.ui.theme.resolveDarkTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Storage Access Framework (SAF) picker for .iso / .img / raw disk image files
    private val openIsoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onIsoUriSelected(uri)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Foreground service runs even if notifications are denied; granting shows live progress
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !viewModel.isStartupReady.value }
        splashScreen.setOnExitAnimationListener { splashViewProvider ->
            splashViewProvider.remove()
        }
        val initialSplashBars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(
            statusBarStyle = initialSplashBars,
            navigationBarStyle = initialSplashBars
        )

        if (savedInstanceState == null) {
            requestNotificationPermissionIfNeeded()
            handleUsbAttachIntent(intent)
        }
        observeKeepScreenOnState()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val isStartupReady by viewModel.isStartupReady.collectAsStateWithLifecycle()
            val splashDismissed by viewModel.splashDismissed.collectAsStateWithLifecycle()
            val useDarkTheme = resolveDarkTheme(state.themeMode, isSystemInDarkTheme())
            val effectiveDarkBars = !splashDismissed || useDarkTheme
            val backCoordinator = remember { BackPressCoordinator() }
            var exitToast by remember { mutableStateOf<Toast?>(null) }

            DisposableEffect(effectiveDarkBars) {
                val barStyle = if (effectiveDarkBars) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                }
                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle
                )
                onDispose {}
            }

            // Do not leak the Activity context or leave a queued Toast behind if the
            // composition is disposed while the exit prompt is visible.
            DisposableEffect(Unit) {
                onDispose { exitToast?.cancel() }
            }

            LaunchedEffect(state.selectedTab) {
                if (state.selectedTab != AppTab.HOME) {
                    backCoordinator.reset()
                }
            }

            BackHandler(enabled = splashDismissed) {
                when (backCoordinator.onBackPressed(state.selectedTab)) {
                    BackAction.NAVIGATE_HOME -> {
                        viewModel.selectTab(AppTab.HOME)
                    }
                    BackAction.SHOW_EXIT_PROMPT -> {
                        exitToast?.cancel()
                        exitToast = Toast.makeText(
                            applicationContext,
                            "Press back again to exit",
                            Toast.LENGTH_SHORT
                        ).apply { show() }
                    }
                    BackAction.EXIT_APP -> {
                        exitToast?.cancel()
                        finish()
                    }
                }
            }

            UsbBootableCreatorTheme(
                themeMode = state.themeMode,
                customization = state.customization
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    MainTabsScaffold(
                    state = state,
                    logsFlow = viewModel.logs,
                    onSelectTab = viewModel::selectTab,
                    onSetFlashingMode = viewModel::setFlashingMode,
                    onSetWindowsBypassTpm = viewModel::setWindowsBypassTpm,
                    onSetWindowsBypassSecureBoot = viewModel::setWindowsBypassSecureBoot,
                    onSetWindowsBypassRam = viewModel::setWindowsBypassRam,
                    onSetWindowsBypassCpu = viewModel::setWindowsBypassCpu,
                    onSetWindowsBypassAll = viewModel::setWindowsBypassAll,
                    onSelectIsoClick = {
                        // Use "*/*" because Android's MIME registry frequently classifies .iso and .img
                        // under application/octet-stream or unrecognized MIME types across OEMs.
                        openIsoLauncher.launch(arrayOf("*/*"))
                    },
                    onSelectDrive = viewModel::selectUsbDrive,
                    onRequestUsbPermission = viewModel::requestUsbPermission,
                    onRetryProbeDrive = viewModel::retryProbeDrive,
                    onRefreshDrives = viewModel::refreshUsbDrives,
                    onConfirmStartWrite = viewModel::startBootableUsbCreation,
                    onCancelWrite = viewModel::cancelActiveWrite,
                    onDismissFinishedStatus = viewModel::dismissFinishedStatus,
                    onStartUsbHealthCheck = viewModel::startUsbHealthCheck,
                    onCancelUsbHealthCheck = viewModel::cancelUsbHealthCheck,
                    onDismissUsbHealthCheck = viewModel::dismissUsbHealthCheck,
                    onDismissBanner = viewModel::dismissBanner,
                    onSetThemeMode = viewModel::setThemeMode,
                    onApplyCustomization = viewModel::saveAppearanceCustomization,
                    onResetCustomization = viewModel::resetAppearanceCustomization,
                    onResetAllSettings = viewModel::restoreDefaults,
                    onSetBufferOption = viewModel::setBufferOption,
                    onSetVerifyAfterWrite = viewModel::setVerifyAfterWrite,
                        onClearHistory = viewModel::clearHistory,
                        onClearLogs = viewModel::clearLogs
                    )

                    AnimatedVisibility(
                        visible = !splashDismissed,
                        exit = fadeOut(animationSpec = tween(durationMillis = 220))
                    ) {
                        AppSplashScreen(
                            isStartupReady = isStartupReady,
                            onFinished = viewModel::onSplashFinished
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttachIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUsbDrives()
    }

    override fun onDestroy() {
        updateKeepScreenOnFlag(false)
        super.onDestroy()
    }

    /**
     * Lifecycle-aware observer attached to the Activity window (independent of tab navigation).
     * Keeps the screen awake throughout PREPARING, PARTITIONING, WRITING (including USB recovery),
     * FLUSHING_CACHE, and VERIFYING, and restores normal screen timeout immediately upon
     * COMPLETED, FAILED, CANCELLED, or IDLE.
     */
    private fun observeKeepScreenOnState() {
        val app = application as UsbBootableApp
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.writeStateStore.progress
                    .map { shouldKeepScreenOn(it.phase) }
                    .distinctUntilChanged()
                    .collect { keepAwake ->
                        updateKeepScreenOnFlag(keepAwake)
                    }
            }
        }
    }

    private fun updateKeepScreenOnFlag(keepAwake: Boolean) {
        val currentFlags = window.attributes.flags
        val isFlagSet = (currentFlags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (keepAwake && !isFlagSet) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            (application as? UsbBootableApp)?.logger?.info(
                TAG,
                "FLAG_KEEP_SCREEN_ON enabled while USB write/verify operation is active"
            )
        } else if (!keepAwake && isFlagSet) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            (application as? UsbBootableApp)?.logger?.info(
                TAG,
                "FLAG_KEEP_SCREEN_ON cleared; normal screen timeout restored"
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun handleUsbAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        viewModel.onUsbAttachIntent(device?.deviceId)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        fun shouldKeepScreenOn(phase: WritePhase): Boolean = phase.isActive
    }
}
