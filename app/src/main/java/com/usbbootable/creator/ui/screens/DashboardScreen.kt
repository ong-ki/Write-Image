package com.usbbootable.creator.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usbbootable.creator.core.util.ByteUtils
import com.usbbootable.creator.core.writer.TransferSpeedSnapshot
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.core.writer.WriteProgress
import com.usbbootable.creator.ui.GuidedStepText
import com.usbbootable.creator.ui.theme.CyanAccentBright
import com.usbbootable.creator.ui.theme.ElectricBlue
import com.usbbootable.creator.ui.theme.ElectricBlueBright
import com.usbbootable.creator.ui.theme.GlassCardShape14
import com.usbbootable.creator.ui.theme.GlassCardShape16
import com.usbbootable.creator.ui.theme.GlassCardSurface
import com.usbbootable.creator.ui.theme.GlassMotion
import com.usbbootable.creator.ui.theme.GlassTextWhite
import com.usbbootable.creator.ui.theme.LocalAppColors
import com.usbbootable.creator.ui.theme.LocalNeonTheme
import com.usbbootable.creator.ui.theme.LocalProgressBarStyle
import com.usbbootable.creator.ui.theme.adaptAccentForSurface
import com.usbbootable.creator.ui.theme.effectivePrimaryColor
import com.usbbootable.creator.ui.theme.effectiveSecondaryColor
import com.usbbootable.creator.ui.theme.pressScale
import com.usbbootable.creator.ui.theme.rememberMotionEnabled
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import java.util.Locale

private val PillShape = RoundedCornerShape(8.dp)
private val ErrorBoxShape = RoundedCornerShape(10.dp)
private val ChartBoxShape = RoundedCornerShape(14.dp)
private const val SPEED_CHART_WINDOW_MS = 20_000L
private const val BYTES_PER_MB = 1024.0 * 1024.0

@Composable
internal fun MainActionsSection(
    canCreate: Boolean,
    isFlashing: Boolean = false,
    guideActive: Boolean = false,
    guideError: Boolean = false,
    onCreateClick: () -> Unit
) {
    val createInteraction = remember { MutableInteractionSource() }
    val appColors = LocalAppColors.current
    val neon = LocalNeonTheme.current
    val isReady = canCreate && !isFlashing

    val readyContainerColor = lerp(ElectricBlue, ElectricBlueBright, 0.16f)
    val glowStrength = if (neon.neonEnabled) {
        (0.55f + 0.45f * neon.glowIntensity).coerceIn(0.45f, 1.0f)
    } else {
        0.35f
    }

    val buttonBorder = if (isReady) {
        BorderStroke(
            width = 1.25.dp,
            color = lerp(ElectricBlueBright, CyanAccentBright, 0.35f).copy(alpha = 0.90f)
        )
    } else {
        BorderStroke(
            width = 1.dp,
            color = appColors.borderSubtle.copy(alpha = if (appColors.isDark) 0.65f else 0.90f)
        )
    }

    val glowModifier = if (isReady) {
        Modifier.drawWithCache {
            val outerExpand = 5.dp.toPx()
            val innerExpand = 2.dp.toPx()
            val baseCorner = 14.dp.toPx()
            val outerColor = ElectricBlueBright.copy(alpha = (0.20f * glowStrength).coerceIn(0f, 0.26f))
            val innerColor = CyanAccentBright.copy(alpha = (0.22f * glowStrength).coerceIn(0f, 0.28f))
            onDrawBehind {
                drawRoundRect(
                    color = outerColor,
                    topLeft = Offset(-outerExpand, -outerExpand * 0.7f),
                    size = Size(size.width + outerExpand * 2f, size.height + outerExpand * 1.4f),
                    cornerRadius = CornerRadius(baseCorner + outerExpand, baseCorner + outerExpand)
                )
                drawRoundRect(
                    color = innerColor,
                    topLeft = Offset(-innerExpand, -innerExpand * 0.6f),
                    size = Size(size.width + innerExpand * 2f, size.height + innerExpand * 1.2f),
                    cornerRadius = CornerRadius(baseCorner + innerExpand, baseCorner + innerExpand)
                )
            }
        }
    } else {
        Modifier
    }

    val createTextDefaultColor = if (isReady) GlassTextWhite else appColors.textDisabled
    val createTextGuideColor = when {
        guideError -> MaterialTheme.colorScheme.error
        isReady -> lerp(GlassTextWhite, neon.effectiveSecondaryColor, 0.22f)
        else -> MaterialTheme.colorScheme.primary
    }

    Button(
        onClick = {
            if (isReady) onCreateClick()
        },
        enabled = isReady,
        interactionSource = createInteraction,
        shape = GlassCardShape14,
        border = buttonBorder,
        colors = ButtonDefaults.buttonColors(
            containerColor = readyContainerColor,
            contentColor = GlassTextWhite,
            disabledContainerColor = appColors.disabledCreateContainer,
            disabledContentColor = appColors.textDisabled
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pressScale(createInteraction, enabled = isReady)
            .then(glowModifier)
    ) {
        if (isFlashing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = appColors.textSecondary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "FLASHING IN PROGRESS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            GuidedStepText(
                text = "CREATE BOOTABLE USB",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                defaultColor = createTextDefaultColor,
                guideActive = guideActive,
                guideColor = createTextGuideColor,
                minAlpha = 0.45f
            )
        }
    }
}

/**
 * Progress card with a separate, Linux-style live transfer-speed graph:
 * - Graph X-axis: recent write time (fixed 20-second scrolling window)
 * - Graph Y-axis: real write throughput in MB/s, from confirmed USB write bytes
 * - Overall completion percentage and progress bar remain independent of the graph.
 */
@Composable
internal fun ActiveOperationCard(
    progress: WriteProgress,
    speedSnapshot: TransferSpeedSnapshot = TransferSpeedSnapshot.EMPTY,
    elapsedSeconds: Long = 0L,
    fallbackTotalBytes: Long = 0L,
    isReadyToStart: Boolean = false,
    isStarting: Boolean = false,
    onCancel: () -> Unit,
    onDismissFinished: () -> Unit
) {
    val motionEnabled = rememberMotionEnabled()
    val appColors = LocalAppColors.current
    val neon = LocalNeonTheme.current
    val progressBarStyle = LocalProgressBarStyle.current
    val neonPrimary = adaptAccentForSurface(neon.effectivePrimaryColor, appColors.isDark)
    val neonSecondary = adaptAccentForSurface(neon.effectiveSecondaryColor, appColors.isDark)
    val effectivePhase = if (progress.phase == WritePhase.IDLE && isStarting) {
        WritePhase.PREPARING
    } else {
        progress.phase
    }

    val targetAccentColor = when (effectivePhase) {
        WritePhase.IDLE -> if (isReadyToStart) neonPrimary else MaterialTheme.colorScheme.outline
        WritePhase.VERIFYING -> neonSecondary
        WritePhase.COMPLETED -> statusSuccessColor()
        WritePhase.FAILED -> MaterialTheme.colorScheme.error
        WritePhase.CANCELLED -> statusWarningColor()
        else -> neonSecondary
    }
    val accentColor by animateColorAsState(
        targetValue = targetAccentColor,
        animationSpec = tween(
            durationMillis = if (motionEnabled) GlassMotion.STATUS_DURATION_MS else 0,
            easing = FastOutSlowInEasing
        ),
        label = "progress_accent_color"
    )

    val targetFraction = when (effectivePhase) {
        WritePhase.IDLE -> 0f
        WritePhase.COMPLETED -> 1f
        else -> progress.overallProgress.coerceIn(0f, 0.995f)
    }

    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(
            durationMillis = if (motionEnabled) GlassMotion.PROGRESS_DURATION_MS else 0,
            easing = FastOutSlowInEasing
        ),
        label = "usb_area_progress"
    )

    // Do NOT wait for animation when Cancelled, Failed, or Idle — snap immediately
    val displayFraction = when (effectivePhase) {
        WritePhase.CANCELLED, WritePhase.FAILED, WritePhase.IDLE -> targetFraction
        else -> animatedFraction
    }

    val percentInt = when (effectivePhase) {
        WritePhase.COMPLETED -> 100
        WritePhase.IDLE -> 0
        else -> (displayFraction * 100f).roundToInt().coerceIn(0, 99)
    }

    val phaseTitle = when (effectivePhase) {
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
    val writtenBytesToDisplay = when (effectivePhase) {
        WritePhase.IDLE, WritePhase.PREPARING, WritePhase.PARTITIONING -> 0L
        WritePhase.COMPLETED -> if (progress.bytesWritten > 0L) progress.bytesWritten else totalBytesToDisplay
        else -> when {
            progress.bytesWritten > 0L -> progress.bytesWritten
            effectivePhase == WritePhase.WRITING || effectivePhase == WritePhase.FLUSHING_CACHE ->
                progress.bytesProcessed
            else -> 0L
        }
    }.coerceAtLeast(0L)

    val statusText = when {
        progress.phase == WritePhase.IDLE && isStarting ->
            "Starting USB write session..."
        effectivePhase == WritePhase.IDLE && isReadyToStart ->
            "Ready to write bootable USB drive."
        effectivePhase == WritePhase.IDLE ->
            "Select an ISO file and USB drive to begin."
        else -> progress.statusMessage
    }

    val elapsedFormatted = ByteUtils.formatElapsedClock(elapsedSeconds)
    val writtenTotalFormatted = ByteUtils.formatWrittenTotal(writtenBytesToDisplay, totalBytesToDisplay)

    GlassCardSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassCardShape16,
        accentBorderColor = if (effectivePhase == WritePhase.IDLE && !isReadyToStart) null else accentColor,
        borderWidth = if (effectivePhase == WritePhase.IDLE) 1.dp else 1.5.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Header Row: Phase Badge + Status on left, Completion Percentage on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    when (effectivePhase) {
                        WritePhase.VERIFYING -> {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = "Verifying",
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        WritePhase.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Completed",
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        WritePhase.FAILED -> {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Failed",
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        WritePhase.CANCELLED -> {
                            Icon(
                                imageVector = Icons.Rounded.Cancel,
                                contentDescription = "Cancelled",
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        else -> Unit
                    }
                    StatusPill(text = phaseTitle, color = accentColor)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "$percentInt%",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (effectivePhase == WritePhase.IDLE) {
                        appColors.textPrimary
                    } else {
                        accentColor
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Live transfer-speed waveform (real completed USB writes only)
            TransferSpeedChart(
                snapshot = speedSnapshot,
                phase = effectivePhase,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(94.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Speed statistics — independent of overall completion percentage above/below
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryMetric(
                    label = "CURRENT",
                    value = if (speedSnapshot.isLive && !speedSnapshot.isEmpty) {
                        ByteUtils.formatSpeed(speedSnapshot.currentBytesPerSec)
                    } else {
                        "-- MB/s"
                    },
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetric(
                    label = "AVERAGE",
                    value = if (speedSnapshot.isEmpty) {
                        "-- MB/s"
                    } else {
                        ByteUtils.formatSpeed(speedSnapshot.averageBytesPerSec)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetric(
                    label = "PEAK",
                    value = if (speedSnapshot.isEmpty) {
                        "-- MB/s"
                    } else {
                        ByteUtils.formatSpeed(speedSnapshot.peakBytesPerSec)
                    },
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Selected Progress Bar Style indicator reflecting live completion
            val barAccentOverride = when (effectivePhase) {
                WritePhase.FAILED, WritePhase.CANCELLED -> accentColor
                else -> null
            }
            StyledProgressBar(
                progress = displayFraction,
                style = progressBarStyle,
                neonTheme = neon,
                overrideAccentColor = barAccentOverride,
                height = 12.dp
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Current Operation Status + Active Item
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = appColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val itemDetail = progress.currentItemLabel.takeIf {
                it.isNotBlank() && effectivePhase != WritePhase.IDLE
            }
            if (itemDetail != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = itemDetail,
                    style = MaterialTheme.typography.labelSmall,
                    color = appColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = appColors.borderSubtle)
            Spacer(modifier = Modifier.height(6.dp))

            // Compact Real-time Telemetry Row: Elapsed Time, Written / Total Size, Remaining Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryMetric(
                    label = "ELAPSED",
                    value = elapsedFormatted,
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.weight(0.75f)
                )
                TelemetryMetric(
                    label = "WRITTEN",
                    value = writtenTotalFormatted,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1.5f)
                )
                TelemetryMetric(
                    label = "REMAINING",
                    value = if (effectivePhase.isActive) {
                        ByteUtils.formatDuration(progress.etaSeconds)
                    } else {
                        "--:--"
                    },
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(0.75f)
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
                    shape = ErrorBoxShape,
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

            // Prominent Cancel button directly below the progress section while writing/verifying
            if (effectivePhase.isActive) {
                val cancelInteraction = remember { MutableInteractionSource() }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onCancel,
                    interactionSource = cancelInteraction,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .pressScale(cancelInteraction)
                ) {
                    Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CANCEL", style = MaterialTheme.typography.labelLarge)
                }
            } else if (effectivePhase != WritePhase.IDLE) {
                Spacer(modifier = Modifier.height(6.dp))
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

/**
 * Linux-style live transfer-speed graph.
 *
 * X: fixed 20-second scrolling window ending at the newest sample.
 * Y: real throughput in MB/s, computed only from bytes the USB writer confirmed as written.
 * The waveform scrolls left as time advances, rises with faster writes, falls as throughput
 * drops (including zero during stalls), and freezes once the write finishes or disconnects.
 */
@Composable
private fun TransferSpeedChart(
    snapshot: TransferSpeedSnapshot,
    phase: WritePhase,
    modifier: Modifier = Modifier
) {
    val linePath = remember { Path() }
    val areaPath = remember { Path() }

    val appColors = LocalAppColors.current
    val neon = LocalNeonTheme.current
    val neonPrimary = adaptAccentForSurface(neon.effectivePrimaryColor, appColors.isDark)
    val neonSecondary = adaptAccentForSurface(neon.effectiveSecondaryColor, appColors.isDark)
    val glowStrength = if (neon.neonEnabled) neon.glowIntensity else 0.15f

    val errorColor = MaterialTheme.colorScheme.error
    val warningColor = statusWarningColor()
    val successColor = statusSuccessColor()

    val lineColors = remember(phase, neonPrimary, neonSecondary, errorColor, warningColor, successColor) {
        when (phase) {
            WritePhase.FAILED -> listOf(errorColor, errorColor.copy(alpha = 0.85f))
            WritePhase.CANCELLED -> listOf(warningColor, warningColor.copy(alpha = 0.85f))
            WritePhase.COMPLETED -> listOf(neonSecondary, neonPrimary, successColor)
            else -> listOf(neonSecondary, neonPrimary, neonSecondary)
        }
    }
    val areaColors = remember(phase, neonPrimary, neonSecondary, errorColor, warningColor, successColor) {
        when (phase) {
            WritePhase.FAILED -> listOf(errorColor.copy(alpha = 0.28f), Color.Transparent)
            WritePhase.CANCELLED -> listOf(warningColor.copy(alpha = 0.26f), Color.Transparent)
            WritePhase.COMPLETED -> listOf(
                successColor.copy(alpha = 0.26f),
                neonPrimary.copy(alpha = 0.12f),
                Color.Transparent
            )
            else -> listOf(
                neonPrimary.copy(alpha = 0.28f),
                neonSecondary.copy(alpha = 0.12f),
                Color.Transparent
            )
        }
    }
    val endpointColor = when (phase) {
        WritePhase.FAILED -> errorColor
        WritePhase.CANCELLED -> warningColor
        WritePhase.COMPLETED -> successColor
        else -> neonSecondary
    }
    val glowColor = when (phase) {
        WritePhase.FAILED -> errorColor.copy(alpha = 0.20f)
        WritePhase.CANCELLED -> warningColor.copy(alpha = 0.18f)
        WritePhase.COMPLETED -> successColor.copy(alpha = 0.20f)
        else -> neonSecondary.copy(alpha = (0.26f * glowStrength).coerceIn(0.04f, 0.26f))
    }

    val samples = snapshot.samples
    val peakMbps = maxOf(snapshot.peakBytesPerSec, snapshot.currentBytesPerSec, 1.0) / BYTES_PER_MB
    val axisMaxMbps = niceAxisMaximum(peakMbps * 1.15)
    val axisMaxBytesPerSec = axisMaxMbps * BYTES_PER_MB
    val live = snapshot.isLive && phase == WritePhase.WRITING
    val latestElapsedMs = maxOf(snapshot.elapsedMs, samples.lastOrNull()?.elapsedMs ?: 0L)
    val chartBg = appColors.chartBackground
    val chartGrid = appColors.chartGridLine
    val borderSubtle = appColors.borderSubtle

    Box(
        modifier = modifier
            .clip(ChartBoxShape)
            .background(chartBg)
            .border(1.dp, borderSubtle, ChartBoxShape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 36.dp, end = 4.dp, top = 4.dp, bottom = 15.dp)
                .drawWithCache {
                    val lineBrush = Brush.horizontalGradient(colors = lineColors)
                    val areaBrush = Brush.verticalGradient(colors = areaColors)
                    val lineStroke = Stroke(
                        width = 2.2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                    val glowStroke = Stroke(
                        width = 5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                    val dotOuterRadius = 5.dp.toPx()
                    val dotInnerRadius = 2.8.dp.toPx()

                    onDrawBehind {
                        val w = size.width
                        val h = size.height
                        if (w <= 1f || h <= 1f) return@onDrawBehind

                        // Subtle horizontal and vertical grid lines
                        for (step in 0..4) {
                            val y = h * (step / 4f)
                            drawLine(
                                color = chartGrid,
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1f
                            )
                        }
                        for (step in 1..3) {
                            val x = w * (step / 4f)
                            drawLine(
                                color = chartGrid.copy(alpha = 0.6f),
                                start = Offset(x, 0f),
                                end = Offset(x, h),
                                strokeWidth = 1f
                            )
                        }

                        if (samples.isEmpty()) {
                            drawLine(
                                color = borderSubtle,
                                start = Offset(0f, h),
                                end = Offset(w, h),
                                strokeWidth = 2f
                            )
                            return@onDrawBehind
                        }

                        fun mapX(elapsedMs: Long): Float {
                            val age = latestElapsedMs - elapsedMs
                            return (w - (age.toFloat() / SPEED_CHART_WINDOW_MS.toFloat()) * w)
                                .coerceIn(0f, w)
                        }

                        fun mapY(bytesPerSec: Double): Float {
                            val fraction = (bytesPerSec / axisMaxBytesPerSec).coerceIn(0.0, 1.0)
                            return h - fraction.toFloat() * h
                        }

                        // Skip samples that have scrolled out of the fixed time window.
                        var first = 0
                        while (first < samples.lastIndex &&
                            latestElapsedMs - samples[first].elapsedMs > SPEED_CHART_WINDOW_MS
                        ) {
                            first++
                        }

                        linePath.reset()
                        areaPath.reset()
                        val firstX = mapX(samples[first].elapsedMs)
                        val firstY = mapY(samples[first].bytesPerSec)
                        linePath.moveTo(firstX, firstY)
                        areaPath.moveTo(firstX, h)
                        areaPath.lineTo(firstX, firstY)

                        var prevX = firstX
                        var prevY = firstY
                        for (i in first + 1..samples.lastIndex) {
                            val sample = samples[i]
                            val x = mapX(sample.elapsedMs)
                            val y = mapY(sample.bytesPerSec)
                            val midX = (prevX + x) * 0.5f
                            linePath.cubicTo(midX, prevY, midX, y, x, y)
                            areaPath.cubicTo(midX, prevY, midX, y, x, y)
                            prevX = x
                            prevY = y
                        }

                        // A single sample is drawn as a short flat segment at the current level.
                        if (samples.size == 1) {
                            val startX = (prevX - 6.dp.toPx()).coerceIn(0f, w)
                            linePath.reset()
                            linePath.moveTo(startX, prevY)
                            linePath.lineTo(prevX, prevY)
                            areaPath.reset()
                            areaPath.moveTo(startX, h)
                            areaPath.lineTo(startX, prevY)
                            areaPath.lineTo(prevX, prevY)
                        }

                        areaPath.lineTo(prevX, h)
                        areaPath.close()

                        drawPath(path = areaPath, brush = areaBrush)
                        drawPath(path = linePath, color = glowColor, style = glowStroke)
                        drawPath(path = linePath, brush = lineBrush, style = lineStroke)
                        drawCircle(color = glowColor, radius = dotOuterRadius, center = Offset(prevX, prevY))
                        drawCircle(color = endpointColor, radius = dotInnerRadius, center = Offset(prevX, prevY))
                        drawCircle(
                            color = GlassTextWhite,
                            radius = dotInnerRadius * 0.45f,
                            center = Offset(prevX, prevY)
                        )
                    }
                }
        )

        // Readable speed-axis labels (MB/s)
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .padding(top = 2.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = axisLabel(axisMaxMbps),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = appColors.textSecondary
            )
            Text(
                text = axisLabel(axisMaxMbps / 2.0),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                color = appColors.textSecondary
            )
            Text(
                text = "0",
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                color = appColors.textSecondary
            )
        }

        // Fixed time window labels
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 36.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "-20s",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                color = appColors.textSecondary
            )
            Text(
                text = if (live) "now" else "held",
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = appColors.textSecondary
            )
        }
    }
}

private fun niceAxisMaximum(value: Double): Double {
    if (!value.isFinite() || value <= 1.0) return 1.0
    val magnitude = 10.0.pow(floor(log10(value)))
    return when (val normalized = value / magnitude) {
        in 0.0..1.0 -> magnitude
        in 1.0..2.0 -> 2.0 * magnitude
        in 2.0..5.0 -> 5.0 * magnitude
        else -> 10.0 * magnitude
    }
}

private fun axisLabel(megaBytesPerSecond: Double): String =
    if (megaBytesPerSecond >= 10.0) {
        megaBytesPerSecond.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", megaBytesPerSecond)
    }

@Composable
private fun TelemetryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start
) {
    val appColors = LocalAppColors.current
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment
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
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = appColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun StatusPill(text: String, color: Color) {
    val appColors = LocalAppColors.current
    val pillBg = if (appColors.isDark) color.copy(alpha = 0.22f) else color.copy(alpha = 0.14f)
    val pillBorder = if (appColors.isDark) color.copy(alpha = 0.55f) else color.copy(alpha = 0.40f)
    val pillText = if (appColors.isDark) GlassTextWhite else lerp(color, appColors.textPrimary, 0.35f)
    Surface(
        color = pillBg,
        shape = PillShape,
        border = BorderStroke(0.75.dp, pillBorder)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = pillText,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
