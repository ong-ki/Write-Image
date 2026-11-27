package com.usbbootable.creator.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usbbootable.creator.R
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.writer.TransferSpeedSnapshot
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.service.UsbEjectPhase
import com.usbbootable.creator.service.UsbEjectState
import com.usbbootable.creator.ui.theme.GlassDialogShape
import com.usbbootable.creator.ui.theme.GlassMotion
import com.usbbootable.creator.ui.theme.LocalAppColors
import com.usbbootable.creator.ui.theme.glassDialogContainerColor
import com.usbbootable.creator.ui.theme.glassDialogEnter
import com.usbbootable.creator.ui.theme.rememberMotionEnabled
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor
import kotlin.math.roundToInt

private fun phaseTitle(phase: WritePhase): String = when (phase) {
    WritePhase.IDLE, WritePhase.PREPARING -> "Preparing…"
    WritePhase.PARTITIONING -> "Partitioning drive…"
    WritePhase.WRITING -> "Writing to USB"
    WritePhase.FLUSHING_CACHE -> "Finalizing…"
    WritePhase.VERIFYING -> "Verifying data"
    WritePhase.COMPLETED -> "Completed"
    WritePhase.CANCELLED -> "Cancelled"
    WritePhase.FAILED -> "Failed"
}

/**
 * Full-screen progress view shown while a write is in flight: large percentage (or an
 * indeterminate indicator while PREPARING / PARTITIONING), phase label, live speed, elapsed and
 * remaining time, the real-throughput graph, and a Cancel action (confirmed by the caller).
 */
@Composable
internal fun WriteProgressPanel(
    progress: WriteProgress,
    speedSnapshot: TransferSpeedSnapshot,
    elapsedSeconds: Long,
    fallbackTotalBytes: Long,
    isoName: String,
    driveName: String,
    onCancel: () -> Unit
) {
    val appColors = LocalAppColors.current
    val motionEnabled = rememberMotionEnabled()
    val phase = if (progress.phase == WritePhase.IDLE) WritePhase.PREPARING else progress.phase
    val indeterminate = phase == WritePhase.PREPARING || phase == WritePhase.PARTITIONING

    val fraction by animateFloatAsState(
        targetValue = progress.overallProgress.coerceIn(0f, 0.995f),
        animationSpec = tween(
            durationMillis = if (motionEnabled) GlassMotion.PROGRESS_DURATION_MS else 0,
            easing = FastOutSlowInEasing
        ),
        label = "write_progress_fraction"
    )
    val percent = (fraction * 100f).roundToInt().coerceIn(0, 99)
    val totalBytes = if (progress.totalBytes > 0L) progress.totalBytes else fallbackTotalBytes
    val writtenBytes = when (phase) {
        WritePhase.PREPARING, WritePhase.PARTITIONING -> 0L
        else -> when {
            progress.bytesWritten > 0L -> progress.bytesWritten
            phase == WritePhase.WRITING || phase == WritePhase.FLUSHING_CACHE -> progress.bytesProcessed
            else -> 0L
        }
    }.coerceAtLeast(0L)

    val title = phaseTitle(phase)
    val speedText = if (speedSnapshot.isLive && !speedSnapshot.isEmpty) {
        ByteUtils.formatSpeed(speedSnapshot.currentBytesPerSec)
    } else {
        "-- MB/s"
    }
    val remainingText = if (phase.isActive) ByteUtils.formatDuration(progress.etaSeconds) else "--:--"
    val statusText = if (progress.phase == WritePhase.IDLE) {
        "Starting USB write session..."
    } else {
        progress.statusMessage
    }
    val progressDescription = if (indeterminate) title else "$title, $percent percent complete"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                }
            )
            if (isoName.isNotBlank() || driveName.isNotBlank()) {
                Text(
                    text = "$isoName → $driveName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        AppCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            contentDescription = progressDescription
                            if (!indeterminate) {
                                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier.height(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (indeterminate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp
                            )
                        } else {
                            Text(
                                text = "$percent%",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = 48.sp,
                                    lineHeight = 56.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (indeterminate) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            trackColor = appColors.surfaceInner
                        )
                    } else {
                        StyledProgressBar(
                            progress = fraction,
                            modifier = Modifier.fillMaxWidth(),
                            height = 12.dp
                        )
                    }
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                progress.currentItemLabel.takeIf { it.isNotBlank() && progress.phase != WritePhase.IDLE }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = appColors.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricTile("SPEED", speedText, Modifier.weight(1f))
                    MetricTile("ELAPSED", ByteUtils.formatElapsedClock(elapsedSeconds), Modifier.weight(1f))
                    MetricTile("REMAINING", remainingText, Modifier.weight(1f))
                }
                MetricTile(
                    label = "WRITTEN",
                    value = ByteUtils.formatWrittenTotal(writtenBytes, totalBytes),
                    modifier = Modifier.fillMaxWidth()
                )

                TransferSpeedChart(
                    snapshot = speedSnapshot,
                    phase = phase,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .clearAndSetSemantics {
                            contentDescription = "Live write speed graph, current speed $speedText"
                        }
                )
            }
        }

        AppOutlineButton(
            text = "CANCEL",
            onClick = onCancel,
            icon = Icons.Rounded.Cancel,
            isDestructive = true
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val appColors = LocalAppColors.current
    Column(
        modifier = modifier
            .clip(HomeControlShape)
            .background(appColors.surfaceInner)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = appColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Distinct terminal screens for COMPLETED, FAILED and CANCELLED, each with an icon, a short
 * message and a primary action (Done for success, Retry + Done otherwise).
 */
@Composable
internal fun WriteResultPanel(
    progress: WriteProgress,
    elapsedSeconds: Long,
    isoName: String,
    driveName: String,
    canRetry: Boolean,
    ejectState: UsbEjectState = UsbEjectState(),
    hasConnectedDrive: Boolean = false,
    isWriteActive: Boolean = false,
    onEjectUsb: () -> Unit = {},
    onDone: () -> Unit,
    onRetry: () -> Unit
) {
    val appColors = LocalAppColors.current
    val success = statusSuccessColor()
    val warning = statusWarningColor()
    val error = MaterialTheme.colorScheme.error

    val icon = when (progress.phase) {
        WritePhase.COMPLETED -> Icons.Rounded.CheckCircle
        WritePhase.FAILED -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.Cancel
    }
    val tint = when (progress.phase) {
        WritePhase.COMPLETED -> success
        WritePhase.FAILED -> error
        else -> warning
    }
    val title = when (progress.phase) {
        WritePhase.COMPLETED -> "USB drive is ready"
        WritePhase.FAILED -> "Write failed"
        else -> "Write cancelled"
    }
    val message = when (progress.phase) {
        WritePhase.COMPLETED -> progress.statusMessage
        WritePhase.FAILED -> progress.statusMessage.ifBlank { "Something went wrong while writing." }
        else -> "Writing was stopped. The drive may be partially written and must be written again before it can boot."
    }
    val errorDetail = progress.errorMessage?.takeIf { it.isNotBlank() && it != message }
    val showRetry = progress.phase != WritePhase.COMPLETED && canRetry

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        StatusIconBadge(
            icon = icon,
            tint = tint,
            size = 96.dp,
            iconSize = 56.dp
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            }
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (errorDetail != null) {
            AppBanner(
                message = errorDetail,
                tone = BannerTone.ERROR,
                icon = null,
                rounded = true
            )
        }

        AppCard(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isoName.isNotBlank()) DetailRow("Image", isoName)
            if (driveName.isNotBlank()) DetailRow("Drive", driveName)
            DetailRow("Duration", ByteUtils.formatElapsedClock(elapsedSeconds))
            if (progress.bytesWritten > 0L) {
                DetailRow("Written", ByteUtils.formatBytes(progress.bytesWritten))
            }
            progress.verificationReport?.let { report ->
                DetailRow("Verification", report.methodDescription)
                if (report.bytesVerified > 0L) {
                    DetailRow(
                        "Verified",
                        ByteUtils.formatBytes(report.bytesVerified) +
                            if (report.filesVerified > 1) " in ${report.filesVerified} files" else ""
                    )
                }
                report.mismatchDetail?.let { DetailRow("Mismatch", it) }
            }
            if (progress.phase == WritePhase.COMPLETED) {
                val ejected = ejectState.isEjectedOrWarning
                Text(
                    text = stringResource(
                        if (ejected) R.string.eject_result_hint_after else R.string.eject_result_hint_before
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (ejectState.phase) {
                        UsbEjectPhase.SUCCEEDED -> success
                        UsbEjectPhase.COMPLETED_WARNING -> warning
                        else -> appColors.textSecondary
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (progress.phase == WritePhase.COMPLETED && (hasConnectedDrive || ejectState.isEjecting)) {
            val isEjecting = ejectState.isEjecting
            AppTonalButton(
                text = stringResource(R.string.action_eject_usb),
                onClick = onEjectUsb,
                enabled = !isWriteActive && !isEjecting,
                isLoading = isEjecting,
                loadingText = stringResource(R.string.eject_status_ejecting),
                icon = Icons.Rounded.Eject,
                iconContentDescription = stringResource(R.string.cd_eject_usb),
                minHeight = 52.dp
            )
        }
        if (showRetry) {
            AppPrimaryButton(
                text = "RETRY",
                onClick = onRetry,
                icon = Icons.Rounded.Replay
            )
            TextButton(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text("DONE", style = MaterialTheme.typography.labelLarge)
            }
        } else {
            AppPrimaryButton(
                text = "DONE",
                onClick = onDone
            )
        }
    }
}

/** Confirmation shown before an in-flight write is stopped. */
@Composable
internal fun ConfirmCancelDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = GlassDialogShape,
        containerColor = glassDialogContainerColor(),
        modifier = Modifier.glassDialogEnter(),
        icon = {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Stop writing?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = "Stopping now leaves the USB drive partially written. " +
                    "It will not boot until you write it again.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("STOP WRITING", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("KEEP WRITING")
            }
        }
    )
}
