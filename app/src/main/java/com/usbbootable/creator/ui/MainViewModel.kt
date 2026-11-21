package com.usbbootable.creator.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usbbootable.creator.UsbBootableApp
import com.usbbootable.creator.core.image.FlashingMode
import com.usbbootable.creator.core.image.IsoImageAnalyzer
import com.usbbootable.creator.core.image.SafImageSource
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.safety.PreflightCheckResult
import com.usbbootable.creator.core.safety.PreflightValidator
import com.usbbootable.creator.core.storage.AppSettings
import com.usbbootable.creator.core.storage.OperationHistoryRecord
import com.usbbootable.creator.core.storage.WriteBufferOption
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.usb.UsbEvent
import com.usbbootable.creator.core.usb.UsbTransportSupport
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.LogEntry
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.service.PendingWriteJob
import com.usbbootable.creator.service.UsbWriteService
import com.usbbootable.creator.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTab(val label: String) {
    HOME("Home"),
    HISTORY("History"),
    SETTINGS("Settings")
}

data class DashboardUiState(
    val selectedTab: AppTab = AppTab.HOME,
    val flashingMode: FlashingMode = FlashingMode.AUTO,
    val isUsbHostSupported: Boolean = true,
    val selectedIso: SelectedIsoInfo? = null,
    val isAnalyzingIso: Boolean = false,
    val isoError: String? = null,
    val availableDrives: List<UsbDriveInfo> = emptyList(),
    val selectedDrive: UsbDriveInfo? = null,
    val preflight: PreflightCheckResult = PreflightValidator.validate(true, null, null),
    val writeProgress: WriteProgress = WriteProgress(),
    val logs: List<LogEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val history: List<OperationHistoryRecord> = emptyList(),
    val bannerMessage: String? = null
) {
    val themeMode: ThemeMode get() = settings.themeMode
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as UsbBootableApp
    private val usbMonitor = app.usbMonitor
    private val writeStore = app.writeStateStore
    private val prefsStore = app.preferencesStore
    private val logger = app.logger

    private val selectedTabFlow = MutableStateFlow(AppTab.HOME)
    private val flashingModeFlow = MutableStateFlow(FlashingMode.AUTO)
    private val selectedIsoFlow = MutableStateFlow<SelectedIsoInfo?>(null)
    private val isAnalyzingIsoFlow = MutableStateFlow(false)
    private val isoErrorFlow = MutableStateFlow<String?>(null)
    private val selectedDriveIdFlow = MutableStateFlow<Int?>(null)
    private val bannerMessageFlow = MutableStateFlow<String?>(null)

    private data class IsoSelectionBundle(
        val iso: SelectedIsoInfo?,
        val analyzing: Boolean,
        val error: String?,
        val flashingMode: FlashingMode
    )

    private data class UiMetaBundle(
        val selectedTab: AppTab,
        val selectedDriveId: Int?,
        val settings: AppSettings,
        val history: List<OperationHistoryRecord>,
        val bannerMessage: String?
    )

    private val isoBundleFlow = combine(
        selectedIsoFlow,
        isAnalyzingIsoFlow,
        isoErrorFlow,
        flashingModeFlow
    ) { iso, loading, err, mode ->
        IsoSelectionBundle(iso, loading, err, mode)
    }

    private val metaBundleFlow = combine(
        selectedTabFlow,
        selectedDriveIdFlow,
        prefsStore.settings,
        prefsStore.history,
        bannerMessageFlow
    ) { tab, id, settings, history, banner ->
        UiMetaBundle(tab, id, settings, history, banner)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        isoBundleFlow,
        usbMonitor.drives,
        writeStore.progress,
        logger.entries,
        metaBundleFlow
    ) { isoBundle, drives, progress, logs, meta ->
        val selectedDrive = meta.selectedDriveId?.let { id ->
            drives.firstOrNull { it.deviceId == id }
        }
        val preflight = PreflightValidator.validate(
            isUsbHostSupported = usbMonitor.isUsbHostSupported,
            iso = isoBundle.iso,
            drive = selectedDrive,
            flashingMode = isoBundle.flashingMode
        )
        DashboardUiState(
            selectedTab = meta.selectedTab,
            flashingMode = isoBundle.flashingMode,
            isUsbHostSupported = usbMonitor.isUsbHostSupported,
            selectedIso = isoBundle.iso,
            isAnalyzingIso = isoBundle.analyzing,
            isoError = isoBundle.error,
            availableDrives = drives,
            selectedDrive = selectedDrive,
            preflight = preflight,
            writeProgress = progress,
            logs = logs,
            settings = meta.settings,
            history = meta.history,
            bannerMessage = meta.bannerMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DashboardUiState(
            isUsbHostSupported = usbMonitor.isUsbHostSupported,
            settings = prefsStore.settings.value,
            history = prefsStore.history.value
        )
    )

    init {
        usbMonitor.refreshDevices()
        observeUsbEvents()
    }

    private fun observeUsbEvents() {
        viewModelScope.launch {
            usbMonitor.events.collect { event ->
                when (event) {
                    is UsbEvent.DeviceAttached -> {
                        bannerMessageFlow.value = "USB OTG device connected: ${event.name}"
                        if (selectedDriveIdFlow.value == null) {
                            selectedDriveIdFlow.value = event.deviceId
                            val drive = usbMonitor.drives.value.firstOrNull { it.deviceId == event.deviceId }
                            if (drive != null && drive.transportSupport == UsbTransportSupport.SUPPORTED_BOT && !drive.hasPermission) {
                                usbMonitor.requestPermission(event.deviceId)
                            }
                        }
                    }

                    is UsbEvent.DeviceDetached -> {
                        if (selectedDriveIdFlow.value == event.deviceId) {
                            selectedDriveIdFlow.value = null
                        }
                        bannerMessageFlow.value = "USB drive disconnected: ${event.name}"
                    }

                    is UsbEvent.PermissionResult -> {
                        if (!event.granted) {
                            bannerMessageFlow.value =
                                "USB Host permission was denied. Tap SELECT USB DRIVE to grant permission."
                        } else {
                            selectedDriveIdFlow.value = event.deviceId
                        }
                    }
                }
            }
        }
    }

    fun onIsoUriSelected(uri: Uri) {
        if (writeStore.progress.value.phase.isActive) return

        viewModelScope.launch {
            isAnalyzingIsoFlow.value = true
            isoErrorFlow.value = null
            bannerMessageFlow.value = null

            val result = withContext(Dispatchers.IO) {
                try {
                    // Best-effort persistable read permission across process restarts
                    try {
                        app.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) {
                    }

                    val (displayName, queriedSize) = querySafMetadata(uri)
                    logger.info(TAG, "Inspecting ISO via SAF: '$displayName' (queried=${ByteUtils.formatBytes(queriedSize)})")

                    SafImageSource(app, uri, queriedSize).use { source ->
                        val info = IsoImageAnalyzer.analyze(
                            source = source,
                            uriString = uri.toString(),
                            displayName = displayName
                        )
                        logger.success(
                            TAG,
                            "Analyzed '${info.displayName}': ${info.classification} | " +
                                "size=${ByteUtils.formatBytes(info.sizeBytes)} | " +
                                "strategy=${info.writeStrategy} | bootable=${info.bootSupport.isGuaranteedBootable}"
                        )
                        Result.success(info)
                    }
                } catch (e: Exception) {
                    logger.error(TAG, "ISO validation failed: ${e.message}")
                    Result.failure<SelectedIsoInfo>(e)
                }
            }

            result.fold(
                onSuccess = { info ->
                    selectedIsoFlow.value = info
                    isoErrorFlow.value = null
                },
                onFailure = { err ->
                    selectedIsoFlow.value = null
                    isoErrorFlow.value = err.message ?: "Unable to read the selected ISO image."
                }
            )
            isAnalyzingIsoFlow.value = false
        }
    }

    fun selectUsbDrive(deviceId: Int) {
        if (writeStore.progress.value.phase.isActive) return
        selectedDriveIdFlow.value = deviceId
        val drive = usbMonitor.drives.value.firstOrNull { it.deviceId == deviceId } ?: return
        if (drive.transportSupport == UsbTransportSupport.SUPPORTED_BOT) {
            if (!drive.hasPermission) {
                usbMonitor.requestPermission(deviceId)
            } else if (!drive.isProbed) {
                usbMonitor.probeDriveAsync(deviceId)
            }
        }
    }

    fun requestUsbPermission(deviceId: Int) {
        usbMonitor.requestPermission(deviceId)
    }

    fun retryProbeDrive(deviceId: Int) {
        usbMonitor.probeDriveAsync(deviceId)
    }

    fun refreshUsbDrives() {
        usbMonitor.refreshDevices()
        val currentId = selectedDriveIdFlow.value
        val drives = usbMonitor.drives.value
        if (currentId != null && drives.none { it.deviceId == currentId }) {
            selectedDriveIdFlow.value = null
        } else if (currentId == null && drives.size == 1) {
            selectedDriveIdFlow.value = drives.first().deviceId
        }
    }

    fun startBootableUsbCreation() {
        if (writeStore.progress.value.phase.isActive) return
        val state = uiState.value
        val iso = state.selectedIso ?: return
        val drive = state.selectedDrive ?: return

        viewModelScope.launch {
            // Re-validate ISO file accessibility immediately before starting destructive write
            val accessible = withContext(Dispatchers.IO) {
                try {
                    SafImageSource(app, Uri.parse(iso.uriString), iso.sizeBytes).use { it.sizeBytes > 0L }
                } catch (_: Exception) {
                    false
                }
            }
            if (!accessible) {
                selectedIsoFlow.value = iso.copy(isAccessible = false)
                isoErrorFlow.value = "ISO file is no longer accessible. Please re-select the ISO file."
                return@launch
            }

            val recheck = PreflightValidator.validate(
                isUsbHostSupported = usbMonitor.isUsbHostSupported,
                iso = iso,
                drive = drive,
                flashingMode = state.flashingMode
            )
            if (!recheck.canProceed) {
                bannerMessageFlow.value = recheck.blockers.firstOrNull()
                return@launch
            }

            bannerMessageFlow.value = null
            val currentSettings = prefsStore.settings.value
            writeStore.pendingJob = PendingWriteJob(
                isoInfo = iso,
                driveInfo = drive,
                requestedBufferBytes = currentSettings.bufferOption.bytesOrAuto,
                verifyAfterWrite = currentSettings.verifyAfterWrite
            )
            selectedTabFlow.value = AppTab.HOME
            logger.info(
                TAG,
                "User confirmed destructive erase. Starting foreground USB write: '${iso.displayName}' -> '${drive.displayName}' " +
                    "(buffer=${currentSettings.bufferOption.label}, verify=${currentSettings.verifyAfterWrite})"
            )
            UsbWriteService.startWrite(app)
        }
    }

    fun cancelActiveWrite() {
        // Immediately flip UI state to Cancelled and suppress trailing progress updates before IPC roundtrip
        writeStore.requestCancel()
        UsbWriteService.cancelWrite(app)
    }

    fun selectTab(tab: AppTab) {
        selectedTabFlow.value = tab
    }

    fun setFlashingMode(mode: FlashingMode) {
        if (writeStore.progress.value.phase.isActive) return
        flashingModeFlow.value = mode
        logger.info(TAG, "Flashing mode changed to ${mode.label}")
    }

    fun setThemeMode(mode: ThemeMode) {
        prefsStore.setThemeMode(mode)
    }

    fun setBufferOption(option: WriteBufferOption) {
        prefsStore.setBufferOption(option)
        logger.info(TAG, "USB I/O buffer mode changed to ${option.label}")
    }

    fun setVerifyAfterWrite(enabled: Boolean) {
        prefsStore.setVerifyAfterWrite(enabled)
        logger.info(TAG, "Post-write SHA-256 verification set to $enabled")
    }

    fun clearHistory() {
        prefsStore.clearHistory()
    }

    fun dismissFinishedStatus() {
        writeStore.resetToIdle()
    }

    fun dismissBanner() {
        bannerMessageFlow.value = null
    }

    fun clearLogs() {
        logger.clear()
    }

    fun cycleThemeMode() {
        val next = when (prefsStore.settings.value.themeMode) {
            ThemeMode.SYSTEM -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.SYSTEM
        }
        prefsStore.setThemeMode(next)
    }

    private fun querySafMetadata(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "image.iso"
        var size = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        name = cursor.getString(nameIdx)
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        return Pair(name, size)
    }

    companion object {
        private const val TAG = "ViewModel"
    }
}
