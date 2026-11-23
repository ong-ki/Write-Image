package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usbbootable.creator.core.storage.NeonThemeConfig
import com.usbbootable.creator.core.storage.ProgressBarStyle
import com.usbbootable.creator.ui.theme.LocalAppColors
import com.usbbootable.creator.ui.theme.LocalNeonTheme
import com.usbbootable.creator.ui.theme.LocalProgressBarStyle
import com.usbbootable.creator.ui.theme.adaptAccentForSurface
import com.usbbootable.creator.ui.theme.effectivePrimaryColor
import com.usbbootable.creator.ui.theme.effectiveSecondaryColor
import kotlin.math.abs
import kotlin.math.floor

/**
 * Centralized themed progress bar supporting 4 visually distinct styles:
 * - [ProgressBarStyle.CLASSIC]: Clean, traditional solid flat fill in a subtle bordered bar.
 * - [ProgressBarStyle.NEON_GLOW]: Cyber-style recessed rail with radiant multi-layer neon tube,
 *   hot white core filament, and glowing leading energy tip.
 * - [ProgressBarStyle.GRADIENT]: Sleek full-capsule pill with a vivid 3-stop multi-color
 *   spectrum transition and subtle top sheen.
 * - [ProgressBarStyle.SEGMENTED]: Discrete rectangular hardware-meter blocks with clear spacing,
 *   outlined unfilled slots, and progressive partial-block fill.
 */
@Composable
fun StyledProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    style: ProgressBarStyle = LocalProgressBarStyle.current,
    neonTheme: NeonThemeConfig = LocalNeonTheme.current,
    overrideAccentColor: Color? = null,
    trackColor: Color = LocalAppColors.current.surfaceInner,
    height: Dp = 12.dp
) {
    val appColors = LocalAppColors.current
    val clamped = progress.coerceIn(0f, 1f)
    val primary = overrideAccentColor ?: adaptAccentForSurface(neonTheme.effectivePrimaryColor, appColors.isDark)
    val secondary = overrideAccentColor ?: adaptAccentForSurface(neonTheme.effectiveSecondaryColor, appColors.isDark)
    val borderSubtle = appColors.borderSubtle
    val isDark = appColors.isDark

    // Preserve a clearly visible cyber glow for NEON_GLOW style, scaled by user intensity/radius.
    val neonGlowStrength = if (neonTheme.neonEnabled) {
        (0.45f + 0.55f * neonTheme.glowIntensity).coerceIn(0.45f, 1.0f)
    } else {
        0.42f
    }
    val glowRadiusFactor = ((neonTheme.glowRadiusDp - 2f) / 18f).coerceIn(0f, 1f)

    val spectrumColors = remember(primary, secondary, isDark, overrideAccentColor) {
        if (overrideAccentColor != null) {
            listOf(
                lerp(overrideAccentColor, Color.Black, 0.12f),
                overrideAccentColor,
                lerp(overrideAccentColor, Color.White, 0.28f)
            )
        } else {
            val thirdStop = adaptAccentForSurface(shiftHueAccent(secondary, 68f), isDark)
            listOf(primary, secondary, thirdStop)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        when (style) {
            ProgressBarStyle.CLASSIC -> {
                // Traditional flat bar: clean subtle corners, bordered track, single solid fill.
                val classicRadius = CornerRadius(3.dp.toPx().coerceAtMost(h * 0.35f))
                val strokePx = 1.dp.toPx()
                drawRoundRect(
                    color = trackColor,
                    size = size,
                    cornerRadius = classicRadius
                )
                if (clamped > 0f) {
                    val fillW = (w * clamped).coerceIn(classicRadius.x * 1.5f, w)
                    drawRoundRect(
                        color = primary,
                        size = Size(fillW, h),
                        cornerRadius = classicRadius
                    )
                }
                drawRoundRect(
                    color = borderSubtle.copy(alpha = if (isDark) 0.75f else 0.90f),
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size((w - strokePx).coerceAtLeast(0f), (h - strokePx).coerceAtLeast(0f)),
                    cornerRadius = classicRadius,
                    style = Stroke(width = strokePx)
                )
            }

            ProgressBarStyle.NEON_GLOW -> {
                // Cyber-style glowing neon tube centered inside full layout height so outer
                // neon aura renders cleanly without clipping on compact tiles or Home card.
                val tubeHeight = (h * 0.50f).coerceAtLeast(4.dp.toPx().coerceAtMost(h))
                val tubeTop = (h - tubeHeight) / 2f
                val tubeRadius = CornerRadius(tubeHeight / 2f, tubeHeight / 2f)
                val railColor = if (isDark) Color(0xFF0A1224) else trackColor
                val railBorder = primary.copy(alpha = if (isDark) 0.40f else 0.35f)

                // Recessed cyber track rail
                drawRoundRect(
                    color = railColor,
                    topLeft = Offset(0f, tubeTop),
                    size = Size(w, tubeHeight),
                    cornerRadius = tubeRadius
                )
                drawRoundRect(
                    color = railBorder,
                    topLeft = Offset(0f, tubeTop),
                    size = Size(w, tubeHeight),
                    cornerRadius = tubeRadius,
                    style = Stroke(width = 0.85.dp.toPx())
                )

                if (clamped > 0f) {
                    val fillW = (w * clamped).coerceIn(tubeHeight.coerceAtMost(w), w)
                    val maxHaloPad = ((h - tubeHeight) * 0.5f) * (0.65f + 0.35f * glowRadiusFactor)
                    val innerHaloPad = maxHaloPad * 0.52f

                    // Outer soft neon aura
                    drawRoundRect(
                        color = primary.copy(alpha = (0.30f * neonGlowStrength).coerceIn(0.12f, 0.34f)),
                        topLeft = Offset(0f, tubeTop - maxHaloPad),
                        size = Size(fillW, tubeHeight + maxHaloPad * 2f),
                        cornerRadius = CornerRadius((tubeHeight + maxHaloPad * 2f) / 2f)
                    )
                    // Inner vivid cyan/secondary bloom
                    drawRoundRect(
                        color = secondary.copy(alpha = (0.44f * neonGlowStrength).coerceIn(0.18f, 0.48f)),
                        topLeft = Offset(0f, tubeTop - innerHaloPad),
                        size = Size(fillW, tubeHeight + innerHaloPad * 2f),
                        cornerRadius = CornerRadius((tubeHeight + innerHaloPad * 2f) / 2f)
                    )
                    // Core luminous neon tube
                    drawRoundRect(
                        color = lerp(primary, secondary, 0.25f),
                        topLeft = Offset(0f, tubeTop),
                        size = Size(fillW, tubeHeight),
                        cornerRadius = tubeRadius
                    )
                    // Bright white plasma filament inside the tube
                    val coreH = (tubeHeight * 0.36f).coerceAtLeast(1.5f)
                    drawRoundRect(
                        color = Color.White.copy(alpha = if (isDark) 0.82f else 0.72f),
                        topLeft = Offset(tubeHeight * 0.25f, tubeTop + (tubeHeight - coreH) / 2f),
                        size = Size((fillW - tubeHeight * 0.5f).coerceAtLeast(0f), coreH),
                        cornerRadius = CornerRadius(coreH / 2f)
                    )
                    // Glowing leading energy tip beacon
                    val tipX = (fillW - tubeHeight * 0.42f).coerceIn(tubeHeight * 0.42f, w - tubeHeight * 0.42f)
                    val tipCenter = Offset(tipX, h / 2f)
                    drawCircle(
                        color = secondary.copy(alpha = (0.48f * neonGlowStrength).coerceIn(0.20f, 0.55f)),
                        radius = (h * 0.46f).coerceAtLeast(tubeHeight * 0.75f),
                        center = tipCenter
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.92f),
                        radius = (tubeHeight * 0.32f).coerceAtLeast(1.5f),
                        center = tipCenter
                    )
                }
            }

            ProgressBarStyle.GRADIENT -> {
                // Modern sleek full-capsule pill with clearly visible 3-stop spectrum transition.
                val pillRadius = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(
                    color = trackColor,
                    size = size,
                    cornerRadius = pillRadius
                )
                if (clamped > 0f) {
                    val fillW = (w * clamped).coerceIn(h.coerceAtMost(w), w)
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = spectrumColors,
                            startX = 0f,
                            endX = fillW.coerceAtLeast(1f)
                        ),
                        size = Size(fillW, h),
                        cornerRadius = pillRadius
                    )
                    // Subtle modern glass highlight along top edge of filled pill
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.White.copy(alpha = 0.26f), Color.Transparent),
                            startY = 0f,
                            endY = h * 0.58f
                        ),
                        size = Size(fillW, h * 0.58f),
                        cornerRadius = pillRadius
                    )
                }
            }

            ProgressBarStyle.SEGMENTED -> {
                // Separate rectangular hardware-style segments with clear spacing & slot outlines.
                val gapPx = 3.5.dp.toPx()
                val targetBlockPx = 14.dp.toPx()
                val segmentCount = ((w + gapPx) / (targetBlockPx + gapPx)).toInt().coerceIn(8, 14)
                val segWidth = ((w - gapPx * (segmentCount - 1)) / segmentCount).coerceAtLeast(4f)
                val segRadius = CornerRadius(2.dp.toPx().coerceAtMost(h * 0.22f))
                val strokePx = 0.85.dp.toPx()
                val exactFilled = clamped * segmentCount
                val fullSegments = floor(exactFilled).toInt()
                val partialFraction = (exactFilled - fullSegments).coerceIn(0f, 1f)

                for (i in 0 until segmentCount) {
                    val left = i * (segWidth + gapPx)
                    val segColor = if (i == fullSegments - 1 && partialFraction <= 0.02f) {
                        secondary
                    } else {
                        lerp(primary, secondary, (i.toFloat() / (segmentCount - 1).coerceAtLeast(1)) * 0.45f)
                    }
                    when {
                        i < fullSegments -> {
                            drawRoundRect(
                                color = segColor,
                                topLeft = Offset(left, 0f),
                                size = Size(segWidth, h),
                                cornerRadius = segRadius
                            )
                        }
                        i == fullSegments && partialFraction > 0.01f -> {
                            drawRoundRect(
                                color = trackColor,
                                topLeft = Offset(left, 0f),
                                size = Size(segWidth, h),
                                cornerRadius = segRadius
                            )
                            drawRoundRect(
                                color = secondary,
                                topLeft = Offset(left, 0f),
                                size = Size((segWidth * partialFraction).coerceAtLeast(2f), h),
                                cornerRadius = segRadius
                            )
                            drawRoundRect(
                                color = borderSubtle.copy(alpha = 0.65f),
                                topLeft = Offset(left + strokePx / 2f, strokePx / 2f),
                                size = Size((segWidth - strokePx).coerceAtLeast(0f), (h - strokePx).coerceAtLeast(0f)),
                                cornerRadius = segRadius,
                                style = Stroke(width = strokePx)
                            )
                        }
                        else -> {
                            drawRoundRect(
                                color = trackColor,
                                topLeft = Offset(left, 0f),
                                size = Size(segWidth, h),
                                cornerRadius = segRadius
                            )
                            drawRoundRect(
                                color = borderSubtle.copy(alpha = 0.65f),
                                topLeft = Offset(left + strokePx / 2f, strokePx / 2f),
                                size = Size((segWidth - strokePx).coerceAtLeast(0f), (h - strokePx).coerceAtLeast(0f)),
                                cornerRadius = segRadius,
                                style = Stroke(width = strokePx)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Produces a vibrant third hue-shifted accent stop so the Gradient progress style always displays
 * a distinct multi-color transition even when Primary and Secondary are close in hue.
 */
internal fun shiftHueAccent(color: Color, degrees: Float): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta < 1e-5f -> 210f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    val sat = (if (max < 1e-5f) 0.78f else (delta / max).coerceAtLeast(0.72f)).coerceIn(0f, 1f)
    val v = max.coerceIn(0.72f, 1f)
    val shiftedHue = ((hue + degrees) % 360f + 360f) % 360f

    val c = v * sat
    val x = c * (1f - abs((shiftedHue / 60f) % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        shiftedHue < 60f -> Triple(c, x, 0f)
        shiftedHue < 120f -> Triple(x, c, 0f)
        shiftedHue < 180f -> Triple(0f, c, x)
        shiftedHue < 240f -> Triple(0f, x, c)
        shiftedHue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f
    )
}
