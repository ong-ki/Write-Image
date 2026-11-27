package com.usbbootable.creator.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Eject
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.usbbootable.creator.service.UsbEjectPhase
import com.usbbootable.creator.service.UsbEjectState
import com.usbbootable.creator.ui.AppTab
import com.usbbootable.creator.ui.DashboardUiState
import com.usbbootable.creator.ui.GuidedStep
import com.usbbootable.creator.ui.GuidedStepText
import com.usbbootable.creator.ui.GuidedWorkflowResolver
import com.usbbootable.creator.ui.theme.GlassBackdropBox
import com.usbbootable.creator.ui.theme.GlassCardShape16
import com.usbbootable.creator.ui.theme.GlassDialogShape
import com.usbbootable.creator.ui.theme.GlassMotion
import com.usbbootable.creator.ui.theme.GlassTextWhite
import com.usbbootable.creator.ui.theme.LocalAppColors
import com.usbbootable.creator.ui.theme.LocalNeonTheme
import com.usbbootable.creator.ui.theme.ThemeMode
import com.usbbootable.creator.ui.theme.effectivePrimaryColor
import com.usbbootable.creator.ui.theme.glassBarSurfaceColor
import com.usbbootable.creator.ui.theme.glassDialogContainerColor
import com.usbbootable.creator.ui.theme.glassDialogEnter
import com.usbbootable.creator.ui.theme.rememberMotionEnabled
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor
import kotlinx.coroutines.delay
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
    onEjectUsb: () -> Unit = {},
    onRetryEject: () -> Unit = {},
    onDismissEjectBanner: () -> Unit = {},
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
    onSetAutoEjectAfterWrite: (Boolean) -> Unit = {},
    onClearHistory: () -> Unit,
    onClearLogs: () -> Unit
) {
    var showDrivePicker by rememberSaveable { mutableStateOf(false) }
    var showConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showGuideDialog by rememberSaveable { mutableStateOf(false) }
    var showLogSheet by rememberSaveable { mutableStateOf(false) }
    var showResetDefaultsDialog by rememberSaveable { mutableStateOf(false) }
    var showEjectDetailsDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.ejectState.phase) {
        if (state.ejectState.phase == UsbEjectPhase.IDLE || state.ejectState.phase == UsbEjectPhase.EJECTING) {
            showEjectDetailsDialog = false
        }
    }

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
                    AppDivider()
                }
            },
            bottomBar = {
                Column {
                    AppDivider()
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
                AnimatedVisibility(visible = state.ejectState.phase != UsbEjectPhase.IDLE) {
                    EjectStatusBanner(
                        ejectState = state.ejectState,
                        isWriteActive = state.writeProgress.phase.isActive || state.operationTimerRunning,
                        onShowDetails = { showEjectDetailsDialog = true },
                        onRetry = onRetryEject,
                        onDismiss = onDismissEjectBanner
                    )
                }

                AnimatedVisibility(visible = state.bannerMessage != null) {
                    state.bannerMessage?.let { msg ->
                        AppBanner(
                            message = msg,
                            tone = BannerTone.INFO,
                            icon = Icons.Rounded.Usb,
                            rounded = false,
                            onDismiss = onDismissBanner
                        )
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
                                onEjectUsb = onEjectUsb,
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
                                onSetAutoEjectAfterWrite = onSetAutoEjectAfterWrite,
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

    if (showEjectDetailsDialog && state.ejectState.phase != UsbEjectPhase.IDLE) {
        val logs by logsFlow.collectAsStateWithLifecycle()
        EjectDetailsDialog(
            ejectState = state.ejectState,
            logs = logs,
            onDismiss = { showEjectDetailsDialog = false }
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

@Composable
private fun EjectStatusBanner(
    ejectState: UsbEjectState,
    isWriteActive: Boolean,
    onShowDetails: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val tone = when (ejectState.phase) {
        UsbEjectPhase.SUCCEEDED -> BannerTone.SUCCESS
        UsbEjectPhase.FAILED -> BannerTone.ERROR
        UsbEjectPhase.COMPLETED_WARNING,
        UsbEjectPhase.DISCONNECTED_WARNING -> BannerTone.WARNING
        UsbEjectPhase.EJECTING, UsbEjectPhase.IDLE -> BannerTone.INFO
    }

    val iconContentDescription = when (ejectState.phase) {
        UsbEjectPhase.SUCCEEDED -> stringResource(R.string.cd_eject_status_success)
        UsbEjectPhase.FAILED -> stringResource(R.string.cd_eject_status_error)
        UsbEjectPhase.COMPLETED_WARNING,
        UsbEjectPhase.DISCONNECTED_WARNING -> stringResource(R.string.cd_eject_status_warning)
        UsbEjectPhase.EJECTING, UsbEjectPhase.IDLE -> null
    }

    val genericFailedMessage = stringResource(R.string.eject_banner_failed_generic)
    val messageText = when (ejectState.phase) {
        UsbEjectPhase.EJECTING -> stringResource(
            R.string.eject_banner_progress,
            ejectState.targetDeviceName.ifBlank { "USB" }
        )
        UsbEjectPhase.SUCCEEDED -> stringResource(R.string.eject_banner_safe_to_remove)
        UsbEjectPhase.COMPLETED_WARNING -> ejectState.errorMessage?.takeIf {
            it.isNotBlank() && !looksTechnicalMessage(it)
        } ?: stringResource(R.string.eject_banner_all_commands_failed_warning)
        UsbEjectPhase.FAILED -> ejectState.errorMessage?.takeIf {
            it.isNotBlank() && !looksTechnicalMessage(it)
        } ?: genericFailedMessage
        UsbEjectPhase.DISCONNECTED_WARNING -> ejectState.errorMessage?.takeIf {
            it.isNotBlank() && !looksTechnicalMessage(it)
        } ?: stringResource(R.string.eject_banner_unplugged_warning)
        UsbEjectPhase.IDLE -> ""
    }

    val showDetailsAction = ejectState.phase == UsbEjectPhase.FAILED ||
        ejectState.phase == UsbEjectPhase.COMPLETED_WARNING ||
        ejectState.phase == UsbEjectPhase.DISCONNECTED_WARNING ||
        !ejectState.detailLog.isNullOrBlank()

    AppBanner(
        message = messageText,
        tone = tone,
        iconContentDescription = iconContentDescription,
        isLoading = ejectState.phase == UsbEjectPhase.EJECTING,
        rounded = false,
        fontWeight = if (ejectState.phase == UsbEjectPhase.SUCCEEDED) FontWeight.SemiBold else FontWeight.Medium,
        footerActions = if (ejectState.phase != UsbEjectPhase.EJECTING) {
            { palette ->
                if (showDetailsAction) {
                    TextButton(
                        onClick = onShowDetails,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 36.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.eject_action_details),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.content
                        )
                    }
                }
                if (ejectState.phase == UsbEjectPhase.FAILED) {
                    TextButton(
                        onClick = onRetry,
                        enabled = !isWriteActive && !ejectState.isEjecting,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 36.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.eject_action_retry),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.icon
                        )
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .semantics {
                            contentDescription = "Close"
                        }
                ) {
                    Text(
                        text = stringResource(R.string.eject_action_close),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.content
                    )
                }
            }
        } else {
            null
        }
    )
}

private fun looksTechnicalMessage(message: String): Boolean {
    return message.contains(" | ") ||
        message.contains("CSW", ignoreCase = false) ||
        message.contains("SYNCHRONIZE_CACHE", ignoreCase = true) ||
        message.contains("TEST_UNIT_READY", ignoreCase = true) ||
        message.contains("START_STOP_UNIT", ignoreCase = true) ||
        message.contains("Endpoints:", ignoreCase = true) ||
        message.contains("UsbBot", ignoreCase = true) ||
        message.contains("Exception", ignoreCase = false)
}

@Composable
private fun EjectDetailsDialog(
    ejectState: UsbEjectState,
    logs: List<LogEntry>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000L)
            copied = false
        }
    }

    val fullLogText = remember(logs, ejectState.detailLog, ejectState.errorMessage) {
        buildString {
            val detail = ejectState.detailLog?.takeIf { it.isNotBlank() }
            if (detail != null) {
                appendLine("=== Eject Diagnostics ===")
                appendLine(detail.trimEnd())
            }
            if (logs.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("=== Full Diagnostic Log (${logs.size} events) ===")
                logs.forEach { entry ->
                    appendLine("[${entry.timestamp}] [${entry.tag}] ${entry.message}")
                }
            }
            if (isEmpty()) {
                append(ejectState.errorMessage ?: "No diagnostic log entries recorded.")
            }
        }.trimEnd()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = GlassDialogShape,
        containerColor = glassDialogContainerColor(),
        modifier = Modifier.glassDialogEnter(),
        title = {
            Text(
                text = stringResource(R.string.eject_details_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (ejectState.targetDeviceName.isNotBlank()) {
                    Text(
                        text = ejectState.targetDeviceName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    color = appColors.surfaceInner,
                    shape = HomeControlShape,
                    border = BorderStroke(1.dp, appColors.borderSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 360.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = fullLogText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = appColors.textPrimary,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("USB Eject Log", fullLogText))
                    copied = true
                },
                shape = HomeControlShape,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (copied) R.string.eject_details_copied else R.string.eject_details_copy
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.eject_action_close),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}

private fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.HOME -> Icons.Rounded.Home
    AppTab.HISTORY -> Icons.Rounded.History
    AppTab.SETTINGS -> Icons.Rounded.Settings
}

private enum class HomeMode { FORM, PROGRESS, RESULT }

/**
 * Home screen as a three-step flow (1. image, 2. USB drive, 3. write). While a write is in
 * flight it is replaced by the progress screen, and afterwards by a success / failed / cancelled
 * result screen. All state and callbacks are the existing ones; this is layout only.
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
    onEjectUsb: () -> Unit,
    onOpenHistoryTab: () -> Unit,
    onCancelWrite: () -> Unit,
    onDismissFinishedStatus: () -> Unit
) {
    val isWriting = state.writeProgress.phase.isActive
    val isStartingWrite = state.operationTimerRunning && state.writeProgress.phase == WritePhase.IDLE
    val isWriteInFlight = isWriting || state.operationTimerRunning
    val isEjecting = state.ejectState.isEjecting
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
        !isEjecting &&
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

    val phase = state.writeProgress.phase
    val mode = when {
        isWriteInFlight -> HomeMode.PROGRESS
        phase == WritePhase.COMPLETED || phase == WritePhase.FAILED || phase == WritePhase.CANCELLED ->
            HomeMode.RESULT
        else -> HomeMode.FORM
    }
    var showCancelConfirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(mode) {
        if (mode != HomeMode.PROGRESS) showCancelConfirm = false
    }
    val motionEnabled = rememberMotionEnabled()

    val isoName = state.writeProgress.sourceIsoName.ifBlank { selectedIso?.displayName.orEmpty() }
    val driveName = state.writeProgress.targetDeviceName.ifBlank { selectedDrive?.displayName ?: "USB drive" }

    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            if (!motionEnabled) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (fadeIn(tween(220, delayMillis = 60)) +
                    scaleIn(initialScale = 0.98f, animationSpec = tween(220, delayMillis = 60))) togetherWith
                    fadeOut(tween(90))
            }
        },
        label = "home_mode_transition"
    ) { currentMode ->
        when (currentMode) {
            HomeMode.PROGRESS -> ScreenColumn {
                WriteProgressPanel(
                    progress = state.writeProgress,
                    speedSnapshot = state.speedSnapshot,
                    elapsedSeconds = state.elapsedSeconds,
                    fallbackTotalBytes = selectedIso?.sizeBytes ?: 0L,
                    isoName = isoName,
                    driveName = driveName,
                    onCancel = { showCancelConfirm = true }
                )
            }

            HomeMode.RESULT -> ScreenColumn {
                WriteResultPanel(
                    progress = state.writeProgress,
                    elapsedSeconds = state.elapsedSeconds,
                    isoName = isoName,
                    driveName = driveName,
                    canRetry = state.preflight.canProceed &&
                        state.flashingModeSelected &&
                        !state.isAnalyzingIso &&
                        !isEjecting,
                    ejectState = state.ejectState,
                    hasConnectedDrive = selectedDrive != null,
                    isWriteActive = isWriteInFlight,
                    onEjectUsb = onEjectUsb,
                    onDone = onDismissFinishedStatus,
                    onRetry = {
                        onDismissFinishedStatus()
                        onRequestCreateConfirm()
                    }
                )
            }

            HomeMode.FORM -> ScreenColumn {
                // Step 1 — image
                StepCard(
                    step = 1,
                    icon = Icons.Rounded.Album,
                    title = "ISO Image",
                    value = when {
                        state.isAnalyzingIso -> "Inspecting image..."
                        selectedIso != null -> selectedIso.displayName
                        else -> "No image selected"
                    },
                    subtitle = when {
                        state.isoError != null -> state.isoError
                        selectedIso != null ->
                            "${ByteUtils.formatBytes(selectedIso.sizeBytes)} • ${selectedIso.bootSupport.headline}"
                        else -> "Choose a Windows or Linux .iso / .img file"
                    },
                    isError = state.isoError != null,
                    ready = selectedIso?.isAccessible == true,
                    isLoading = state.isAnalyzingIso,
                    empty = selectedIso == null && !state.isAnalyzingIso,
                    completed = isoReady,
                    emphasizeAction = selectedIso == null || state.isoError != null,
                    actionLabel = if (selectedIso == null) "Select image" else "Change image",
                    enabled = !isWriteInFlight && !isEjecting,
                    onAction = onSelectIsoClick,
                    titleGuideActive = guided.isCurrent(GuidedStep.ISO_IMAGE),
                    titleGuideColor = guideColor
                ) {
                    // Warnings for the selected image (e.g. > 4 GiB dual partition or non-hybrid notice)
                    selectedIso?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            warnings.forEach { warn ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = "Warning",
                                        tint = statusWarningColor(),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
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

                // Step 2 — USB drive
                val drive = selectedDrive
                val driveNeedsPermission = drive != null &&
                    drive.transportSupport == UsbTransportSupport.SUPPORTED_BOT &&
                    !drive.hasPermission
                val driveHasProbeError = drive != null && drive.probeError != null

                StepCard(
                    step = 2,
                    icon = Icons.Rounded.Usb,
                    title = "USB Drive",
                    value = drive?.displayName ?: "No USB drive selected",
                    subtitle = when {
                        drive == null && connectedCount == 0 -> "Connect a flash drive via an OTG adapter"
                        drive == null -> "$connectedCount drive(s) detected — tap Select drive"
                        drive.transportSupport == UsbTransportSupport.UNSUPPORTED_UASP_ONLY ->
                            "Unsupported UASP-only device (requires BOT 0x50)"
                        !drive.hasPermission -> "USB permission required — tap Grant access"
                        drive.probeError != null -> drive.probeError
                        drive.isWriteProtected -> "Write-protected (read-only)"
                        drive.isProbed ->
                            "${ByteUtils.formatDecimalCapacity(drive.capacityBytes)} • VID:PID ${drive.vidPidHex}"
                        else -> "Probing SCSI geometry & capacity..."
                    },
                    isError = driveHasProbeError || drive?.isWriteProtected == true ||
                        drive?.transportSupport == UsbTransportSupport.UNSUPPORTED_UASP_ONLY,
                    ready = drive?.isProbed == true && !drive.isWriteProtected,
                    isLoading = drive != null && drive.hasPermission && !drive.isProbed && drive.probeError == null,
                    empty = drive == null,
                    completed = driveReady,
                    emphasizeAction = drive == null || driveNeedsPermission || driveHasProbeError,
                    actionLabel = when {
                        driveNeedsPermission -> "Grant access"
                        driveHasProbeError -> "Retry"
                        drive == null -> "Select drive"
                        else -> "Change drive"
                    },
                    enabled = !isWriteInFlight && !isEjecting,
                    onAction = {
                        when {
                            driveNeedsPermission -> onRequestUsbPermission(drive.deviceId)
                            driveHasProbeError -> onRetryProbeDrive(drive.deviceId)
                            else -> onOpenDrivePicker()
                        }
                    },
                    titleGuideActive = guided.isCurrent(GuidedStep.USB_DRIVE),
                    titleGuideColor = guideColor
                ) {
                    // Non-destructive health check once a supported drive is ready
                    if (drive != null && drive.transportSupport == UsbTransportSupport.SUPPORTED_BOT &&
                        drive.hasPermission && drive.isProbed
                    ) {
                        val isBusy = isWriteInFlight || isEjecting || state.usbHealthProgress.phase.isActive
                        AppOutlineButton(
                            text = "CHECK USB HEALTH",
                            onClick = onStartUsbHealthCheck,
                            enabled = !isBusy,
                            icon = Icons.Rounded.HealthAndSafety,
                            compactTextStyle = true
                        )
                    }

                    if (drive != null || isEjecting) {
                        val canTapEject = !isWriteInFlight && !isEjecting && !state.usbHealthProgress.phase.isActive
                        AppOutlineButton(
                            text = stringResource(R.string.action_eject_usb),
                            onClick = onEjectUsb,
                            enabled = canTapEject,
                            isLoading = isEjecting,
                            loadingText = stringResource(R.string.eject_status_ejecting),
                            icon = Icons.Rounded.Eject,
                            iconContentDescription = stringResource(R.string.cd_eject_usb),
                            compactTextStyle = true
                        )
                    }
                }

                // Step 3 — write (flashing mode + primary action)
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StepBadge(step = 3, completed = false, isError = false)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Write",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() }
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GuidedStepText(
                                    text = "Flashing Mode",
                                    style = MaterialTheme.typography.labelMedium,
                                    defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    guideActive = guided.isCurrent(GuidedStep.FLASHING_MODE),
                                    guideColor = guideColor
                                )
                                if (guided.isCompleted(GuidedStep.FLASHING_MODE)) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Flashing mode selected",
                                        tint = statusSuccessColor(),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            selectedIso?.let { iso ->
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FlashingMode.entries.forEach { flashMode ->
                                AppSelectableOptionButton(
                                    label = flashMode.shortLabel,
                                    selected = state.flashingMode == flashMode,
                                    onClick = { onSetFlashingMode(flashMode) },
                                    enabled = !isWriteInFlight && !isEjecting,
                                    accessibilityDescription = flashMode.label,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Contextual Windows requirements bypass (Windows mode, or a Windows ISO in Auto mode)
                    val showWindowsBypass = state.flashingMode == FlashingMode.WINDOWS ||
                        (state.flashingMode == FlashingMode.AUTO &&
                            state.selectedIso?.detectedOsFamily == DetectedOsFamily.WINDOWS)
                    AnimatedVisibility(visible = showWindowsBypass) {
                        WindowsBypassSection(
                            options = state.settings.windowsBypassOptions,
                            enabled = !isWriteInFlight && !isEjecting,
                            onToggleTpm = onSetWindowsBypassTpm,
                            onToggleSecureBoot = onSetWindowsBypassSecureBoot,
                            onToggleRam = onSetWindowsBypassRam,
                            onToggleCpu = onSetWindowsBypassCpu,
                            onToggleAll = onSetWindowsBypassAll
                        )
                    }

                    // One primary action; disabled until the image and drive are both ready
                    MainActionsSection(
                        canCreate = canCreate,
                        isFlashing = isWriteInFlight,
                        guideActive = guided.isCurrent(GuidedStep.CREATE),
                        guideError = guided.currentStepHasError,
                        onCreateClick = onRequestCreateConfirm
                    )

                    val blocker = state.preflight.blockers.firstOrNull()
                    val hint = when {
                        blocker != null && (selectedIso != null || selectedDrive != null) -> blocker
                        selectedIso == null && selectedDrive == null ->
                            "Select an image and a USB drive to enable Write."
                        !state.flashingModeSelected && state.preflight.canProceed ->
                            "Choose a flashing mode to enable Write."
                        else -> null
                    }
                    if (hint != null) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (blocker != null && (selectedIso != null || selectedDrive != null)) {
                                statusWarningColor()
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Recent operations — final section on the Home screen
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(
                        title = "Recent Operations",
                        actionLabel = if (state.history.isNotEmpty()) "View all" else null,
                        onAction = onOpenHistoryTab
                    )

                    if (state.history.isEmpty()) {
                        AppEmptyState(
                            icon = Icons.Rounded.History,
                            message = "No USB writes yet. Finished, failed and cancelled writes appear here.",
                            compact = true
                        )
                    } else {
                        val recentHistory = remember(state.history) { state.history.take(3) }
                        AppCard(
                            contentPadding = PaddingValues(0.dp),
                            verticalArrangement = Arrangement.Top
                        ) {
                            recentHistory.forEachIndexed { idx, record ->
                                if (idx > 0) {
                                    AppDivider()
                                }
                                CompactHistoryRow(record)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCancelConfirm) {
        ConfirmCancelDialog(
            onConfirm = {
                showCancelConfirm = false
                onCancelWrite()
            },
            onDismiss = { showCancelConfirm = false }
        )
    }
}

@Composable
private fun StepBadge(step: Int, completed: Boolean, isError: Boolean) {
    val success = statusSuccessColor()
    val error = MaterialTheme.colorScheme.error
    val container = when {
        completed -> success.copy(alpha = 0.16f)
        isError -> error.copy(alpha = 0.16f)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        completed -> success
        isError -> error
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(container)
            .semantics { contentDescription = "Step $step" + if (completed) ", done" else "" },
        contentAlignment = Alignment.Center
    ) {
        if (completed) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = step.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = content
            )
        }
    }
}

/**
 * Step card: numbered badge, title, selected item (name + size/capacity) or an empty state,
 * optional extra content, and a full-width 48dp action button.
 */
@Composable
private fun StepCard(
    step: Int,
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    isError: Boolean,
    ready: Boolean,
    isLoading: Boolean,
    empty: Boolean,
    completed: Boolean,
    emphasizeAction: Boolean,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
    titleGuideActive: Boolean,
    titleGuideColor: Color,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    AppCard(
        accentBorderColor = if (isError) MaterialTheme.colorScheme.error else null
    ) {
        Row(verticalAlignment = Alignment.Top) {
            StepBadge(step = step, completed = completed, isError = isError)
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GuidedStepText(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    guideActive = titleGuideActive,
                    guideColor = titleGuideColor
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (empty) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    isError -> MaterialTheme.colorScheme.error
                    ready -> statusSuccessColor()
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(24.dp)
            )
        }

        content()

        val actionModifier = Modifier.semantics { contentDescription = "$actionLabel: $title" }
        if (emphasizeAction) {
            AppTonalButton(
                text = actionLabel,
                onClick = onAction,
                enabled = enabled,
                modifier = actionModifier
            )
        } else {
            AppOutlineButton(
                text = actionLabel,
                onClick = onAction,
                enabled = enabled,
                modifier = actionModifier
            )
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassCardShape16,
        color = LocalAppColors.current.surfaceInner
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Windows Requirements Bypass",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                if (options.hasAnyEnabled) {
                    StatusPill(
                        text = if (options.allEnabled) "ALL ACTIVE" else "${options.enabledLabels().size} ACTIVE",
                        color = statusWarningColor()
                    )
                }
            }

            BypassCheckboxRow(
                label = "Enable All Bypasses",
                checked = options.allEnabled,
                enabled = enabled,
                bold = true,
                onCheckedChange = onToggleAll
            )

            AppDivider()

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

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = "Warning",
                    tint = statusWarningColor(),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Bypassing hardware requirements allows installation on unsupported PCs, but does not make unsupported hardware officially supported.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Whole-row toggle with a 48dp minimum touch target and checkbox semantics. */
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
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
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
    val visual = historyStatusVisual(record.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusIconBadge(
            icon = visual.icon,
            tint = visual.color,
            size = 32.dp,
            iconSize = 18.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
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
        StatusPill(text = visual.label, color = visual.color)
    }
}

private data class HistoryStatusVisual(
    val icon: ImageVector,
    val color: Color,
    val label: String,
    val bannerTone: BannerTone
)

@Composable
private fun historyStatusVisual(phase: WritePhase): HistoryStatusVisual = when (phase) {
    WritePhase.COMPLETED -> HistoryStatusVisual(
        icon = Icons.Rounded.CheckCircle,
        color = statusSuccessColor(),
        label = "COMPLETED",
        bannerTone = BannerTone.SUCCESS
    )
    WritePhase.CANCELLED -> HistoryStatusVisual(
        icon = Icons.Rounded.Cancel,
        color = statusWarningColor(),
        label = "CANCELLED",
        bannerTone = BannerTone.WARNING
    )
    else -> HistoryStatusVisual(
        icon = Icons.Rounded.ErrorOutline,
        color = MaterialTheme.colorScheme.error,
        label = "FAILED",
        bannerTone = BannerTone.ERROR
    )
}

/**
 * Persisted History Tab showing real completed, failed, and cancelled operations
 * using the same Home-style cards, status icons, detail rows, and result banners.
 */
@Composable
private fun HistoryTabContent(history: List<OperationHistoryRecord>) {
    if (history.isEmpty()) {
        ScreenColumn {
            AppEmptyState(
                icon = Icons.Rounded.History,
                title = "No Operation History",
                message = "Completed, cancelled, and failed USB writes will be logged here with measured transfer speeds.",
                compact = false
            )
        }
        return
    }

    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }

    ScreenLazyColumn {
        item(key = "history_header") {
            SectionHeader(title = "Operation History (${history.size})")
        }
        items(history, key = { it.id }) { record ->
            HistoryRecordCard(record = record, dateFormat = dateFormat)
        }
    }
}

@Composable
private fun HistoryRecordCard(
    record: OperationHistoryRecord,
    dateFormat: SimpleDateFormat
) {
    val visual = historyStatusVisual(record.status)
    val isFailed = visual.bannerTone == BannerTone.ERROR
    val resultMessage = record.summaryNote.ifBlank {
        when (record.status) {
            WritePhase.COMPLETED -> "Bootable USB created successfully."
            WritePhase.CANCELLED -> "Write operation was cancelled."
            else -> "Write operation failed."
        }
    }

    AppCard(
        accentBorderColor = if (isFailed) MaterialTheme.colorScheme.error else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            StatusIconBadge(
                icon = visual.icon,
                tint = visual.color,
                size = 32.dp,
                iconSize = 18.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = dateFormat.format(Date(record.timestampMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = record.isoName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusPill(text = visual.label, color = visual.color)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailRow(label = "USB Drive", value = record.driveName)
            DetailRow(label = "Size", value = ByteUtils.formatBytes(record.isoSizeBytes))
            DetailRow(
                label = "Transfer",
                value = "${ByteUtils.formatSpeed(record.averageSpeedBytesPerSec)} • " +
                    ByteUtils.formatDuration(record.durationMs / 1000L)
            )
        }

        AppBanner(
            message = resultMessage,
            tone = visual.bannerTone,
            icon = visual.icon,
            rounded = true
        )
    }
}

/**
 * Theme-aware Settings Tab matching Home screen cards, section headers, buttons, and spacing.
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
    onSetAutoEjectAfterWrite: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTechnicalGuide: () -> Unit
) {
    val isWriting = state.writeProgress.phase.isActive
    val isEjecting = state.ejectState.isEjecting
    val controlsEnabled = !isWriting && !isEjecting

    ScreenColumn {
        // 1. Appearance — Home-style option buttons inside a Home-style card
        TitledCardSection(title = "Appearance") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    AppSelectableOptionButton(
                        label = when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        },
                        selected = state.settings.themeMode == mode,
                        onClick = { onSetThemeMode(mode) },
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 2 & 3. Progress Bar Style + Neon Customization
        CustomizationSettingsContent(
            savedCustomization = state.customization,
            onApplyCustomization = onApplyCustomization,
            onResetDefaults = onResetCustomization
        )

        // 4. USB Flashing & Integrity (Buffer Size + Post-Write SHA-256 Verification + Auto-Eject)
        TitledCardSection(
            title = "USB Flashing & Integrity",
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "I/O Buffer Size",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Auto picks optimal chunk size (256/128/64 KiB) for your USB port.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WriteBufferOption.entries.forEach { option ->
                    AppSelectableOptionButton(
                        label = if (option == WriteBufferOption.AUTO) "Auto" else option.label,
                        selected = state.settings.bufferOption == option,
                        onClick = { onSetBufferOption(option) },
                        enabled = controlsEnabled,
                        accessibilityDescription = option.label,
                        maxLines = 1
                    )
                }
            }

            AppDivider()

            AppSwitchRow(
                title = "Verify SHA-256 After Writing",
                subtitle = "Read back written sectors over SCSI to verify bit-exact integrity.",
                checked = state.settings.verifyAfterWrite,
                enabled = controlsEnabled,
                onCheckedChange = onSetVerifyAfterWrite
            )

            AppDivider()

            AppSwitchRow(
                title = stringResource(R.string.settings_auto_eject_title),
                subtitle = stringResource(R.string.settings_auto_eject_subtitle),
                checked = state.settings.autoEjectAfterWrite,
                enabled = controlsEnabled,
                onCheckedChange = onSetAutoEjectAfterWrite
            )
        }

        // 5. Diagnostics & Help
        TitledCardSection(
            title = "Diagnostics & Help",
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DiagnosticLogRow(
                logsFlow = logsFlow,
                onOpenLogs = onOpenLogs
            )
            AppDivider()
            AppListRow(
                icon = Icons.Rounded.Info,
                title = "Technical & Compatibility Guide",
                subtitle = "USB Host API, root notes, Windows & Linux boot details",
                onClick = onOpenTechnicalGuide
            )
        }

        // 6. Restore Defaults — confirmation handled by the caller
        TitledCardSection(
            title = "Restore Defaults",
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Reset all preferences to defaults. ISO files, USB data, and history are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppOutlineButton(
                text = "RESTORE DEFAULTS",
                onClick = onResetAllSettings,
                enabled = !isWriting,
                icon = Icons.Rounded.Restore,
                compactTextStyle = true
            )
        }

        // Security Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = null,
                tint = statusSuccessColor(),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${stringResource(id = R.string.app_name)} v${BuildConfig.VERSION_NAME} • Internal storage is never exposed or writable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagnosticLogRow(
    logsFlow: StateFlow<List<LogEntry>>,
    onOpenLogs: () -> Unit
) {
    val logs by logsFlow.collectAsStateWithLifecycle()
    AppListRow(
        icon = Icons.Rounded.Terminal,
        title = "USB / SCSI Diagnostic Log",
        subtitle = "${logs.size} event${if (logs.size == 1) "" else "s"} captured",
        onClick = onOpenLogs
    )
}
