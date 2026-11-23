package com.usbbootable.creator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.usbbootable.creator.core.storage.AppearanceCustomization
import com.usbbootable.creator.core.storage.NeonThemeConfig
import com.usbbootable.creator.core.storage.ProgressBarStyle

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Immutable
data class AppSurfaceColors(
    val isDark: Boolean,
    val backgroundDeep: Color,
    val surfaceCard: Color,
    val surfaceInner: Color,
    val barSurface: Color,
    val dialogSurface: Color,
    val scrim: Color,
    val borderSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val chartBackground: Color,
    val chartGridLine: Color,
    val disabledCreateContainer: Color
)

val DarkAppSurfaceColors = AppSurfaceColors(
    isDark = true,
    backgroundDeep = GlassBackgroundDeep,
    surfaceCard = GlassSurfaceFill,
    surfaceInner = GlassSurfaceInner,
    barSurface = GlassSheetBackground,
    dialogSurface = Color(0xFC0D182F),
    scrim = Color(0xB3040812),
    borderSubtle = GlassBorderSubtle,
    textPrimary = GlassTextWhite,
    textSecondary = GlassTextSoftGray,
    textDisabled = GlassTextDisabled,
    chartBackground = DarkChartBackground,
    chartGridLine = DarkChartGridLine,
    disabledCreateContainer = DarkDisabledCreateContainer
)

val LightAppSurfaceColors = AppSurfaceColors(
    isDark = false,
    backgroundDeep = LightGlassBackground,
    surfaceCard = LightGlassSurfaceFill,
    surfaceInner = LightGlassSurfaceInner,
    barSurface = LightGlassSheetBackground,
    dialogSurface = LightGlassDialogContainer,
    scrim = LightGlassScrim,
    borderSubtle = LightGlassBorderSubtle,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    textDisabled = LightTextDisabled,
    chartBackground = LightChartBackground,
    chartGridLine = LightChartGridLine,
    disabledCreateContainer = LightDisabledCreateContainer
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer
)

val LocalIsDarkTheme = staticCompositionLocalOf { true }
val LocalAppColors = staticCompositionLocalOf { DarkAppSurfaceColors }
val LocalNeonTheme = staticCompositionLocalOf { NeonThemeConfig.DEFAULT }
val LocalProgressBarStyle = staticCompositionLocalOf { ProgressBarStyle.GRADIENT }

val NeonThemeConfig.effectivePrimaryColor: Color
    get() = if (neonEnabled) Color(primaryColorArgb) else ElectricBlueBright

val NeonThemeConfig.effectiveSecondaryColor: Color
    get() = if (neonEnabled) Color(secondaryColorArgb) else CyanAccentBright

fun resolveDarkTheme(themeMode: ThemeMode, systemInDarkTheme: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Picks white or deep slate ink so text/icons remain legible over any customized accent color.
 */
fun contrastingContentColor(background: Color): Color =
    if (background.luminance() > 0.48f) LightTextPrimary else GlassTextWhite

/**
 * Keeps custom neon accents vibrant in dark mode while deepening overly bright neon hues
 * (such as cyan or yellow) just enough to remain readable on light surfaces.
 */
fun adaptAccentForSurface(color: Color, darkTheme: Boolean): Color {
    if (darkTheme) return color
    val lum = color.luminance()
    return when {
        lum > 0.55f -> lerp(color, LightTextPrimary, 0.46f)
        lum > 0.34f -> lerp(color, LightTextPrimary, 0.30f)
        else -> color
    }
}

internal fun buildAppColorScheme(
    darkTheme: Boolean,
    neon: NeonThemeConfig
): ColorScheme {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    return base.withNeonAccents(neon = neon, darkTheme = darkTheme)
}

private fun ColorScheme.withNeonAccents(
    neon: NeonThemeConfig,
    darkTheme: Boolean
): ColorScheme {
    val rawPrimary = if (neon.neonEnabled) Color(neon.primaryColorArgb) else {
        if (darkTheme) ElectricBlueBright else ElectricBlue
    }
    val rawSecondary = if (neon.neonEnabled) Color(neon.secondaryColorArgb) else {
        if (darkTheme) CyanAccentBright else LightSecondary
    }
    val primaryAccent = adaptAccentForSurface(rawPrimary, darkTheme)
    val secondaryAccent = adaptAccentForSurface(rawSecondary, darkTheme)

    return if (darkTheme) {
        copy(
            primary = primaryAccent,
            onPrimary = contrastingContentColor(primaryAccent),
            primaryContainer = primaryAccent.copy(alpha = 0.28f),
            onPrimaryContainer = GlassTextWhite,
            secondary = secondaryAccent,
            onSecondary = contrastingContentColor(secondaryAccent),
            tertiary = secondaryAccent,
            secondaryContainer = secondaryAccent.copy(alpha = 0.24f),
            onSecondaryContainer = GlassTextWhite,
            outline = if (neon.neonEnabled) primaryAccent.copy(alpha = 0.45f) else GlassBorderSubtle
        )
    } else {
        val lightPrimaryContainer = lerp(Color.White, primaryAccent, 0.15f)
        val lightSecondaryContainer = lerp(Color.White, secondaryAccent, 0.15f)
        copy(
            primary = primaryAccent,
            onPrimary = contrastingContentColor(primaryAccent),
            primaryContainer = lightPrimaryContainer,
            onPrimaryContainer = lerp(primaryAccent, LightTextPrimary, 0.52f),
            secondary = secondaryAccent,
            onSecondary = contrastingContentColor(secondaryAccent),
            tertiary = secondaryAccent,
            secondaryContainer = lightSecondaryContainer,
            onSecondaryContainer = lerp(secondaryAccent, LightTextPrimary, 0.52f),
            outline = if (neon.neonEnabled) {
                lerp(LightGlassBorderSubtle, primaryAccent, 0.30f)
            } else {
                LightGlassBorderSubtle
            }
        )
    }
}

@Composable
fun statusSuccessColor(): Color =
    if (LocalIsDarkTheme.current) StatusSuccessDark else StatusSuccessLight

@Composable
fun statusWarningColor(): Color =
    if (LocalIsDarkTheme.current) StatusWarningDark else StatusWarningLight

@Composable
fun UsbBootableCreatorTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    customization: AppearanceCustomization = AppearanceCustomization.DEFAULT,
    content: @Composable () -> Unit
) {
    val darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme())
    val normalizedCustomization = remember(customization) { customization.normalized() }
    val appColors = if (darkTheme) DarkAppSurfaceColors else LightAppSurfaceColors
    val colorScheme = remember(darkTheme, normalizedCustomization.neonTheme) {
        buildAppColorScheme(darkTheme = darkTheme, neon = normalizedCustomization.neonTheme)
    }

    CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme,
        LocalAppColors provides appColors,
        LocalNeonTheme provides normalizedCustomization.neonTheme,
        LocalProgressBarStyle provides normalizedCustomization.progressBarStyle
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
