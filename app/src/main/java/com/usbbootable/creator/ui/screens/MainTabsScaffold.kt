package com.usbbootable.creator.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usbbootable.creator.BuildConfig
import com.usbbootable.creator.core.image.DetectedOsFamily
import com.usbbootable.creator.core.image.FlashingMode
import com.usbbootable.creator.core.storage.OperationHistoryRecord
import com.usbbootable.creator.core.storage.WriteBufferOption
import com.usbbootable.creator.core.usb.UsbTransportSupport
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.ui.AppTab
import com.usbbootable.creator.ui.DashboardUiState
import com.usbbootable.creator.ui.theme.ThemeMode
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabsScaffold(
    state: DashboardUiState,
    onSelectTab: (AppTab) -> Unit,
    onSetFlashingMode: (FlashingMode) -> Unit = {},
    onSelectIsoClick: () -> Unit,
    onSelectDrive: (Int) -> Unit,
    onRequestUsbPermission: (Int) -> Unit,
    onRetryProbeDrive: (Int) -> Unit,
    onRefreshDrives: () -> Unit,
    onConfirmStartWrite: () -> Unit,
    onCancelWrite: () -> Unit,
    onDismissFinishedStatus: () -> Unit,
    onDismissBanner: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetBufferOption: (WriteBufferOption) -> Unit,
    onSetVerifyAfterWrite: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onClearLogs: () -> Unit
) {
    var showDrivePicker by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showLogSheet by remember { mutableStateOf(false) }

    val topTitle = when (state.selectedTab) {
        AppTab.HOME -> "USB Bootable Creator"
        AppTab.HISTORY -> "History"
        AppTab.SETTINGS -> "Settings"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = topTitle,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
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
                        alwaysShowLabel = true
                    )
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
                        color = MaterialTheme.colorScheme.secondaryContainer,
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
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (state.selectedTab) {
                    AppTab.HOME -> HomeTabContent(
                        state = state,
                        onSetFlashingMode = onSetFlashingMode,
                        onSelectIsoClick = onSelectIsoClick,
                        onOpenDrivePicker = {
                            onRefreshDrives()
                            showDrivePicker = true
                        },
                        onRequestUsbPermission = onRequestUsbPermission,
                        onRetryProbeDrive = onRetryProbeDrive,
                        onRequestCreateConfirm = { showConfirmDialog = true },
                        onOpenHistoryTab = { onSelectTab(AppTab.HISTORY) },
                        onCancelWrite = onCancelWrite,
                        onDismissFinishedStatus = onDismissFinishedStatus
                    )

                    AppTab.HISTORY -> HistoryTabContent(
                        history = state.history
                    )

                    AppTab.SETTINGS -> SettingsTabContent(
                        state = state,
                        onSetThemeMode = onSetThemeMode,
                        onSetBufferOption = onSetBufferOption,
                        onSetVerifyAfterWrite = onSetVerifyAfterWrite,
                        onOpenLogs = { showLogSheet = true },
                        onOpenTechnicalGuide = { showGuideDialog = true }
                    )
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

    if (showLogSheet) {
        OperationLogSheet(
            logs = state.logs,
            onClear = onClearLogs,
            onDismiss = { showLogSheet = false }
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeTabContent(
    state: DashboardUiState,
    onSetFlashingMode: (FlashingMode) -> Unit,
    onSelectIsoClick: () -> Unit,
    onOpenDrivePicker: () -> Unit,
    onRequestUsbPermission: (Int) -> Unit,
    onRetryProbeDrive: (Int) -> Unit,
    onRequestCreateConfirm: () -> Unit,
    onOpenHistoryTab: () -> Unit,
    onCancelWrite: () -> Unit,
    onDismissFinishedStatus: () -> Unit
) {
    val isWriting = state.writeProgress.phase.isActive
    val connectedCount = state.availableDrives.size
    val usbDotColor = when {
        !state.isUsbHostSupported -> MaterialTheme.colorScheme.error
        state.selectedDrive?.isProbed == true && !state.selectedDrive.isWriteProtected -> statusSuccessColor()
        connectedCount > 0 -> statusWarningColor()
        else -> MaterialTheme.colorScheme.outline
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Clean Header Row with USB OTG Connection Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bootable USB via OTG",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = when {
                        !state.isUsbHostSupported -> "USB OTG Host not reported"
                        connectedCount == 0 -> "No USB drive connected"
                        connectedCount == 1 -> "1 USB drive connected"
                        else -> "$connectedCount USB drives connected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusDotLabel(
                dotColor = usbDotColor,
                label = when {
                    !state.isUsbHostSupported -> "No OTG"
                    connectedCount > 0 -> "Connected"
                    else -> "Standby"
                }
            )
        }

        // 2. Circular Progress Indicator Card (Waiting / Writing / Verifying / Completed / Failed / Cancelled)
        ActiveOperationCard(
            progress = state.writeProgress,
            fallbackTotalBytes = state.selectedIso?.sizeBytes ?: 0L,
            isReadyToStart = state.preflight.canProceed,
            onCancel = onCancelWrite,
            onDismissFinished = onDismissFinishedStatus
        )

        // 3. Flashing Mode Selector (Automatic ISO Detection / Windows Bootable USB / Linux Bootable USB)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Flashing Mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.selectedIso?.let { iso ->
                        val detectedBadge = when (iso.detectedOsFamily) {
                            DetectedOsFamily.WINDOWS -> "Detected: Windows 10/11"
                            DetectedOsFamily.LINUX -> "Detected: Linux ISO"
                            DetectedOsFamily.RAW_DISK_IMAGE -> "Detected: Raw Image"
                            DetectedOsFamily.UNSUPPORTED -> "Detected: Non-Hybrid ISO"
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FlashingMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.flashingMode == mode,
                            enabled = !isWriting,
                            onClick = { onSetFlashingMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }
        }

        // 4. Single Unified Summary Surface (Selected ISO + Selected USB Drive)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                // ISO Row
                HomeStatusRow(
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
                        else -> "Tap Select to pick a Windows or Linux ISO"
                    },
                    isError = state.isoError != null,
                    ready = state.selectedIso?.isAccessible == true,
                    isLoading = state.isAnalyzingIso,
                    actionLabel = if (state.selectedIso == null) "Select" else "Change",
                    enabled = !isWriting,
                    onAction = onSelectIsoClick
                )

                // Warnings for selected ISO (e.g. >4 GiB dual partition or non-hybrid optical notice)
                state.selectedIso?.warnings?.takeIf { it.isNotEmpty() }?.let { warnings ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        warnings.forEach { warn ->
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = statusWarningColor(),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = warn,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                // USB Drive Row
                val drive = state.selectedDrive
                val driveNeedsPermission = drive != null &&
                    drive.transportSupport == UsbTransportSupport.SUPPORTED_BOT &&
                    !drive.hasPermission
                val driveHasProbeError = drive != null && drive.probeError != null

                HomeStatusRow(
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
                    enabled = !isWriting,
                    onAction = {
                        when {
                            driveNeedsPermission -> onRequestUsbPermission(drive.deviceId)
                            driveHasProbeError -> onRetryProbeDrive(drive.deviceId)
                            else -> onOpenDrivePicker()
                        }
                    }
                )
            }
        }

        // 4. Primary Action Buttons: SELECT ISO, SELECT USB DRIVE, CREATE BOOTABLE USB
        MainActionsSection(
            isWriting = isWriting,
            connectedDriveCount = connectedCount,
            canCreate = state.preflight.canProceed && !isWriting && !state.isAnalyzingIso,
            onSelectIsoClick = onSelectIsoClick,
            onSelectUsbClick = onOpenDrivePicker,
            onCreateClick = onRequestCreateConfirm
        )

        // Inline Pre-flight Blockers when ISO or USB is selected but not yet ready to flash
        if (!isWriting && state.preflight.blockers.isNotEmpty() && (state.selectedIso != null || state.selectedDrive != null)) {
            Text(
                text = state.preflight.blockers.first(),
                style = MaterialTheme.typography.bodySmall,
                color = statusWarningColor(),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // 5. Recent Operations (Clean minimal list, up to 3 latest)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Operations",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.history.isNotEmpty()) {
                    TextButton(onClick = onOpenHistoryTab) {
                        Text("View all")
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
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        state.history.take(3).forEachIndexed { idx, record ->
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
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp
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
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onAction, enabled = enabled) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun StatusDotLabel(dotColor: Color, label: String) {
    Surface(
        color = dotColor.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = dotColor
            )
        }
    }
}

@Composable
private fun CompactHistoryRow(record: OperationHistoryRecord) {
    val (statusColor, statusLabel) = historyStatusVisual(record.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
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
 * Clean Settings Tab (Theme, I/O Buffer Tuning, Post-Write Verification, Diagnostics & Guide).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsTabContent(
    state: DashboardUiState,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetBufferOption: (WriteBufferOption) -> Unit,
    onSetVerifyAfterWrite: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenTechnicalGuide: () -> Unit
) {
    val isWriting = state.writeProgress.phase.isActive

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Appearance
        SettingsSection(title = "Appearance") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.settings.themeMode == mode,
                        onClick = { onSetThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                }
                            )
                        }
                    )
                }
            }
        }

        // USB Write Performance Buffer Size
        SettingsSection(title = "USB Write Performance") {
            Text(
                text = "I/O Buffer Size",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Auto selects 256 KiB on USB 3.x (Android 9+), 128 KiB on USB 2.0, or 64 KiB on Android 8.x for maximum throughput with millisecond cancel response.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WriteBufferOption.entries.forEach { option ->
                    FilterChip(
                        selected = state.settings.bufferOption == option,
                        enabled = !isWriting,
                        onClick = { onSetBufferOption(option) },
                        label = { Text(option.label) }
                    )
                }
            }
        }

        // Data Integrity
        SettingsSection(title = "Data Integrity") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Verify SHA-256 After Writing",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Reads back written sectors/files over SCSI to verify bit-exact integrity. Disabling speeds up total time but skips corruption detection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = state.settings.verifyAfterWrite,
                    enabled = !isWriting,
                    onCheckedChange = onSetVerifyAfterWrite
                )
            }
        }

        // Diagnostics & Technical Guide
        SettingsSection(title = "Diagnostics & Documentation") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLogs)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("USB / SCSI Diagnostic Log", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${state.logs.size} events captured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTechnicalGuide)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Technical & Compatibility Guide", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "USB Host API, root notes, Windows vs Linux ISO boot requirements",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Compact Security Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Security,
                contentDescription = null,
                tint = statusSuccessColor(),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "v${BuildConfig.VERSION_NAME} • Internal storage is never exposed or writable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                content()
            }
        }
    }
}
