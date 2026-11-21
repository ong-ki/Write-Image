package com.usbbootable.creator.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usbbootable.creator.core.image.SelectedIsoInfo
import com.usbbootable.creator.core.usb.UsbDriveInfo
import com.usbbootable.creator.core.usb.UsbTransportSupport
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.ui.DashboardUiState
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor
import kotlin.math.roundToInt

@Composable
fun CreateUsbTabContent(
    state: DashboardUiState,
    onSelectIsoClick: () -> Unit,
    onOpenDrivePicker: () -> Unit,
    onRequestUsbPermission: (Int) -> Unit,
    onRetryProbeDrive: (Int) -> Unit,
    onRequestCreateConfirm: () -> Unit,
    onCancelWrite: () -> Unit,
    onDismissFinishedStatus: () -> Unit
) {
    val isWriting = state.writeProgress.phase.isActive

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Active Write / Verify / Completed / Failed / Cancelled Progress Panel
        ActiveOperationCard(
            progress = state.writeProgress,
            fallbackTotalBytes = state.selectedIso?.sizeBytes ?: 0L,
            isReadyToStart = state.preflight.canProceed,
            onCancel = onCancelWrite,
            onDismissFinished = onDismissFinishedStatus
        )

        // Primary Action Buttons: SELECT ISO, SELECT USB DRIVE, CREATE BOOTABLE USB
        MainActionsSection(
            isWriting = isWriting,
            connectedDriveCount = state.availableDrives.size,
            canCreate = state.preflight.canProceed && !isWriting && !state.isAnalyzingIso,
            onSelectIsoClick = onSelectIsoClick,
            onSelectUsbClick = onOpenDrivePicker,
            onCreateClick = onRequestCreateConfirm
        )

        // Transparent Pre-flight Blockers when Create is disabled
        if (!isWriting && state.preflight.blockers.isNotEmpty() && (state.selectedIso != null || state.selectedDrive != null)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Required Before Flashing",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.preflight.blockers.forEach { blocker ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "• ",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusWarningColor(),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = blocker,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Selected ISO Details & Bootability Analysis
        IsoSummaryCard(
            iso = state.selectedIso,
            isAnalyzing = state.isAnalyzingIso,
            isoError = state.isoError
        )

        // Target USB OTG Drive Details
        UsbDriveSummaryCard(
            drive = state.selectedDrive,
            totalDetected = state.availableDrives.size,
            onGrantPermission = onRequestUsbPermission,
            onRetryProbe = onRetryProbeDrive
        )

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun ReadinessOverviewCard(state: DashboardUiState) {
    val step1Ready = state.selectedIso != null && state.selectedIso.isAccessible
    val step2Ready = state.selectedDrive != null &&
        state.selectedDrive.hasPermission &&
        state.selectedDrive.isProbed &&
        !state.selectedDrive.isWriteProtected
    val step3Ready = state.preflight.canProceed

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Flash Pipeline",
                    style = MaterialTheme.typography.titleMedium
                )
                StatusPill(
                    text = if (step3Ready) "READY TO FLASH" else "SETUP REQUIRED",
                    color = if (step3Ready) statusSuccessColor() else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PipelineStep(number = "1", label = "ISO Image", ready = step1Ready, modifier = Modifier.weight(1f))
                PipelineStep(number = "2", label = "USB OTG", ready = step2Ready, modifier = Modifier.weight(1f))
                PipelineStep(number = "3", label = "Write & Verify", ready = step3Ready, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PipelineStep(
    number: String,
    label: String,
    ready: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (ready) {
                statusSuccessColor().copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(34.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (ready) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = statusSuccessColor(),
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = number,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun MainActionsSection(
    isWriting: Boolean,
    connectedDriveCount: Int,
    canCreate: Boolean,
    onSelectIsoClick: () -> Unit,
    onSelectUsbClick: () -> Unit,
    onCreateClick: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalButton(
                onClick = onSelectIsoClick,
                enabled = !isWriting,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SELECT ISO",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }

            FilledTonalButton(
                onClick = onSelectUsbClick,
                enabled = !isWriting,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Usb,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (connectedDriveCount > 0) {
                        "SELECT USB DRIVE ($connectedDriveCount)"
                    } else {
                        "SELECT USB DRIVE"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Button(
            onClick = onCreateClick,
            enabled = canCreate,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "CREATE BOOTABLE USB",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * Modern Material 3 circular progress indicator card supporting all 6 visual states:
 * - Waiting (IDLE): Empty circular ring (0%)
 * - Writing (PREPARING / PARTITIONING / WRITING / FLUSHING_CACHE): Animated primary progress ring
 * - Verifying (VERIFYING): Distinct tertiary/teal verification ring with shield icon
 * - Completed (COMPLETED): Full 100% emerald circle with success checkmark icon
 * - Failed (FAILED): Error red indicator ring with error icon
 * - Cancelled (CANCELLED): Amber cancelled ring (snaps immediately without waiting for animation)
 */
@Composable
internal fun ActiveOperationCard(
    progress: WriteProgress,
    fallbackTotalBytes: Long = 0L,
    isReadyToStart: Boolean = false,
    onCancel: () -> Unit,
    onDismissFinished: () -> Unit
) {
    val accentColor = when (progress.phase) {
        WritePhase.IDLE -> if (isReadyToStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        WritePhase.VERIFYING -> MaterialTheme.colorScheme.tertiary
        WritePhase.COMPLETED -> statusSuccessColor()
        WritePhase.FAILED -> MaterialTheme.colorScheme.error
        WritePhase.CANCELLED -> statusWarningColor()
        else -> MaterialTheme.colorScheme.primary
    }

    val targetFraction = when (progress.phase) {
        WritePhase.IDLE -> 0f
        WritePhase.COMPLETED -> 1f
        else -> progress.overallProgress.coerceIn(0f, 1f)
    }

    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "usb_circular_progress"
    )

    // Do NOT wait for animation when Cancelled, Failed, or Idle — snap immediately
    val displayFraction = when (progress.phase) {
        WritePhase.CANCELLED, WritePhase.FAILED, WritePhase.IDLE -> targetFraction
        else -> animatedFraction
    }

    val percentInt = (displayFraction * 100f).roundToInt().coerceIn(0, 100)

    val phaseTitle = when (progress.phase) {
        WritePhase.IDLE -> "WAITING"
        WritePhase.PREPARING -> "PREPARING"
        WritePhase.PARTITIONING -> "PARTITIONING"
        WritePhase.WRITING -> "WRITING"
        WritePhase.FLUSHING_CACHE -> "FINALIZING"
        WritePhase.VERIFYING -> "VERIFYING"
        WritePhase.COMPLETED -> "COMPLETED"
        WritePhase.CANCELLED -> "CANCELLED"
        WritePhase.FAILED -> "FAILED"
    }

    val totalBytesToDisplay = if (progress.totalBytes > 0L) progress.totalBytes else fallbackTotalBytes
    val writtenBytesToDisplay = when (progress.phase) {
        WritePhase.IDLE -> 0L
        WritePhase.COMPLETED -> totalBytesToDisplay
        else -> progress.bytesProcessed
    }

    val statusText = when {
        progress.phase == WritePhase.IDLE && isReadyToStart ->
            "Ready to write bootable USB drive."
        progress.phase == WritePhase.IDLE ->
            "Select an ISO file and USB drive to begin."
        else -> progress.statusMessage
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (progress.phase == WritePhase.IDLE) 1.dp else 1.5.dp,
            color = accentColor.copy(alpha = if (progress.phase == WritePhase.IDLE) 0.55f else 0.75f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Large Circular Progress Ring with Percentage & State Icon in the Center
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(168.dp)
            ) {
                CircularProgressIndicator(
                    progress = { displayFraction },
                    modifier = Modifier.size(168.dp),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeWidth = 13.dp,
                    strokeCap = StrokeCap.Round
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(20.dp)
                ) {
                    when (progress.phase) {
                        WritePhase.VERIFYING -> {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = "Verifying",
                                tint = accentColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        WritePhase.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Completed",
                                tint = accentColor,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        WritePhase.FAILED -> {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Failed",
                                tint = accentColor,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        WritePhase.CANCELLED -> {
                            Icon(
                                imageVector = Icons.Rounded.Cancel,
                                contentDescription = "Cancelled",
                                tint = accentColor,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        else -> Unit
                    }

                    Text(
                        text = "$percentInt%",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (progress.phase == WritePhase.IDLE) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            accentColor
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    StatusPill(text = phaseTitle, color = accentColor)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Current Operation Status
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (progress.currentItemLabel.isNotBlank() && progress.phase != WritePhase.IDLE) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = progress.currentItemLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))

            // Real-time Telemetry Grid below the Circular Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryMetric(
                    label = "WRITTEN / TOTAL",
                    value = if (totalBytesToDisplay > 0L) {
                        "${ByteUtils.formatBytes(writtenBytesToDisplay)} / ${ByteUtils.formatBytes(totalBytesToDisplay)}"
                    } else {
                        "0 B / --"
                    }
                )
                TelemetryMetric(
                    label = "SPEED",
                    value = if (progress.phase.isActive) {
                        ByteUtils.formatSpeed(progress.speedBytesPerSec)
                    } else {
                        "-- MB/s"
                    }
                )
                TelemetryMetric(
                    label = "REMAINING",
                    value = if (progress.phase.isActive) {
                        ByteUtils.formatDuration(progress.etaSeconds)
                    } else {
                        "--:--"
                    }
                )
            }

            // Verification report when finished
            progress.verificationReport?.let { report ->
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(10.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (report.verified) Icons.Rounded.VerifiedUser else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = if (report.verified) statusSuccessColor() else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = report.methodDescription,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Verified: ${ByteUtils.formatBytes(report.bytesVerified)}" +
                            if (report.filesVerified > 1) " across ${report.filesVerified} files" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    report.driveSha256?.let { sha ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SHA-256: $sha",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    report.mismatchDetail?.let { detail ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            progress.errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Prominent Cancel button directly below the circular indicator while writing/verifying
            if (progress.phase.isActive) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onCancel,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CANCEL", style = MaterialTheme.typography.labelLarge)
                }
            } else if (progress.phase != WritePhase.IDLE) {
                Spacer(modifier = Modifier.height(10.dp))
                TextButton(
                    onClick = onDismissFinished,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("RESET STATUS")
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetric(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IsoSummaryCard(
    iso: SelectedIsoInfo?,
    isAnalyzing: Boolean,
    isoError: String?
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Source ISO / Disk Image",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (iso != null) {
                    StatusPill(
                        text = if (iso.isAccessible) "VERIFIED" else "INACCESSIBLE",
                        color = if (iso.isAccessible) statusSuccessColor() else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when {
                isAnalyzing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Inspecting MBR, GPT, ISO-9660, UDF & boot headers...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                isoError != null -> {
                    Text(
                        text = isoError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                iso == null -> {
                    Text(
                        text = "No image selected. Tap SELECT ISO to choose a Windows or Linux .iso / .img file from internal storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        text = iso.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Size: ${ByteUtils.formatBytes(iso.sizeBytes)} (${iso.sizeBytes} bytes) • Label: ${iso.volumeLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (iso.bootSupport.uefiSupported) {
                            StatusPill(text = "UEFI", color = statusSuccessColor())
                        }
                        if (iso.bootSupport.legacyBiosSupported) {
                            StatusPill(text = "LEGACY BIOS", color = statusSuccessColor())
                        }
                        if (iso.bootSupport.secureBootFriendly) {
                            StatusPill(text = "SECURE BOOT OK", color = MaterialTheme.colorScheme.tertiary)
                        }
                        if (!iso.bootSupport.isGuaranteedBootable) {
                            StatusPill(text = "NOT GUARANTEED BOOTABLE", color = statusWarningColor())
                        }
                        if (iso.hasUdf) {
                            StatusPill(text = "UDF", color = MaterialTheme.colorScheme.secondary)
                        }
                        if (iso.hasIso9660) {
                            StatusPill(text = "ISO-9660", color = MaterialTheme.colorScheme.secondary)
                        }
                        if (iso.hasGpt) {
                            StatusPill(text = "GPT", color = MaterialTheme.colorScheme.secondary)
                        }
                        if (iso.hasMbr) {
                            StatusPill(text = "MBR", color = MaterialTheme.colorScheme.secondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = iso.bootSupport.headline,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (iso.bootSupport.isGuaranteedBootable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            statusWarningColor()
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = iso.bootSupport.technicalExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (iso.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = statusWarningColor().copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                iso.warnings.forEach { warn ->
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            imageVector = Icons.Rounded.WarningAmber,
                                            contentDescription = null,
                                            tint = statusWarningColor(),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = warn,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbDriveSummaryCard(
    drive: UsbDriveInfo?,
    totalDetected: Int,
    onGrantPermission: (Int) -> Unit,
    onRetryProbe: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Target USB OTG Drive",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                val badgeText = when {
                    drive == null && totalDetected == 0 -> "DISCONNECTED"
                    drive == null -> "$totalDetected DETECTED"
                    !drive.hasPermission -> "PERMISSION NEEDED"
                    drive.isWriteProtected -> "READ-ONLY"
                    drive.isProbed -> "READY"
                    else -> "PROBING"
                }
                val badgeColor = when {
                    drive == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    !drive.hasPermission -> statusWarningColor()
                    drive.isWriteProtected || drive.probeError != null -> MaterialTheme.colorScheme.error
                    drive.isProbed -> statusSuccessColor()
                    else -> MaterialTheme.colorScheme.primary
                }
                StatusPill(text = badgeText, color = badgeColor)
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (drive == null) {
                Text(
                    text = if (totalDetected > 0) {
                        "$totalDetected USB storage drive(s) detected. Tap SELECT USB DRIVE to choose your target."
                    } else {
                        "No USB flash drive connected. Plug in a USB drive through an OTG adapter, then tap SELECT USB DRIVE."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = drive.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "VID:PID ${drive.vidPidHex} • Path: ${drive.deviceName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                when {
                    drive.transportSupport == UsbTransportSupport.UNSUPPORTED_UASP_ONLY -> {
                        Text(
                            text = "Incompatible USB protocol: UASP-only device (no Bulk-Only Transport fallback).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    !drive.hasPermission -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Grant Android USB Host permission to probe real capacity.",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusWarningColor(),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { onGrantPermission(drive.deviceId) }) {
                                Text("GRANT")
                            }
                        }
                    }

                    drive.probeError != null -> {
                        Text(
                            text = drive.probeError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = { onRetryProbe(drive.deviceId) }) {
                            Text("RETRY SCSI PROBE")
                        }
                    }

                    !drive.isProbed -> {
                        Text(
                            text = "Reading SCSI INQUIRY & READ CAPACITY...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    else -> {
                        Text(
                            text = "Capacity: ${ByteUtils.formatDecimalCapacity(drive.capacityBytes)} • ${drive.logicalBlockSize}-byte sectors (${drive.totalBlocks} LBAs)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (drive.isWriteProtected) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Drive is write-protected (read-only).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatusPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
