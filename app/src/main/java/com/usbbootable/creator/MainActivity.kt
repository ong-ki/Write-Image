package com.usbbootable.creator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.ui.MainViewModel
import com.usbbootable.creator.ui.screens.MainTabsScaffold
import com.usbbootable.creator.ui.theme.UsbBootableCreatorTheme
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()
        handleUsbAttachIntent(intent)
        observeKeepScreenOnState()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            UsbBootableCreatorTheme(themeMode = state.themeMode) {
                MainTabsScaffold(
                    state = state,
                    onSelectTab = viewModel::selectTab,
                    onSetFlashingMode = viewModel::setFlashingMode,
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
                    onDismissBanner = viewModel::dismissBanner,
                    onSetThemeMode = viewModel::setThemeMode,
                    onSetBufferOption = viewModel::setBufferOption,
                    onSetVerifyAfterWrite = viewModel::setVerifyAfterWrite,
                    onClearHistory = viewModel::clearHistory,
                    onClearLogs = viewModel::clearLogs
                )
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
        val app = application as UsbBootableApp
        updateKeepScreenOnFlag(shouldKeepScreenOn(app.writeStateStore.progress.value.phase))
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
        viewModel.refreshUsbDrives()
        if (device != null) {
            viewModel.selectUsbDrive(device.deviceId)
        }
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
