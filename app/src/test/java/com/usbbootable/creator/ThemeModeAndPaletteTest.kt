package com.usbbootable.creator

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.usbbootable.creator.core.storage.NeonColorPresets
import com.usbbootable.creator.core.storage.NeonThemeConfig
import com.usbbootable.creator.ui.screens.shiftHueAccent
import com.usbbootable.creator.ui.theme.DarkAppSurfaceColors
import com.usbbootable.creator.ui.theme.DarkSurface
import com.usbbootable.creator.ui.theme.GlassBackgroundDeep
import com.usbbootable.creator.ui.theme.GlassTextWhite
import com.usbbootable.creator.ui.theme.LightAppSurfaceColors
import com.usbbootable.creator.ui.theme.LightTextPrimary
import com.usbbootable.creator.ui.theme.ThemeMode
import com.usbbootable.creator.ui.theme.adaptAccentForSurface
import com.usbbootable.creator.ui.theme.buildAppColorScheme
import com.usbbootable.creator.ui.theme.contrastingContentColor
import com.usbbootable.creator.ui.theme.resolveDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeAndPaletteTest {

    @Test
    fun resolveDarkThemeHonorsLightDarkAndSystemModes() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = true))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = true))
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
    }

    @Test
    fun lightColorSchemeProvidesGenuineLightSurfacesAndReadableDarkTextAcrossAllNeonConfigs() {
        val configs = listOf(
            NeonThemeConfig.DEFAULT,
            NeonThemeConfig.DEFAULT.copy(neonEnabled = false),
            NeonThemeConfig(neonEnabled = true, primaryColorArgb = 0xFFFFFF00L, secondaryColorArgb = 0xFF00FFFFL)
        ) + NeonColorPresets.all.map { NeonThemeConfig.DEFAULT.withPreset(it) }

        for (config in configs) {
            val scheme = buildAppColorScheme(darkTheme = false, neon = config)
            assertTrue("Light background must be bright", scheme.background.luminance() > 0.85f)
            assertTrue("Light surface must be bright", scheme.surface.luminance() > 0.90f)
            assertTrue("Light onBackground must be dark", scheme.onBackground.luminance() < 0.08f)
            assertTrue("Light onSurface must be dark", scheme.onSurface.luminance() < 0.08f)
            assertTrue("Light onSurfaceVariant must be dark", scheme.onSurfaceVariant.luminance() < 0.25f)
            assertTrue("Light primaryContainer must be bright", scheme.primaryContainer.luminance() > 0.65f)
            assertTrue("Light onPrimaryContainer must be dark", scheme.onPrimaryContainer.luminance() < 0.25f)
            assertTrue("Light secondaryContainer must be bright", scheme.secondaryContainer.luminance() > 0.65f)
            assertTrue("Light onSecondaryContainer must be dark", scheme.onSecondaryContainer.luminance() < 0.25f)
            assertTrue("Light errorContainer must be bright", scheme.errorContainer.luminance() > 0.70f)
            assertTrue("Light onErrorContainer must be dark", scheme.onErrorContainer.luminance() < 0.20f)
        }
    }

    @Test
    fun darkColorSchemePreservesExistingDarkNavyAppearance() {
        val scheme = buildAppColorScheme(darkTheme = true, neon = NeonThemeConfig.DEFAULT)
        assertEquals(GlassBackgroundDeep, scheme.background)
        assertEquals(DarkSurface, scheme.surface)
        assertEquals(GlassTextWhite, scheme.onBackground)
        assertEquals(GlassTextWhite, scheme.onSurface)
        assertEquals(GlassTextWhite, scheme.onPrimaryContainer)
        assertEquals(GlassTextWhite, scheme.onSecondaryContainer)
    }

    @Test
    fun appSurfaceColorPalettesSeparateLightAndDarkCleanly() {
        assertFalse(LightAppSurfaceColors.isDark)
        assertTrue(LightAppSurfaceColors.backgroundDeep.luminance() > 0.85f)
        assertTrue(LightAppSurfaceColors.surfaceCard.luminance() > 0.95f)
        assertTrue(LightAppSurfaceColors.dialogSurface.luminance() > 0.95f)
        assertTrue(LightAppSurfaceColors.barSurface.luminance() > 0.95f)
        assertTrue(LightAppSurfaceColors.textPrimary.luminance() < 0.05f)
        assertTrue(LightAppSurfaceColors.chartBackground.luminance() > 0.90f)

        assertTrue(DarkAppSurfaceColors.isDark)
        assertEquals(GlassBackgroundDeep, DarkAppSurfaceColors.backgroundDeep)
        assertEquals(GlassTextWhite, DarkAppSurfaceColors.textPrimary)
    }

    @Test
    fun brightNeonAccentsAdaptForLightReadabilityAndContentContrast() {
        val brightYellow = Color(0xFFFFFF00)
        assertEquals(LightTextPrimary, contrastingContentColor(brightYellow))
        assertEquals(GlassTextWhite, contrastingContentColor(Color(0xFF2563EB)))

        val adaptedLight = adaptAccentForSurface(brightYellow, darkTheme = false)
        assertTrue("Bright yellow must be darkened on light surfaces", adaptedLight.luminance() < 0.35f)
        assertEquals(brightYellow, adaptAccentForSurface(brightYellow, darkTheme = true))
    }

    @Test
    fun gradientThirdStopProvidesDistinctHueForEveryNeonPreset() {
        for (preset in NeonColorPresets.all) {
            val secondary = Color(preset.secondaryArgb)
            val shifted = shiftHueAccent(secondary, 68f)
            val channelDiff = kotlin.math.abs(shifted.red - secondary.red) +
                kotlin.math.abs(shifted.green - secondary.green) +
                kotlin.math.abs(shifted.blue - secondary.blue)
            assertTrue(
                "Gradient stop for ${preset.label} must visually diverge from secondary",
                channelDiff > 0.25f
            )
        }
    }
}
