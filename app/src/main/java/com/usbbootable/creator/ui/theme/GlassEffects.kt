package com.usbbootable.creator.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usbbootable.creator.core.storage.NeonThemeConfig

object GlassMotion {
    const val TAB_DURATION_MS = 180
    const val PRESS_DURATION_MS = 90
    const val DIALOG_DURATION_MS = 160
    const val STATUS_DURATION_MS = 200
    const val PROGRESS_DURATION_MS = 180
}

val GlassDialogShape: Shape = RoundedCornerShape(22.dp)
val GlassSheetShape: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
val GlassCardShape14: Shape = RoundedCornerShape(14.dp)
val GlassCardShape16: Shape = RoundedCornerShape(16.dp)
val GlassCardShape20: Shape = RoundedCornerShape(20.dp)

@Composable
fun glassBarSurfaceColor(): Color = LocalAppColors.current.barSurface

@Composable
fun glassDialogContainerColor(): Color = LocalAppColors.current.dialogSurface

@Composable
fun glassScrimColor(): Color = LocalAppColors.current.scrim

private val DefaultDarkGlassBorder = BorderStroke(1.dp, GlassBorderSubtle)
private val DefaultLightGlassBorder = BorderStroke(1.dp, LightGlassBorderSubtle)

/**
 * Respects Android's system animator scale setting so animations snap cleanly when disabled
 * by accessibility or developer settings.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        } catch (_: Exception) {
            true
        }
    }
}

/**
 * Clean theme-aware backdrop (dark navy in Dark mode, crisp cool-white in Light mode) with
 * optional subtle radial ambient glow driven by [LocalNeonTheme].
 */
@Composable
fun GlassBackdropBox(
    modifier: Modifier = Modifier,
    neonTheme: NeonThemeConfig = LocalNeonTheme.current,
    content: @Composable BoxScope.() -> Unit
) {
    val appColors = LocalAppColors.current
    val showBgGlow = neonTheme.neonEnabled && neonTheme.backgroundGlowEnabled && neonTheme.glowIntensity > 0.01f
    val primaryGlow = neonTheme.effectivePrimaryColor
    val secondaryGlow = neonTheme.effectiveSecondaryColor
    val glowAlpha = if (appColors.isDark) {
        (0.16f * neonTheme.glowIntensity).coerceIn(0f, 0.20f)
    } else {
        (0.10f * neonTheme.glowIntensity).coerceIn(0f, 0.14f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(appColors.backgroundDeep)
            .drawWithCache {
                if (!showBgGlow) {
                    onDrawBehind {}
                } else {
                    val primaryCenter = Offset(size.width * 0.16f, size.height * 0.08f)
                    val primaryRadius = size.maxDimension * 0.45f
                    val primaryBrush = Brush.radialGradient(
                        colors = listOf(primaryGlow.copy(alpha = glowAlpha), Color.Transparent),
                        center = primaryCenter,
                        radius = primaryRadius
                    )
                    val secondaryCenter = Offset(size.width * 0.86f, size.height * 0.84f)
                    val secondaryRadius = size.maxDimension * 0.42f
                    val secondaryBrush = Brush.radialGradient(
                        colors = listOf(secondaryGlow.copy(alpha = glowAlpha * 0.72f), Color.Transparent),
                        center = secondaryCenter,
                        radius = secondaryRadius
                    )
                    onDrawBehind {
                        drawCircle(brush = primaryBrush, radius = primaryRadius, center = primaryCenter)
                        drawCircle(brush = secondaryBrush, radius = secondaryRadius, center = secondaryCenter)
                    }
                }
            },
        content = content
    )
}

/**
 * High-contrast, flat-filled subtle glass card surface that adapts cleanly between Dark and Light
 * themes with crisp borders and explicit LocalContentColor for sharp text readability.
 */
@Composable
fun GlassCardSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassCardShape16,
    accentBorderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    neonTheme: NeonThemeConfig = LocalNeonTheme.current,
    content: @Composable () -> Unit
) {
    val appColors = LocalAppColors.current
    val border = remember(
        appColors.isDark,
        appColors.borderSubtle,
        accentBorderColor,
        borderWidth,
        neonTheme.neonEnabled,
        neonTheme.primaryColorArgb,
        neonTheme.glowIntensity
    ) {
        when {
            accentBorderColor != null -> BorderStroke(borderWidth, accentBorderColor)
            neonTheme.neonEnabled -> {
                val blend = if (appColors.isDark) {
                    (0.22f + 0.45f * neonTheme.glowIntensity).coerceIn(0f, 0.85f)
                } else {
                    (0.18f + 0.30f * neonTheme.glowIntensity).coerceIn(0f, 0.52f)
                }
                val tinted = lerp(
                    appColors.borderSubtle,
                    adaptAccentForSurface(neonTheme.effectivePrimaryColor, appColors.isDark),
                    blend
                )
                BorderStroke(borderWidth, tinted)
            }
            borderWidth == 1.dp -> if (appColors.isDark) DefaultDarkGlassBorder else DefaultLightGlassBorder
            else -> BorderStroke(borderWidth, appColors.borderSubtle)
        }
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = appColors.surfaceCard,
        contentColor = appColors.textPrimary,
        border = border,
        content = content
    )
}

/**
 * GPU-only subtle scale animation on press; does not delay clicks or trigger layout passes.
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f
): Modifier = composed {
    val motionEnabled = rememberMotionEnabled()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && motionEnabled) pressedScale else 1f,
        animationSpec = tween(
            durationMillis = if (motionEnabled) GlassMotion.PRESS_DURATION_MS else 0,
            easing = FastOutSlowInEasing
        ),
        label = "button_press_scale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Crisp fade-only entry animation for dialogs (avoids subpixel text scaling blur on physical displays).
 */
fun Modifier.glassDialogEnter(): Modifier = composed {
    val motionEnabled = rememberMotionEnabled()
    val alphaAnim = remember { Animatable(if (motionEnabled) 0f else 1f) }

    LaunchedEffect(motionEnabled) {
        if (motionEnabled) {
            alphaAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(GlassMotion.DIALOG_DURATION_MS, easing = FastOutSlowInEasing)
            )
        } else {
            alphaAnim.snapTo(1f)
        }
    }

    graphicsLayer {
        alpha = alphaAnim.value
    }
}
