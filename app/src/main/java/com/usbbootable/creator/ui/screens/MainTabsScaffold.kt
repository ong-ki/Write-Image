package com.usbbootable.creator.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbbootable.creator.BuildConfig
import com.usbbootable.creator.R
import com.usbbootable.creator.core.image.DetectedOsFamily
import com.usbbootable.creator.core.image.FlashingMode
import com.usbbootable.creator.core.storage.AppearanceCustomization
import com.usbbootable.creator.core.storage.OperationHistoryRecord
import com.usbbootable.creator.core.storage.WriteBufferOption
import com.usbbootable.creator.core.usb.UsbHealthPhase
import com.usbbootable.creator.core.usb.UsbTransportSupport
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.util.LogEntry
import com.usbbootable.creator.core.writer.WindowsBypassOptions
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.ui.AppTab
import com.usbbootable.creator.ui.DashboardUiState
import com.usbbootable.creator.ui.GuidedStep
import com.usbbootable.creator.ui.GuidedStepText
import com.usbbootable.creator.ui.GuidedWorkflowResolver
import com.usbbootable.creator.ui.theme.GlassBackdropBox
import com.usbbootable.creator.ui.theme.GlassCardShape14
import com.usbbootable.creator.ui.theme.GlassCardSurface
import com.usbbootable.creator.ui.theme.GlassMotion
import com.usbbootable.creator.ui.theme.GlassTextWhite
import com.usbbootable.creator.ui.theme.LocalAppColors
import com.usbbootable.creator.ui.theme.LocalNeonTheme
import com.usbbootable.creator.ui.theme.ThemeMode
import com.usbbootable.creator.ui.theme.effectivePrimaryColor
import com.usbbootable.creator.ui.theme.glassBarSurfaceColor
import com.usbbootable.creator.ui.theme.pressScale
import com.usbbootable.creator.ui.theme.rememberMotionEnabled
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabsScaffold(
    state: DashboardUiState,
    logsFlow: StateFlow<List<LogEntry>> = MutableStateFlow(emptyList()),
    onSelectTab: (AppTab) -> Unit,
    onSetFlashingMode: (FlashingMode) -> Unit = {},
    onSetWindowsBypassTpm: (Boolean) -> Unit = {},
    onSetWindowsBypassSecureBoot: (Boolean) -> Unit = {},
    onSetWindowsBypassRam: (Boolean) -> Unit = {},
    onSetWindowsBypassCpu: (Boolean) -> Unit = {},
    onSetWindowsBypassAll: (Boolean) -> Unit = {},
    onSelectIsoClick: () -> Unit,
    onSelectDrive: (Int) -> Unit,
    onRequestUsbPermission: (Int) -> Unit,
    onRetryProbeDrive: (Int) -> Unit,
    onRefreshDrives: () -> Unit,
    onConfirmStartWrite: () -> Unit,
    onCancelWrite: () -> Unit,
    onDismissFinishedStatus: () -> Unit,
    onStartUsbHealthCheck: () -> Unit = {},
    onCancelUsbHealthCheck: () -> Unit = {},
    onDismissUsbHealthCheck: () -> Unit = {},
    onDismissBanner: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onApplyCustomization: (AppearanceCustomization) -> Unit = {},
    onResetCustomization: () -> Unit = {},
    onResetAllSettings: () -> Unit = {},
    onSetBufferOption: (WriteBufferOption) -> Unit,
    onSetVerifyAfterWrite: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onClearLogs: () -> Unit
) {
    var showDrivePicker by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showLogSheet by remember { mutableStateOf(false) }
    var showResetDefaultsDialog by remember { mutableStateOf(false) }

    val appName = stringResource(id = R.string.app_name)
    val topTitle = when (state.selectedTab) {
        AppTab.HOME -> appName
        AppTab.HISTORY -> "History"
        AppTab.SETTINGS -> "Settings"
    }

    val motionEnabled = rememberMotionEnabled()
    val appColors = LocalAppColors.current
    val barSurfaceColor = glassBarSurfaceColor()

    GlassBackdropBox {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = topTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                UsbConnectionStatusDot(connected = state.availableDrives.isNotEmpty())
                            }
                        },
                        actions = {
                            if (state.selectedTab == AppTab.HOME) {
                                IconButton(onClick = onRefreshDrives) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Scan USB drives")
                                }
                            }
                            if (state.selectedTab == AppTab.HISTORY && state.history.isNotEmpty()) {
                                IconButton(onClick = onClearHistory) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear history")
                                }
                            }
                            IconButton(onClick = { showLogSheet = true }) {
                                Icon(Icons.Rounded.Terminal, contentDescription = "Diagnostic log")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = barSurfaceColor,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    HorizontalDivider(color = appColors.borderSubtle, thickness = 1.dp)
                }
            },
            bottomBar = {
                Column {
                    HorizontalDivider(color = appColors.borderSubtle, thickness = 1.dp)
                    NavigationBar(
                        containerColor = barSurfaceColor,
                        tonalElevation = 0.dp
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = state.selectedTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { onSelectTab(tab) },
                                icon = {
                                    Icon(
                                        imageVector = tabIcon(tab),
                                        contentDescription = tab.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = if (appColors.isDark) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    selectedTextColor = appColors.textPrimary,
                                    indicatorColor = if (appColors.isDark) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    unselectedIconColor = appColors.textSecondary,
                                    unselectedTextColor = appColors.textSecondary
                                ),
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AnimatedVisibility(visible = state.bannerMessage != null) {
                    state.bannerMessage?.let { msg ->
                        Surface(
                            color = if (appColors.isDark) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Usb,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = onDismissBanner,
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = state.selectedTab,
                        transitionSpec = {
                            if (!motionEnabled) {
                                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            } else {
                                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                (fadeIn(tween(GlassMotion.TAB_DURATION_MS, easing = FastOutSlowInEasing)) +
                                    slideInHorizontally(tween(GlassMotion.TAB_DURATION_MS, easing = FastOutSlowInEasing)) {
                                        (it * 0.08f * direction).toInt()
                                    }) togetherWith
                                    (fadeOut(tween(GlassMotion.TAB_DURATION_MS, easing = FastOutSlowInEasing)) +
                                        slideOutHorizontally(tween(GlassMotion.TAB_DURATION_MS, easing = FastOutSlowInEasing)) {
                                            (-it * 0.08f * direction).toInt()
                                        })
                            }
                        },
                        label = "tab_content_transition"
                    ) { tab ->
                        when (tab) {
                            AppTab.HOME -> HomeTabContent(
                                state = state,
                                onSetFlashingMode = onSetFlashingMode,
                                onSetWindowsBypassTpm = onSetWindowsBypassTpm,
                                onSetWindowsBypassSecureBoot = onSetWindowsBypassSecureBoot,
                                onSetWindowsBypassRam = onSetWindowsBypassRam,
                                onSetWindowsBypassCpu = onSetWindowsBypassCpu,
                                onSetWindowsBypassAll = onSetWindowsBypassAll,
                                onSelectIsoClick = onSelectIsoClick,
                                onOpenDrivePicker = {
                                    onRefreshDrives()
                                    showDrivePicker = true
                                },
                                onRequestUsbPermission = onRequestUsbPermission,
                                onRetryProbeDrive = onRetryProbeDrive,
                                onRequestCreateConfirm = {
                                    if (!showConfirmDialog &&
                                        !state.writeProgress.phase.isActive &&
                                        !state.operationTimerRunning
                                    ) {
                                        showConfirmDialog = true
                                    }
                                },
                                onStartUsbHealthCheck = onStartUsbHealthCheck,
                                onOpenHistoryTab = { onSelectTab(AppTab.HISTORY) },
                                onCancelWrite = onCancelWrite,
                                onDismissFinishedStatus = onDismissFinishedStatus
                            )

                            AppTab.HISTORY -> HistoryTabContent(
                                history = state.history
                            )

                            AppTab.SETTINGS -> SettingsTabContent(
                                state = state,
                                logsFlow = logsFlow,
                                onSetThemeMode = onSetThemeMode,
                                onApplyCustomization = onApplyCustomization,
                                onResetCustomization = onResetCustomization,
                                onResetAllSettings = {
                                    showResetDefaultsDialog = true
                                },
                                onSetBufferOption = onSetBufferOption,
                                onSetVerifyAfterWrite = onSetVerifyAfterWrite,
                                onOpenLogs = { showLogSheet = true },
                                onOpenTechnicalGuide = { showGuideDialog = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDrivePicker) {
        UsbDrivePickerSheet(
            drives = state.availableDrives,
            selectedDriveId = state.selectedDrive?.deviceId,
            isUsbHostSupported = state.isUsbHostSupported,
            onSelectDrive = onSelectDrive,
            onRequestPermission = onRequestUsbPermission,
            onRetryProbe = onRetryProbeDrive,
            onRefresh = onRefreshDrives,
            onDismiss = { showDrivePicker = false }
        )
    }

    if (showConfirmDialog) {
        val iso = state.selectedIso
        val drive = state.selectedDrive
        if (iso != null && drive != null) {
            ConfirmEraseDialog(
                iso = iso,
                drive = drive,
                requiresNonBootableAck = state.preflight.requiresNonBootableAck,
                onConfirm = {
                    showConfirmDialog = false
                    onConfirmStartWrite()
                },
                onDismiss = { showConfirmDialog = false }
            )
        } else {
            showConfirmDialog = false
        }
    }

    if (showGuideDialog) {
        TechnicalGuideDialog(onDismiss = { showGuideDialog = false })
    }

    if (showResetDefaultsDialog) {
        ConfirmResetDefaultsDialog(
            onConfirm = {
                showResetDefaultsDialog = false
                onResetAllSettings()
            },
            onDismiss = { showResetDefaultsDialog = false }
        )
    }

    if (showLogSheet) {
        val logs by logsFlow.collectAsStateWithLifecycle()
        OperationLogSheet(
            logs = logs,
            onClear = onClearLogs,
            onDismiss = { showLogSheet = false }
        )
    }

    if (state.usbHealthProgress.phase != UsbHealthPhase.IDLE) {
        UsbHealthCheckDialog(
            progress = state.usbHealthProgress,
            onCancel = onCancelUsbHealthCheck,
            onRerun = onStartUsbHealthCheck,
            onDismiss = onDismissUsbHealthCheck
        )
    }
}

private fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.HOME -> Icons.Rounded.Home
    AppTab.HISTORY -> Icons.Rounded.History
    AppTab.SETTINGS -> Icons.Rounded.Settings
}

/**
 * Clean, unified Home screen integrating ISO selection, USB device selection & permission/probing,
 * bootable USB creation, live writing/verification telemetry, and recent operations.
 */
@Composable
private fun HomeTabContent(
    state: DashboardUiState,
    onSetFlashingMode: (FlashingMode) -> Unit,
    onSetWindowsBypassTpm: (Boolean) -> Unit,
    onSetWindowsBypassSecureBoot: (Boolean) -> Unit,
    onSetWindowsBypassRam: (Boolean) -> Unit,
    onSetWindowsBypassCpu: (Boolean) -> Unit,
    onSetWindowsBypassAll: (Boolean) -> Unit,
    onSelectIsoClick: () -> Unit,
    onOpenDrivePicker: () -> Unit,
    onRequestUsbPermission: (Int) -> Unit,
    onRetryProbeDrive: (Int) -> Unit,
    onRequestCreateConfirm: () -> Unit,
    onStartUsbHealthCheck: () -> Unit,
    onOpenHistoryTab: () -> Unit,
    onCancelWrite: () -> Unit,
    onDismissFinishedStatus: () -> Unit
) {
    val isWriting = state.writeProgress.phase.isActive
    val isStartingWrite = state.operationTimerRunning && state.writeProgress.phase == WritePhase.IDLE
    val isWriteInFlight = isWriting || state.operationTimerRunning
    val connectedCount = state.availableDrives.size
    val selectedDrive = state.selectedDrive
    val selectedIso = state.selectedIso

    // Centralized guidance: every input is an existing, real application state value.
    val driveReady = selectedDrive != null &&
        selectedDrive.hasPermission &&
        selectedDrive.transportSupport == UsbTransportSupport.SUPPORTED_BOT &&
        selectedDrive.isProbed &&
        !selectedDrive.isWriteProtected
    val isoReady = selectedIso != null &&
        selectedIso.isAccessible &&
        selectedIso.sizeBytes > 0L &&
        !state.isAnalyzingIso &&
        state.isoError == null
    val modeCompatible = GuidedWorkflowResolver.isFlashingModeCompatible(
        mode = state.flashingMode,
        isoFamily = selectedIso?.detectedOsFamily
    )
    val modeReady = state.flashingModeSelected && modeCompatible
    val canCreate = state.preflight.canProceed &&
        state.flashingModeSelected &&
        !isWriteInFlight &&
        !state.isAnalyzingIso &&
        !state.usbHealthProgress.phase.isActive
    val driveHasError = selectedDrive?.let {
        !it.hasPermission || it.probeError != null || it.isWriteProtected ||
            it.transportSupport != UsbTransportSupport.SUPPORTED_BOT
    } == true
    // Handle the short gap between the user confirming Create and the service's first progress.
    val guidancePhase = if (isStartingWrite) {
        WritePhase.PREPARING
    } else {
        state.writeProgress.phase
    }
    val guided = GuidedWorkflowResolver.resolve(
        writePhase = guidancePhase,
        driveReady = driveReady,
        isoReady = isoReady,
        modeReady = modeReady,
        canCreate = canCreate,
        driveHasError = driveHasError,
        isoHasError = state.isoError != null || (selectedIso != null && !selectedIso.isAccessible),
        modeHasError = state.flashingModeSelected && !modeCompatible
    )
    val appColors = LocalAppColors.current
    val neon = LocalNeonTheme.current
    val guideColor = when {
        guided.currentStepHasError -> MaterialTheme.colorScheme.error
        appColors.isDark -> lerp(neon.effectivePrimaryColor, GlassTextWhite, 0.20f)
        else -> MaterialTheme.colorScheme.primary
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(isWriteInFlight) {
        if (isWriteInFlight && scrollState.value > 0) {
            scrollState.animateScrollTo(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Live Transfer Speed & Progress Card at the top of Home
        ActiveOperationCard(
            progress = state.writeProgress,
            speedSnapshot = state.speedSnapshot,
            elapsedSeconds = state.elapsedSeconds,
            fallbackTotalBytes = state.selectedIso?.sizeBytes ?: 0L,
            isReadyToStart = canCreate,
            isStarting = isStartingWrite,
            onCancel = onCancelWrite,
            onDismissFinished = onDismissFinishedStatus
        )

        // 1. ISO Image Card — static card; only the "ISO Image" label blinks when guided
        HomeSelectionCard {
            HomeStatusRow(
                completed = guided.isCompleted(GuidedStep.ISO_IMAGE),
                titleGuideActive = guided.isCurrent(GuidedStep.ISO_IMAGE),
                titleGuideColor = guideColor,
                icon = Icons.Rounded.Album,
                title = "ISO Image",
                value = when {
                    state.isAnalyzingIso -> "Inspecting image..."
                    state.selectedIso != null -> state.selectedIso.displayName
                    else -> "Not selected"
                },
                subtitle = when {
                    state.isoError != null -> state.isoError
                    state.selectedIso != null -> "${ByteUtils.formatBytes(state.selectedIso.sizeBytes)} • ${state.selectedIso.bootSupport.headline}"
                    else -> "Select a Windows or Linux ISO"
                },
                isError = state.isoError != null,
                ready = state.selectedIso?.isAccessible == true,
                isLoading = state.isAnalyzingIso,
                actionLabel = if (state.selectedIso == null) "Select" else "Change",
                enabled = !isWriteInFlight,
                onAction = onSelectIsoClick
            )

            // Warnings for selected ISO (e.g. >4 GiB dual partition or non-hybrid optical notice)
            state.selectedIso?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    warnings.forEach { warn ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = statusWarningColor(),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = warn,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 2. USB Drive Card — identical static card; only the "USB Drive" label blinks when guided
        val drive = state.selectedDrive
        val driveNeedsPermission = drive != null &&
            drive.transportSupport == UsbTransportSupport.SUPPORTED_BOT &&
            !drive.hasPermission
        val driveHasProbeError = drive != null && drive.probeError != null

        HomeSelectionCard {
            HomeStatusRow(
                completed = guided.isCompleted(GuidedStep.USB_DRIVE),
                titleGuideActive = guided.isCurrent(GuidedStep.USB_DRIVE),
                titleGuideColor = guideColor,
                icon = Icons.Rounded.Usb,
                title = "USB Drive",
                value = drive?.displayName ?: "Not selected",
                subtitle = when {
                    drive == null && connectedCount == 0 -> "Connect a flash drive via OTG"
                    drive == null -> "$connectedCount drive(s) detected — tap Select"
                    drive.transportSupport == UsbTransportSupport.UNSUPPORTED_UASP_ONLY ->
                        "Unsupported UASP-only device (requires BOT 0x50)"
                    !drive.hasPermission -> "USB permission required — tap Grant"
                    drive.probeError != null -> drive.probeError
                    drive.isWriteProtected -> "Write-protected (read-only)"
                    drive.isProbed -> "${ByteUtils.formatDecimalCapacity(drive.capacityBytes)} • VID:PID ${drive.vidPidHex}"
                    else -> "Probing SCSI geometry & capacity..."
                },
                isError = driveHasProbeError || drive?.isWriteProtected == true ||
                    drive?.transportSupport == UsbTransportSupport.UNSUPPORTED_UASP_ONLY,
                ready = drive?.isProbed == true && !drive.isWriteProtected,
                isLoading = drive != null && drive.hasPermission && !drive.isProbed && drive.probeError == null,
                actionLabel = when {
                    driveNeedsPermission -> "Grant"
                    driveHasProbeError -> "Retry"
                    drive == null -> "Select"
                    else -> "Change"
                },
                enabled = !isWriteInFlight,
                onAction = {
                    when {
                        driveNeedsPermission -> onRequestUsbPermission(drive.deviceId)
                        driveHasProbeError -> onRetryProbeDrive(drive.deviceId)
                        else -> onOpenDrivePicker()
                    }
                }
            )

            // Compact "CHECK USB HEALTH" button shown after a supported USB drive is selected
            if (drive != null && drive.transportSupport == UsbTransportSupport.SUPPORTED_BOT && drive.hasPermission && drive.isProbed) {
                val isBusy = isWriteInFlight || state.usbHealthProgress.phase.isActive
                val healthInteraction = remember { MutableInteractionSource() }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onStartUsbHealthCheck,
                    enabled = !isBusy,
                    interactionSource = healthInteraction,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .pressScale(healthInteraction, enabled = !isBusy)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HealthAndSafety,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CHECK USB HEALTH",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 3. Flashing Mode — static card directly above Create Bootable USB; only "Flashing Mode" text blinks
        GlassCardSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = GlassCardShape14
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GuidedStepText(
                            text = "Flashing Mode",
                            style = MaterialTheme.typography.labelMedium,
                            defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            guideActive = guided.isCurrent(GuidedStep.FLASHING_MODE),
                            guideColor = guideColor
                        )
                        if (guided.isCompleted(GuidedStep.FLASHING_MODE)) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Flashing mode selected",
                                tint = statusSuccessColor(),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    state.selectedIso?.let { iso ->
                        val detectedBadge = when (iso.detectedOsFamily) {
                            DetectedOsFamily.WINDOWS -> "Windows 10/11"
                            DetectedOsFamily.LINUX -> "Linux"
                            DetectedOsFamily.RAW_DISK_IMAGE -> "Raw Image"
                            DetectedOsFamily.UNSUPPORTED -> "Non-Hybrid"
                        }
                        val badgeColor = when (iso.detectedOsFamily) {
                            DetectedOsFamily.WINDOWS,
                            DetectedOsFamily.LINUX,
                            DetectedOsFamily.RAW_DISK_IMAGE -> statusSuccessColor()
                            DetectedOsFamily.UNSUPPORTED -> statusWarningColor()
                        }
                        StatusPill(text = detectedBadge, color = badgeColor)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FlashingMode.entries.forEach { mode ->
                        val selected = state.flashingMode == mode
                        OutlinedButton(
                            onClick = { onSetFlashingMode(mode) },
                            enabled = !isWriteInFlight,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    appColors.borderSubtle
                                }
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) {
                                    if (appColors.isDark) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    }
                                } else {
                                    appColors.surfaceInner
                                },
                                contentColor = if (selected) {
                                    if (appColors.isDark) GlassTextWhite else MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    appColors.textSecondary
                                },
                                disabledContainerColor = appColors.surfaceInner,
                                disabledContentColor = appColors.textDisabled
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                        ) {
                            Text(
                                text = mode.shortLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // Contextual Windows Requirements Bypass (only when Windows mode or Windows ISO in Auto mode)
        val showWindowsBypass = state.flashingMode == FlashingMode.WINDOWS ||
            (state.flashingMode == FlashingMode.AUTO && state.selectedIso?.detectedOsFamily == DetectedOsFamily.WINDOWS)
        AnimatedVisibility(visible = showWindowsBypass) {
            WindowsBypassSection(
                options = state.settings.windowsBypassOptions,
                enabled = !isWriteInFlight,
                onToggleTpm = onSetWindowsBypassTpm,
                onToggleSecureBoot = onSetWindowsBypassSecureBoot,
                onToggleRam = onSetWindowsBypassRam,
                onToggleCpu = onSetWindowsBypassCpu,
                onToggleAll = onSetWindowsBypassAll
            )
        }

        // 4. Primary Action: CREATE BOOTABLE USB
        MainActionsSection(
            canCreate = canCreate,
            isFlashing = isWriteInFlight,
            guideActive = guided.isCurrent(GuidedStep.CREATE),
            guideError = guided.currentStepHasError,
            onCreateClick = onRequestCreateConfirm
        )

        // Inline Pre-flight Blockers when ISO or USB is selected but not yet ready to flash
        if (!isWriteInFlight && state.preflight.blockers.isNotEmpty() && (state.selectedIso != null || state.selectedDrive != null)) {
            Text(
                text = state.preflight.blockers.first(),
                style = MaterialTheme.typography.bodySmall,
                color = statusWarningColor(),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // 5. Recent Operations — final section on the Home Screen
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Operations",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.history.isNotEmpty()) {
                    TextButton(
                        onClick = onOpenHistoryTab,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("View all", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (state.history.isEmpty()) {
                Text(
                    text = "No completed or cancelled USB writes yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val recentHistory = remember(state.history) { state.history.take(3) }
                GlassCardSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = GlassCardShape14
                ) {
                    Column {
                        recentHistory.forEachIndexed { idx, record ->
                            if (idx > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            }
                            CompactHistoryRow(record)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowsBypassSection(
    options: WindowsBypassOptions,
    enabled: Boolean,
    onToggleTpm: (Boolean) -> Unit,
    onToggleSecureBoot: (Boolean) -> Unit,
    onToggleRam: (Boolean) -> Unit,
    onToggleCpu: (Boolean) -> Unit,
    onToggleAll: (Boolean) -> Unit
) {
    GlassCardSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassCardShape14
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Windows Requirements Bypass",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (options.hasAnyEnabled) {
                    StatusPill(
                        text = if (options.allEnabled) "ALL ACTIVE" else "${options.enabledLabels().size} ACTIVE",
                        color = statusWarningColor()
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            BypassCheckboxRow(
                label = "Enable All Bypasses",
                checked = options.allEnabled,
                enabled = enabled,
                bold = true,
                onCheckedChange = onToggleAll
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                modifier = Modifier.padding(vertical = 2.dp)
            )

            BypassCheckboxRow(
                label = "Bypass TPM 2.0 Check",
                checked = options.bypassTpm,
                enabled = enabled,
                onCheckedChange = onToggleTpm
            )
            BypassCheckboxRow(
                label = "Bypass Secure Boot Check",
                checked = options.bypassSecureBoot,
                enabled = enabled,
                onCheckedChange = onToggleSecureBoot
            )
            BypassCheckboxRow(
                label = "Bypass RAM Requirement",
                checked = options.bypassRam,
                enabled = enabled,
                onCheckedChange = onToggleRam
            )
            BypassCheckboxRow(
                label = "Bypass CPU Compatibility Check",
                checked = options.bypassCpu,
                enabled = enabled,
                onCheckedChange = onToggleCpu
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = statusWarningColor(),
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Bypassing hardware requirements allows installation on unsupported PCs, but does not make unsupported hardware officially supported.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BypassCheckboxRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    bold: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    val appColors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled) {
                appColors.textPrimary
            } else {
                appColors.textDisabled
            }
        )
    }
}

@Composable
private fun HomeSelectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCardSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassCardShape14
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            content = content
        )
    }
}

@Composable
private fun HomeStatusRow(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    isError: Boolean = false,
    ready: Boolean,
    isLoading: Boolean = false,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    completed: Boolean = false,
    titleGuideActive: Boolean = false,
    titleGuideColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    isError -> MaterialTheme.colorScheme.error
                    ready -> statusSuccessColor()
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GuidedStepText(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    guideActive = titleGuideActive,
                    guideColor = titleGuideColor
                )
                if (completed) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "$title selected",
                        tint = statusSuccessColor(),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        TextButton(
            onClick = onAction,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun UsbConnectionStatusDot(connected: Boolean) {
    val motionEnabled = rememberMotionEnabled()
    val targetColor = if (connected) statusSuccessColor() else MaterialTheme.colorScheme.error
    val dotColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(
            durationMillis = if (motionEnabled) GlassMotion.STATUS_DURATION_MS else 0,
            easing = FastOutSlowInEasing
        ),
        label = "usb_status_dot_color"
    )
    val scale by animateFloatAsState(
        targetValue = if (connected) 1.08f else 0.94f,
        animationSpec = tween(
            durationMillis = if (motionEnabled) GlassMotion.STATUS_DURATION_MS else 0,
            easing = FastOutSlowInEasing
        ),
        label = "usb_status_dot_scale"
    )
    val description = if (connected) "USB connected" else "No USB connected"
    Box(
        modifier = Modifier
            .size(10.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(dotColor)
            .semantics { contentDescription = description }
    )
}

@Composable
private fun CompactHistoryRow(record: OperationHistoryRecord) {
    val (statusColor, statusLabel) = historyStatusVisual(record.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.isoName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${record.driveName} • ${ByteUtils.formatSpeed(record.averageSpeedBytesPerSec)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        StatusPill(text = statusLabel, color = statusColor)
    }
}

/**
 * Persisted History Tab showing real completed, failed, and cancelled operations.
 */
@Composable
private fun HistoryTabContent(history: List<OperationHistoryRecord>) {
    if (history.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("No Operation History", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Completed, cancelled, and failed USB writes will be logged here with measured transfer speeds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(history, key = { it.id }) { record ->
            val (color, label) = historyStatusVisual(record.status)
            GlassCardSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassCardShape14
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when (record.status) {
                                    WritePhase.COMPLETED -> Icons.Rounded.CheckCircle
                                    WritePhase.CANCELLED -> Icons.Rounded.Cancel
                                    else -> Icons.Rounded.ErrorOutline
                                },
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusPill(text = label, color = color)
                        }
                        Text(
                            text = dateFormat.format(Date(record.timestampMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${record.isoName} (${ByteUtils.formatBytes(record.isoSizeBytes)})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Target: ${record.driveName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Avg Speed: ${ByteUtils.formatSpeed(record.averageSpeedBytesPerSec)} • " +
                            "Duration: ${ByteUtils.formatDuration(record.durationMs / 1000L)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (record.summaryNote.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = record.summaryNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun historyStatusVisual(phase: WritePhase): Pair<Color, String> = when (phase) {
    WritePhase.COMPLETED -> statusSuccessColor() to "COMPLETED"
    WritePhase.CANCELLED -> statusWarningColor() to "CANCELLED"
    else -> MaterialTheme.colorScheme.error to "FAILED"
}

/**
 * Compact, theme-aware Settings Tab organizing Appearance, Progress Bar Styles, Neon Customization,
 * USB Flashing & Integrity, Diagnostics, and Restore Defaults with minimal scrolling.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsTabContent(
    state: DashboardUiState,
    logsFlow: StateFlow<List<LogEntry>>,
    onSetThemeMode: (ThemeMode) -> Unit,
    onApplyCustomization: (AppearanceCustomization) -> Unit,
    onResetCustomization: () -> Unit,
    onResetAllSettings: () -> Unit,
    onSetBufferOption: (WriteBufferOption) -> Unit,
    onSetVerifyAfterWrite: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTechnicalGuide: () -> Unit
) {
    val isWriting = state.writeProgress.phase.isActive
    val appColors = LocalAppColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Appearance — compact full-width 3-button segmented selector
        SettingsSection(title = "Appearance") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    val selected = state.settings.themeMode == mode
                    OutlinedButton(
                        onClick = { onSetThemeMode(mode) },
                        shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else appColors.borderSubtle
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) {
                                if (appColors.isDark) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            } else {
                                appColors.surfaceInner
                            },
                            contentColor = if (selected) {
                                if (appColors.isDark) GlassTextWhite else MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                appColors.textSecondary
                            }
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                    ) {
                        Text(
                            text = when (mode) {
                                ThemeMode.SYSTEM -> "System"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 2 & 3. Progress Bar Style (4 distinct styles) + Neon Customization
        CustomizationSettingsContent(
            savedCustomization = state.customization,
            onApplyCustomization = onApplyCustomization,
            onResetDefaults = onResetCustomization
        )

        // 4. USB Flashing & Integrity (Buffer Size + Post-Write SHA-256 Verification)
        SettingsSection(title = "USB Flashing & Integrity") {
            Text(
                text = "I/O Buffer Size",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Auto picks optimal chunk size (256/128/64 KiB) for your USB port.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WriteBufferOption.entries.forEach { option ->
                    val selected = state.settings.bufferOption == option
                    FilterChip(
                        selected = selected,
                        enabled = !isWriting,
                        onClick = { onSetBufferOption(option) },
                        label = {
                            Text(
                                text = if (option == WriteBufferOption.AUTO) "Auto" else option.label,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }

            HorizontalDivider(
                color = appColors.borderSubtle,
                modifier = Modifier.padding(vertical = 6.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isWriting) {
                        onSetVerifyAfterWrite(!state.settings.verifyAfterWrite)
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Verify SHA-256 After Writing",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Read back written sectors over SCSI to verify bit-exact integrity.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Switch(
                    checked = state.settings.verifyAfterWrite,
                    enabled = !isWriting,
                    onCheckedChange = onSetVerifyAfterWrite
                )
            }
        }

        // 5. Diagnostics & Help
        SettingsSection(title = "Diagnostics & Help") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenLogs)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "USB / SCSI Diagnostic Log",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    DiagnosticLogCountText(logsFlow = logsFlow)
                }
            }
            HorizontalDivider(color = appColors.borderSubtle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenTechnicalGuide)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Technical & Compatibility Guide",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "USB Host API, root notes, Windows & Linux boot details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 6. Restore Defaults — confirmation handled by the caller
        SettingsSection(title = "Restore Defaults") {
            Text(
                text = "Reset all preferences to defaults. ISO files, USB data, and history are kept.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onResetAllSettings,
                enabled = !isWriting,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isWriting) appColors.borderSubtle else MaterialTheme.colorScheme.primary
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isWriting) appColors.textDisabled else MaterialTheme.colorScheme.primary,
                    disabledContentColor = appColors.textDisabled
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Restore,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "RESTORE DEFAULTS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Compact Security Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = null,
                tint = statusSuccessColor(),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${stringResource(id = R.string.app_name)} v${BuildConfig.VERSION_NAME} • Internal storage is never exposed or writable.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagnosticLogCountText(logsFlow: StateFlow<List<LogEntry>>) {
    val logs by logsFlow.collectAsStateWithLifecycle()
    Text(
        text = "${logs.size} event${if (logs.size == 1) "" else "s"} captured",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun SettingsSection(
    title: String,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            headerAction?.invoke()
        }
        GlassCardSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = GlassCardShape14
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                content = content
            )
        }
    }
}
