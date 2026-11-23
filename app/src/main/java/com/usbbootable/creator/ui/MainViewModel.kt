package com.usbbootable.creator.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usbbootable.creator.UsbBootableApp
import com.usbbootable.creator.core.image.DetectedOsFamily
import com.usbbootable.creator.core.image.FlashingMode
import com.usbbootable.creator.core.image.IsoImageAnalyzer
import com.usbbootable.creator.core.image.SafImageSource
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.safety.PreflightCheckResult
import com.usbbootable.creator.core.safety.PreflightValidator
import com.usbbootable.creator.core.storage.AppSettings
import com.usbbootable.creator.core.storage.AppearanceCustomization
import com.usbbootable.creator.core.storage.OperationHistoryRecord
import com.usbbootable.creator.core.storage.WriteBufferOption
import com.usbbootable.creator.core.usb.UsbDeviceSession
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.usb.UsbEvent
import com.usbbootable.creator.core.usb.UsbHealthChecker
import com.usbbootable.creator.core.usb.UsbHealthPhase
import com.usbbootable.creator.core.usb.UsbHealthProgress
import com.usbbootable.creator.core.usb.UsbHealthStatus
import com.usbbootable.creator.core.usb.UsbTransportSupport
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.LogEntry
import com.usbbootable.creator.core.writer.TransferSpeedSnapshot
import com.usbbootable.creator.core.writer.WindowsBypassOptions
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.service.PendingWriteJob
import com.usbbootable.creator.service.UsbWriteService
import com.usbbootable.creator.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

enum class AppTab(val label: String) {
    HOME("Home"),
    HISTORY("History"),
    SETTINGS("Settings")
}

data class DashboardUiState(
    val selectedTab: AppTab = AppTab.HOME,
    val flashingMode: FlashingMode = FlashingMode.AUTO,
    /** False until the user explicitly confirms a mode; AUTO is a valid default for flashing. */
    val flashingModeSelected: Boolean = false,
    val isUsbHostSupported: Boolean = true,
    val selectedIso: SelectedIsoInfo? = null,
    val isAnalyzingIso: Boolean = false,
    val isoError: String? = null,
    val availableDrives: List<UsbDriveInfo> = emptyList(),
    val selectedDrive: UsbDriveInfo? = null,
    val preflight: PreflightCheckResult = PreflightValidator.validate(true, null, null),
    val writeProgress: WriteProgress = WriteProgress(),
    val speedSnapshot: TransferSpeedSnapshot = TransferSpeedSnapshot.EMPTY,
    val elapsedSeconds: Long = 0L,
    /** True from the moment Create is confirmed, before the service publishes its first phase. */
    val operationTimerRunning: Boolean = false,
    val usbHealthProgress: UsbHealthProgress = UsbHealthProgress(),
    val settings: AppSettings = AppSettings(),
    val customization: AppearanceCustomization = AppearanceCustomization.DEFAULT,
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
    private val customizationStore = app.customizationStore
    private val logger = app.logger
    private val startupCoordinator = StartupCoordinator()
    val isStartupReady: StateFlow<Boolean> = startupCoordinator.isStartupReady
    val splashDismissed: StateFlow<Boolean> = startupCoordinator.splashDismissed

    fun onSplashFinished() {
        startupCoordinator.markSplashDismissed()
    }

    private val selectedTabFlow = MutableStateFlow(AppTab.HOME)
    private val flashingModeFlow = MutableStateFlow(FlashingMode.AUTO)
    private val flashingModeSelectedFlow = MutableStateFlow(false)
    private val selectedIsoFlow = MutableStateFlow<SelectedIsoInfo?>(null)
    private val isAnalyzingIsoFlow = MutableStateFlow(false)
    private val isoErrorFlow = MutableStateFlow<String?>(null)
    private val selectedDriveIdFlow = MutableStateFlow<Int?>(null)
    private val bannerMessageFlow = MutableStateFlow<String?>(null)
    private var transientBannerJob: Job? = null
    private val usbHealthProgressFlow = MutableStateFlow(UsbHealthProgress())
    private class ActiveHealthRun(
        val deviceId: Int,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val disconnectReset: AtomicBoolean = AtomicBoolean(false),
        val disconnected: AtomicBoolean = AtomicBoolean(false)
    )

    private var isoAnalysisJob: Job? = null
    private var isoAnalysisGeneration = 0L
    private var usbRefreshJob: Job? = null
    private var healthCheckJob: Job? = null
    private val isStartingWrite = AtomicBoolean(false)
    @Volatile
    private var activeHealthRun: ActiveHealthRun? = null

    private data class IsoSelectionBundle(
        val iso: SelectedIsoInfo?,
        val analyzing: Boolean,
        val error: String?,
        val flashingMode: FlashingMode,
        val flashingModeSelected: Boolean
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
        flashingModeFlow,
        flashingModeSelectedFlow
    ) { iso, loading, err, mode, modeSelected ->
        IsoSelectionBundle(iso, loading, err, mode, modeSelected)
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

    private data class ProgressWithTimer(
        val progress: WriteProgress,
        val speedSnapshot: TransferSpeedSnapshot,
        val elapsedSeconds: Long,
        val timerRunning: Boolean,
        val healthProgress: UsbHealthProgress
    )

    // Exposed separately so diagnostic log appends never trigger Home screen recompositions
    val logs: StateFlow<List<LogEntry>> = logger.entries

    @OptIn(ExperimentalCoroutinesApi::class)
    private val elapsedSecondsFlow = writeStore.timerState
        .flatMapLatest { timer ->
            flow {
                emit(timer.elapsedSeconds())
                while (timer.isRunning && currentCoroutineContext().isActive) {
                    delay(500L)
                    emit(timer.elapsedSeconds())
                }
            }
        }
        .distinctUntilChanged()

    private val progressBundleFlow = combine(
        writeStore.progress,
        writeStore.speedSnapshot,
        elapsedSecondsFlow,
        writeStore.timerState,
        usbHealthProgressFlow
    ) { progress, speed, elapsed, timer, health ->
        ProgressWithTimer(progress, speed, elapsed, timer.isRunning, health)
    }

    private val customizationFlow: StateFlow<AppearanceCustomization> = customizationStore.customization
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppearanceCustomization.DEFAULT)

    val uiState: StateFlow<DashboardUiState> = combine(
        isoBundleFlow,
        usbMonitor.drives,
        progressBundleFlow,
        metaBundleFlow,
        customizationFlow
    ) { isoBundle, drives, progressBundle, meta, customization ->
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
            flashingModeSelected = isoBundle.flashingModeSelected,
            isUsbHostSupported = usbMonitor.isUsbHostSupported,
            selectedIso = isoBundle.iso,
            isAnalyzingIso = isoBundle.analyzing,
            isoError = isoBundle.error,
            availableDrives = drives,
            selectedDrive = selectedDrive,
            preflight = preflight,
            writeProgress = progressBundle.progress,
            speedSnapshot = progressBundle.speedSnapshot,
            elapsedSeconds = progressBundle.elapsedSeconds,
            operationTimerRunning = progressBundle.timerRunning,
            usbHealthProgress = progressBundle.healthProgress,
            settings = meta.settings,
            customization = customization,
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
        runStartupInitialization()
        observeUsbEvents()
        monitorTransferSpeed()
    }

    /**
     * Polls the store for real completed-write bytes on a background dispatcher. The store's
     * tracker throttles to real samples; an idle poll cycle during a stalled write records a
     * zero-throughput sample so the graph falls instead of freezing. No simulated data is used.
     */
    private fun monitorTransferSpeed() {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (writeStore.progress.value.phase == WritePhase.WRITING) {
                    writeStore.sampleSpeed()
                    delay(SPEED_SAMPLE_INTERVAL_MS)
                } else {
                    delay(SPEED_IDLE_POLL_MS)
                }
            }
        }
    }

    private fun runStartupInitialization() {
        viewModelScope.launch(Dispatchers.IO) {
            startupCoordinator.runStartup(
                loadCustomization = {
                    customizationStore.customization.first()
                },
                refreshUsb = {
                    usbMonitor.refreshDevices()
                },
                onError = { message, error ->
                    logger.warn(TAG, "$message: ${error.message}")
                }
            )
        }
    }

    private fun observeUsbEvents() {
        viewModelScope.launch {
            usbMonitor.events.collect { event ->
                when (event) {
                    is UsbEvent.DeviceAttached -> {
                        val drive = usbMonitor.drives.value.firstOrNull { it.deviceId == event.deviceId }
                            ?: return@collect
                        bannerMessageFlow.value = "USB OTG device connected: ${event.name}"
                        if (selectedDriveIdFlow.value == null) {
                            selectedDriveIdFlow.value = event.deviceId
                            if (drive.transportSupport == UsbTransportSupport.SUPPORTED_BOT && !drive.hasPermission) {
                                usbMonitor.requestPermission(event.deviceId)
                            }
                        }
                    }

                    is UsbEvent.DeviceDetached -> {
                        handleUsbDisconnected(event.deviceId)
                    }

                    is UsbEvent.PermissionResult -> {
                        if (!event.granted) {
                            bannerMessageFlow.value =
                                "USB Host permission was denied. Tap SELECT USB DRIVE to grant permission."
                        } else if (usbMonitor.drives.value.any { it.deviceId == event.deviceId }) {
                            selectedDriveIdFlow.value = event.deviceId
                        }
                    }
                }
            }
        }
    }

    private fun handleUsbDisconnected(disconnectedDeviceId: Int?) {
        val drivesEmpty = usbMonitor.drives.value.isEmpty()
        val wasSelected = disconnectedDeviceId != null && selectedDriveIdFlow.value == disconnectedDeviceId
        val wasActiveWriteTarget = disconnectedDeviceId != null &&
            writeStore.pendingJob?.driveInfo?.deviceId == disconnectedDeviceId
        val currentHealthRun = activeHealthRun
        val wasActiveHealthTarget = disconnectedDeviceId != null &&
            currentHealthRun?.deviceId == disconnectedDeviceId

        if (wasSelected || drivesEmpty) {
            selectedDriveIdFlow.value = null
        }

        // Clear temporary USB connection / permission / status banners immediately
        bannerMessageFlow.value = null

        val isWriteActive = writeStore.progress.value.phase.isActive
        val hasPendingOrStartingWrite = writeStore.pendingJob != null ||
            isStartingWrite.get() ||
            writeStore.timerState.value.isRunning
        if ((isWriteActive || hasPendingOrStartingWrite) && (wasActiveWriteTarget || wasSelected || drivesEmpty)) {
            // Deliver cancellation while pendingJob still identifies the active write.
            // resetForDisconnect() clears pendingJob immediately, which would otherwise make
            // cancelWrite() treat the still-running service job as "nothing to cancel".
            UsbWriteService.cancelWrite(app)
            writeStore.resetForDisconnect(wasActiveWrite = isWriteActive || writeStore.pendingJob != null)
            if (isWriteActive) {
                bannerMessageFlow.value =
                    "USB drive disconnected. The write was aborted; the drive may contain partial data."
            }
        } else if (writeStore.progress.value.phase != WritePhase.IDLE && (wasSelected || drivesEmpty)) {
            writeStore.resetForDisconnect(wasActiveWrite = false)
        }

        val hasHealthState = usbHealthProgressFlow.value.phase != UsbHealthPhase.IDLE
        if ((hasHealthState || currentHealthRun != null) && (wasActiveHealthTarget || wasSelected || drivesEmpty)) {
            currentHealthRun?.disconnectReset?.set(true)
            currentHealthRun?.cancelled?.set(true)
            healthCheckJob?.cancel()
            usbHealthProgressFlow.value = UsbHealthProgress()
        }
    }

    fun onIsoUriSelected(uri: Uri) {
        if (writeStore.progress.value.phase.isActive) return

        // A newer selection supersedes any in-flight analysis. The generation token also
        // prevents a slow, already-running SAF read from overwriting the newer result.
        val generation = ++isoAnalysisGeneration
        isoAnalysisJob?.cancel()
        // A different ISO invalidates the previous mode choice; the guided workflow returns to
        // Step 3 until the user confirms a mode for the new image.
        flashingModeSelectedFlow.value = false
        isoAnalysisJob = viewModelScope.launch {
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(TAG, "ISO validation failed: ${e.message}")
                    Result.failure<SelectedIsoInfo>(e)
                }
            }

            if (generation != isoAnalysisGeneration) return@launch

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
        val drive = usbMonitor.drives.value.firstOrNull { it.deviceId == deviceId } ?: return
        selectedDriveIdFlow.value = deviceId
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
        refreshUsbDrivesInternal(selectDeviceId = null)
    }

    fun onUsbAttachIntent(deviceId: Int?) {
        refreshUsbDrivesInternal(selectDeviceId = deviceId)
    }

    private fun refreshUsbDrivesInternal(selectDeviceId: Int?) {
        if (usbRefreshJob?.isActive == true && selectDeviceId == null) return
        val priorJob = usbRefreshJob
        usbRefreshJob = viewModelScope.launch {
            priorJob?.join()
            withContext(Dispatchers.IO) {
                usbMonitor.refreshDevices()
            }
            val currentId = selectedDriveIdFlow.value
            val drives = usbMonitor.drives.value
            if (currentId != null && drives.none { it.deviceId == currentId }) {
                handleUsbDisconnected(currentId)
            } else if (selectDeviceId != null && drives.any { it.deviceId == selectDeviceId }) {
                selectUsbDrive(selectDeviceId)
            } else if (selectedDriveIdFlow.value == null && drives.size == 1) {
                selectedDriveIdFlow.value = drives.first().deviceId
            }
        }
    }

    fun startBootableUsbCreation() {
        if (writeStore.progress.value.phase.isActive ||
            writeStore.timerState.value.isRunning ||
            writeStore.pendingJob != null ||
            usbHealthProgressFlow.value.phase.isActive ||
            healthCheckJob?.isActive == true
        ) {
            return
        }
        if (!isStartingWrite.compareAndSet(false, true)) return
        if (!uiState.value.flashingModeSelected) {
            isStartingWrite.set(false)
            bannerMessageFlow.value = "Select a flashing mode before creating the bootable USB."
            return
        }
        if (isAnalyzingIsoFlow.value) {
            isStartingWrite.set(false)
            bannerMessageFlow.value = "Wait for ISO analysis to finish before starting a write."
            return
        }
        val state = uiState.value
        val iso = state.selectedIso
        val drive = state.selectedDrive
        if (iso == null || drive == null) {
            isStartingWrite.set(false)
            return
        }

        // Reset disconnect/cancel guards and start elapsed timer immediately when user initiates Start/Create
        writeStore.prepareForNewWrite()
        writeStore.startTimer()

        viewModelScope.launch {
            var writeDispatched = false
            try {
                // Re-validate ISO file accessibility immediately before starting destructive write
                val accessible = withContext(Dispatchers.IO) {
                    try {
                        SafImageSource(app, iso.uriString.toUri(), iso.sizeBytes).use { it.sizeBytes > 0L }
                    } catch (_: Exception) {
                        false
                    }
                }
                if (!accessible) {
                    selectedIsoFlow.value = iso.copy(isAccessible = false)
                    isoErrorFlow.value = "ISO file is no longer accessible. Please re-select the ISO file."
                    return@launch
                }

                // Re-read live state after the IO suspension to prevent flashing a detached
                // drive, a superseded ISO selection, or colliding with a health diagnostic.
                val liveState = uiState.value
                val liveIso = liveState.selectedIso
                val liveDrive = liveState.selectedDrive
                if (liveIso == null || liveIso.uriString != iso.uriString ||
                    liveDrive == null || liveDrive.deviceId != drive.deviceId ||
                    !usbMonitor.isDeviceConnected(liveDrive.deviceId)
                ) {
                    bannerMessageFlow.value = "Selected USB drive or ISO changed before flashing started."
                    return@launch
                }
                if (usbHealthProgressFlow.value.phase.isActive || healthCheckJob?.isActive == true) {
                    return@launch
                }

                val recheck = PreflightValidator.validate(
                    isUsbHostSupported = usbMonitor.isUsbHostSupported,
                    iso = liveIso,
                    drive = liveDrive,
                    flashingMode = liveState.flashingMode
                )
                if (!recheck.canProceed) {
                    bannerMessageFlow.value = recheck.blockers.firstOrNull()
                    return@launch
                }

                bannerMessageFlow.value = null
                val currentSettings = prefsStore.settings.value
                val effectiveBypass = if (
                    liveIso.detectedOsFamily == DetectedOsFamily.WINDOWS &&
                    liveState.flashingMode != FlashingMode.LINUX
                ) {
                    currentSettings.windowsBypassOptions
                } else {
                    WindowsBypassOptions.NONE
                }
                writeStore.pendingJob = PendingWriteJob(
                    isoInfo = liveIso,
                    driveInfo = liveDrive,
                    requestedBufferBytes = currentSettings.bufferOption.bytesOrAuto,
                    verifyAfterWrite = currentSettings.verifyAfterWrite,
                    windowsBypassOptions = effectiveBypass
                )
                selectedTabFlow.value = AppTab.HOME
                val bypassNote = if (effectiveBypass.hasAnyEnabled) {
                    ", bypass=${effectiveBypass.enabledLabels().joinToString("+")}"
                } else {
                    ""
                }
                logger.info(
                    TAG,
                    "User confirmed destructive erase. Starting foreground USB write: '${liveIso.displayName}' -> '${liveDrive.displayName}' " +
                        "(buffer=${currentSettings.bufferOption.label}, verify=${currentSettings.verifyAfterWrite}$bypassNote)"
                )
                if (UsbWriteService.startWrite(app)) {
                    writeDispatched = true
                } else {
                    writeStore.pendingJob = null
                    bannerMessageFlow.value =
                        "Unable to start the USB write service. Keep the app in the foreground and try again."
                }
            } finally {
                if (!writeDispatched && !writeStore.progress.value.phase.isActive) {
                    writeStore.resetTimer()
                }
                isStartingWrite.set(false)
            }
        }
    }

    fun cancelActiveWrite() {
        // Immediately flip UI state to Cancelled and suppress trailing progress updates before IPC roundtrip
        writeStore.requestCancel()
        UsbWriteService.cancelWrite(app)
    }

    fun startUsbHealthCheck() {
        if (writeStore.progress.value.phase.isActive ||
            writeStore.pendingJob != null ||
            isStartingWrite.get() ||
            usbHealthProgressFlow.value.phase.isActive ||
            healthCheckJob?.isActive == true
        ) {
            return
        }
        val drive = uiState.value.selectedDrive ?: return
        if (drive.transportSupport != UsbTransportSupport.SUPPORTED_BOT || !drive.hasPermission) {
            bannerMessageFlow.value = "Grant USB permission for ${drive.displayName} before running a health check."
            return
        }
        // The SCSI probe owns the USB session while it runs. Starting diagnostics before it
        // completes would collide with the in-flight probe and fail with "drive is busy".
        if (!drive.isProbed) {
            if (drive.probeError != null) {
                bannerMessageFlow.value = "USB drive is not ready: ${drive.probeError}"
            } else {
                bannerMessageFlow.value =
                    "USB drive is still initializing. Wait a moment, then start the health check."
                usbMonitor.probeDriveAsync(drive.deviceId)
            }
            return
        }
        if (drive.isWriteProtected) {
            bannerMessageFlow.value =
                "USB drive is write-protected; a health check needs to write a temporary test file."
            return
        }
        if (drive.capacityBytes <= 0L) {
            bannerMessageFlow.value = "USB drive reports no readable media."
            return
        }
        val usbDevice = usbMonitor.getUsbDevice(drive.deviceId)
        if (usbDevice == null) {
            bannerMessageFlow.value = "Selected USB drive is no longer connected."
            return
        }

        val run = ActiveHealthRun(deviceId = drive.deviceId)
        activeHealthRun = run
        val startedNanos = System.nanoTime()

        usbHealthProgressFlow.value = UsbHealthProgress(
            phase = UsbHealthPhase.RUNNING,
            progressFraction = 0.02f,
            currentTest = "Initializing USB session",
            statusMessage = "Opening safe non-destructive USB OTG diagnostic session...",
            elapsedSeconds = 0L,
            driveName = drive.displayName
        )

        healthCheckJob = viewModelScope.launch {
            val disconnectWatcher = launch {
                usbMonitor.events.collect { event ->
                    if (event is UsbEvent.DeviceDetached && event.deviceId == drive.deviceId) {
                        run.disconnected.set(true)
                    }
                }
            }
            val timerTicker = launch {
                while (isActive) {
                    delay(500L)
                    if (activeHealthRun !== run) break
                    val elapsed = ((System.nanoTime() - startedNanos) / 1_000_000_000L).coerceAtLeast(0L)
                    var keepRunning = true
                    usbHealthProgressFlow.update { cur ->
                        if (activeHealthRun !== run || !cur.phase.isActive) {
                            keepRunning = false
                            cur
                        } else if (elapsed != cur.elapsedSeconds) {
                            cur.copy(elapsedSeconds = elapsed)
                        } else {
                            cur
                        }
                    }
                    if (!keepRunning) break
                }
            }

            usbMonitor.setWriteActive(true)
            try {
                val isCancelledCheck = { run.cancelled.get() }
                val isDisconnectedCheck = {
                    run.disconnected.get() || !usbMonitor.isDeviceConnected(drive.deviceId)
                }
                val requestedBuf = prefsStore.settings.value.bufferOption.bytesOrAuto

                val report = withContext(Dispatchers.IO) {
                    UsbDeviceSession.open(
                        usbManager = usbMonitor.usbManager,
                        device = usbDevice,
                        isDisconnected = isDisconnectedCheck,
                        isCancelled = { false }, // Allow safe temp-file cleanup writes even after cancel
                        requestedBufferBytes = requestedBuf,
                        logger = logger
                    ).use { session ->
                        UsbHealthChecker(logger).runDiagnostics(
                            device = session.blockDevice,
                            driveName = drive.displayName,
                            chunkBytes = session.recommendedBufferBytes,
                            isCancelled = isCancelledCheck,
                            isDisconnected = isDisconnectedCheck,
                            onProgress = { frac, testName, msg ->
                                if (!run.cancelled.get() && !run.disconnectReset.get() && activeHealthRun === run) {
                                    val elapsed = ((System.nanoTime() - startedNanos) / 1_000_000_000L).coerceAtLeast(0L)
                                    val safeFraction = if (frac.isFinite()) frac.coerceIn(0f, 1f) else 0f
                                    usbHealthProgressFlow.update { cur ->
                                        if (activeHealthRun !== run || !cur.phase.isActive) {
                                            cur
                                        } else {
                                            cur.copy(
                                                phase = UsbHealthPhase.RUNNING,
                                                progressFraction = safeFraction,
                                                currentTest = testName,
                                                statusMessage = msg,
                                                elapsedSeconds = elapsed
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                if (activeHealthRun !== run) return@launch
                if (run.disconnectReset.get()) {
                    usbHealthProgressFlow.value = UsbHealthProgress()
                    return@launch
                }
                val elapsed = ((System.nanoTime() - startedNanos) / 1_000_000_000L).coerceAtLeast(0L)
                val finalPhase = if (report.status == UsbHealthStatus.FAILED) {
                    UsbHealthPhase.FAILED
                } else {
                    UsbHealthPhase.COMPLETED
                }
                usbHealthProgressFlow.value = UsbHealthProgress(
                    phase = finalPhase,
                    progressFraction = 1f,
                    currentTest = "Diagnostic Complete",
                    statusMessage = "Status: ${report.status.label}",
                    elapsedSeconds = elapsed,
                    driveName = drive.displayName,
                    report = report.copy(elapsedSeconds = elapsed)
                )
                logger.info(TAG, "USB health check completed for '${drive.displayName}': ${report.status.label}")
            } catch (_: CancellationException) {
                if (activeHealthRun !== run) return@launch
                if (run.disconnectReset.get()) {
                    usbHealthProgressFlow.value = UsbHealthProgress()
                    return@launch
                }
                val elapsed = ((System.nanoTime() - startedNanos) / 1_000_000_000L).coerceAtLeast(0L)
                usbHealthProgressFlow.update { cur ->
                    UsbHealthProgress(
                        phase = UsbHealthPhase.CANCELLED,
                        progressFraction = cur.progressFraction,
                        currentTest = "Cancelled",
                        statusMessage = "USB health check cancelled safely. Temporary test file cleaned up.",
                        elapsedSeconds = elapsed,
                        driveName = drive.displayName
                    )
                }
                logger.warn(TAG, "USB health check cancelled by user.")
            } catch (e: Exception) {
                if (activeHealthRun !== run) return@launch
                if (run.disconnectReset.get()) {
                    usbHealthProgressFlow.value = UsbHealthProgress()
                    return@launch
                }
                val elapsed = ((System.nanoTime() - startedNanos) / 1_000_000_000L).coerceAtLeast(0L)
                logger.error(TAG, "USB health check error: ${e.message}")
                usbHealthProgressFlow.update { cur ->
                    UsbHealthProgress(
                        phase = UsbHealthPhase.FAILED,
                        progressFraction = cur.progressFraction,
                        currentTest = "Diagnostic Failed",
                        statusMessage = "Unable to complete USB health check.",
                        elapsedSeconds = elapsed,
                        driveName = drive.displayName,
                        errorMessage = e.message ?: "Unexpected USB I/O error."
                    )
                }
            } finally {
                timerTicker.cancel()
                disconnectWatcher.cancel()
                if (activeHealthRun === run) {
                    activeHealthRun = null
                    if (!writeStore.progress.value.phase.isActive && writeStore.pendingJob == null) {
                        usbMonitor.setWriteActive(false)
                    }
                }
            }
        }
    }

    fun cancelUsbHealthCheck() {
        activeHealthRun?.cancelled?.set(true)
        healthCheckJob?.cancel()
    }

    fun dismissUsbHealthCheck() {
        if (!usbHealthProgressFlow.value.phase.isActive) {
            usbHealthProgressFlow.value = UsbHealthProgress()
        }
    }

    override fun onCleared() {
        activeHealthRun?.cancelled?.set(true)
        healthCheckJob?.cancel()
        super.onCleared()
    }

    fun selectTab(tab: AppTab) {
        selectedTabFlow.value = tab
    }

    fun setFlashingMode(mode: FlashingMode) {
        if (writeStore.progress.value.phase.isActive) return
        flashingModeFlow.value = mode
        flashingModeSelectedFlow.value = true
        logger.info(TAG, "Flashing mode changed to ${mode.label}")
    }

    fun setThemeMode(mode: ThemeMode) {
        prefsStore.setThemeMode(mode)
    }

    fun saveAppearanceCustomization(customization: AppearanceCustomization) {
        viewModelScope.launch {
            try {
                customizationStore.saveCustomization(customization)
                logger.info(
                    TAG,
                    "Saved theme customization: barStyle=${customization.progressBarStyle.label}, " +
                        "neon=${customization.neonTheme.neonEnabled}"
                )
            } catch (e: IOException) {
                logger.warn(TAG, "Failed to save theme customization: ${e.message}")
            }
        }
    }

    fun resetAppearanceCustomization() {
        viewModelScope.launch {
            try {
                customizationStore.resetDefaults()
                logger.info(TAG, "Reset progress bar style and neon theme to defaults")
            } catch (e: IOException) {
                logger.warn(TAG, "Failed to reset theme customization: ${e.message}")
            }
        }
    }

    /**
     * Restores every user-customizable preference (SharedPreferences + appearance DataStore).
     * Operation history, ISO files, USB state, and device configuration are intentionally untouched.
     */
    fun restoreDefaults() {
        if (writeStore.progress.value.phase.isActive) return
        prefsStore.resetDefaults()
        flashingModeFlow.value = FlashingMode.AUTO
        flashingModeSelectedFlow.value = false
        viewModelScope.launch {
            try {
                customizationStore.resetDefaults()
                logger.info(TAG, "Restored all customizable settings to defaults; history and user files preserved")
                showTransientBanner("Defaults restored. Your files and operation history were not changed.")
            } catch (e: IOException) {
                logger.warn(TAG, "Failed to reset appearance preferences during restoreDefaults: ${e.message}")
                showTransientBanner("Core settings restored, but appearance preferences could not be saved.")
            }
        }
    }

    private fun showTransientBanner(message: String) {
        bannerMessageFlow.value = message
        transientBannerJob?.cancel()
        transientBannerJob = viewModelScope.launch {
            delay(3_000L)
            if (bannerMessageFlow.value == message) {
                bannerMessageFlow.value = null
            }
        }
    }

    fun setBufferOption(option: WriteBufferOption) {
        prefsStore.setBufferOption(option)
        logger.info(TAG, "USB I/O buffer mode changed to ${option.label}")
    }

    fun setVerifyAfterWrite(enabled: Boolean) {
        prefsStore.setVerifyAfterWrite(enabled)
        logger.info(TAG, "Post-write SHA-256 verification set to $enabled")
    }

    fun setWindowsBypassTpm(enabled: Boolean) {
        updateWindowsBypass { it.copy(bypassTpm = enabled) }
    }

    fun setWindowsBypassSecureBoot(enabled: Boolean) {
        updateWindowsBypass { it.copy(bypassSecureBoot = enabled) }
    }

    fun setWindowsBypassRam(enabled: Boolean) {
        updateWindowsBypass { it.copy(bypassRam = enabled) }
    }

    fun setWindowsBypassCpu(enabled: Boolean) {
        updateWindowsBypass { it.copy(bypassCpu = enabled) }
    }

    fun setWindowsBypassAll(enabled: Boolean) {
        updateWindowsBypass { it.withAllEnabled(enabled) }
    }

    private fun updateWindowsBypass(transform: (WindowsBypassOptions) -> WindowsBypassOptions) {
        if (writeStore.progress.value.phase.isActive) return
        val updated = transform(prefsStore.settings.value.windowsBypassOptions)
        prefsStore.setWindowsBypassOptions(updated)
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
        private const val SPEED_SAMPLE_INTERVAL_MS = 300L
        private const val SPEED_IDLE_POLL_MS = 150L
    }
}
