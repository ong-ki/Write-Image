package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usbbootable.creator.core.usb.IntegrityResult
import com.usbbootable.creator.core.usb.UsbHealthPhase
import com.usbbootable.creator.core.usb.UsbHealthProgress
import com.usbbootable.creator.core.usb.UsbHealthStatus
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.ui.theme.GlassDialogShape
import com.usbbootable.creator.ui.theme.glassDialogContainerColor
import com.usbbootable.creator.ui.theme.glassDialogEnter
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor
import kotlin.math.roundToInt

@Composable
fun UsbHealthCheckDialog(
    progress: UsbHealthProgress,
    onCancel: () -> Unit,
    onRerun: () -> Unit,
    onDismiss: () -> Unit
) {
    val isRunning = progress.phase.isActive
    val report = progress.report

    val statusTone = when {
        isRunning -> BannerTone.INFO
        progress.phase == UsbHealthPhase.CANCELLED -> BannerTone.WARNING
        report?.status == UsbHealthStatus.HEALTHY -> BannerTone.SUCCESS
        report?.status == UsbHealthStatus.WARNING -> BannerTone.WARNING
        else -> BannerTone.ERROR
    }
    val statusColor = when {
        isRunning -> MaterialTheme.colorScheme.primary
        progress.phase == UsbHealthPhase.CANCELLED -> statusWarningColor()
        report?.status == UsbHealthStatus.HEALTHY -> statusSuccessColor()
        report?.status == UsbHealthStatus.WARNING -> statusWarningColor()
        else -> MaterialTheme.colorScheme.error
    }

    AlertDialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        shape = GlassDialogShape,
        containerColor = glassDialogContainerColor(),
        modifier = Modifier.glassDialogEnter(),
        icon = {
            Icon(
                imageVector = when {
                    isRunning -> Icons.Rounded.HealthAndSafety
                    progress.phase == UsbHealthPhase.CANCELLED -> Icons.Rounded.Cancel
                    report?.status == UsbHealthStatus.HEALTHY -> Icons.Rounded.CheckCircle
                    report?.status == UsbHealthStatus.WARNING -> Icons.Rounded.WarningAmber
                    else -> Icons.Rounded.ErrorOutline
                },
                contentDescription = null,
                tint = statusColor
            )
        },
        title = {
            Text(
                text = "USB Health Check",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (progress.driveName.isNotBlank()) {
                    Text(
                        text = progress.driveName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (isRunning) {
                    val percent = (progress.progressFraction.coerceIn(0f, 1f) * 100f).roundToInt()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = progress.currentTest.ifBlank { "Starting diagnostics..." },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$percent% • ${ByteUtils.formatElapsedClock(progress.elapsedSeconds)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    StyledProgressBar(
                        progress = progress.progressFraction.coerceIn(0f, 1f),
                        height = 10.dp
                    )

                    if (progress.statusMessage.isNotBlank()) {
                        Text(
                            text = progress.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "Non-destructive mode: existing user data will not be overwritten.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (report != null) {
                    val bannerPalette = bannerColors(statusTone)
                    Surface(
                        color = bannerPalette.container,
                        shape = HomeControlShape,
                        border = BorderStroke(1.dp, bannerPalette.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "STATUS: ${report.status.label}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = bannerPalette.content,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ByteUtils.formatElapsedClock(report.elapsedSeconds),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = bannerPalette.content
                            )
                        }
                    }

                    DiagnosticMetricRow(
                        label = "Actual Capacity",
                        value = ByteUtils.formatDecimalCapacity(report.reportedCapacityBytes),
                        detail = report.capacityNote
                    )
                    AppDivider()

                    DiagnosticMetricRow(
                        label = "Sequential Read Speed",
                        value = ByteUtils.formatSpeed(report.sequentialReadBytesPerSec)
                    )
                    DiagnosticMetricRow(
                        label = "Sequential Write Speed",
                        value = report.sequentialWriteBytesPerSec?.let { ByteUtils.formatSpeed(it) }
                            ?: "Skipped (Read-Only)"
                    )
                    AppDivider()

                    DiagnosticMetricRow(
                        label = "SHA-256 Verification",
                        value = report.integrityResult.label,
                        valueColor = when (report.integrityResult) {
                            IntegrityResult.PASSED -> statusSuccessColor()
                            IntegrityResult.FAILED -> MaterialTheme.colorScheme.error
                            IntegrityResult.SKIPPED_READ_ONLY -> statusWarningColor()
                        },
                        detail = report.integrityNote
                    )
                    AppDivider()

                    DiagnosticMetricRow(
                        label = "USB I/O Errors",
                        value = "${report.ioErrorCount}",
                        valueColor = if (report.ioErrorCount == 0) statusSuccessColor() else MaterialTheme.colorScheme.error
                    )
                    DiagnosticMetricRow(
                        label = "Connection Stability",
                        value = if (report.connectionStable) "Stable" else "Unstable",
                        valueColor = if (report.connectionStable) statusSuccessColor() else statusWarningColor(),
                        detail = report.stabilityNote
                    )

                    if (report.summaryNotes.isNotEmpty()) {
                        AppDivider()
                        report.summaryNotes.forEach { note ->
                            Text(
                                text = "• $note",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Note: This quick non-destructive test checks addressable capacity and a temporary sample file. It does not scan every block and cannot prove the entire USB drive is defect-free.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AppBanner(
                        message = progress.errorMessage
                            ?: progress.statusMessage.ifBlank { "USB health check ended." },
                        tone = statusTone,
                        rounded = true
                    )
                    Text(
                        text = "Elapsed: ${ByteUtils.formatElapsedClock(progress.elapsedSeconds)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (isRunning) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = HomeControlShape,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CANCEL", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Button(
                    onClick = onDismiss,
                    shape = HomeControlShape,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("CLOSE", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
        dismissButton = {
            if (!isRunning) {
                TextButton(
                    onClick = onRerun,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("RUN AGAIN", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    )
}

@Composable
private fun DiagnosticMetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    detail: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = valueColor
            )
        }
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
