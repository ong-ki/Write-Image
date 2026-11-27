package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usbbootable.creator.core.writer.TransferSpeedSnapshot
import com.usbbootable.creator.core.writer.WritePhase
import com.usbbootable.creator.ui.GuidedStepText
import com.usbbootable.creator.ui.theme.GlassCardShape14
import com.usbbootable.creator.ui.theme.GlassTextWhite
import com.usbbootable.creator.ui.theme.LocalAppColors
import com.usbbootable.creator.ui.theme.LocalNeonTheme
import com.usbbootable.creator.ui.theme.adaptAccentForSurface
import com.usbbootable.creator.ui.theme.effectivePrimaryColor
import com.usbbootable.creator.ui.theme.effectiveSecondaryColor
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

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
    val appColors = LocalAppColors.current
    val isReady = canCreate && !isFlashing
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val guideColor = when {
        guideError -> MaterialTheme.colorScheme.error
        isReady -> onAccent
        else -> MaterialTheme.colorScheme.primary
    }

    AppPrimaryButton(
        text = "WRITE TO USB",
        onClick = onCreateClick,
        enabled = canCreate,
        isLoading = isFlashing,
        loadingText = "WRITING IN PROGRESS",
        icon = Icons.Rounded.Bolt,
        labelContent = {
            GuidedStepText(
                text = "WRITE TO USB",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                defaultColor = if (isReady) onAccent else appColors.textDisabled,
                guideActive = guideActive,
                guideColor = guideColor,
                minAlpha = 0.45f
            )
        }
    )
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
internal fun TransferSpeedChart(
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
            .clip(GlassCardShape14)
            .background(chartBg)
            .border(1.dp, borderSubtle, GlassCardShape14)
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
    return when (value / magnitude) {
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
