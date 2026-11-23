package com.usbbootable.creator

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.usbbootable.creator.core.storage.AppSettings
import com.usbbootable.creator.core.storage.AppearanceCustomization
import com.usbbootable.creator.core.storage.CustomizationDataStore
import com.usbbootable.creator.core.storage.NeonColorPresets
import com.usbbootable.creator.core.storage.NeonThemeConfig
import com.usbbootable.creator.core.storage.ProgressBarStyle
import com.usbbootable.creator.core.storage.WriteBufferOption
import com.usbbootable.creator.core.writer.WindowsBypassOptions
import com.usbbootable.creator.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomizationDataStoreTest {

    @Test
    fun appSettingsDefaultsAreFactoryValues() {
        val defaults = AppSettings()
        assertEquals(ThemeMode.SYSTEM, defaults.themeMode)
        assertEquals(WriteBufferOption.AUTO, defaults.bufferOption)
        assertTrue(defaults.verifyAfterWrite)
        assertFalse(defaults.windowsBypassOptions.hasAnyEnabled)
    }

    @Test
    fun appearanceDefaultsAreFactoryValues() {
        val defaults = AppearanceCustomization.DEFAULT
        assertEquals(ProgressBarStyle.GRADIENT, defaults.progressBarStyle)
        assertTrue(defaults.neonTheme.neonEnabled)
        assertEquals(NeonColorPresets.CYBER_BLUE.primaryArgb, defaults.neonTheme.primaryColorArgb)
        assertEquals(NeonColorPresets.CYBER_BLUE.secondaryArgb, defaults.neonTheme.secondaryColorArgb)
        assertEquals(0.65f, defaults.neonTheme.glowIntensity, 0.0001f)
        assertEquals(8f, defaults.neonTheme.glowRadiusDp, 0.0001f)
        assertTrue(defaults.neonTheme.backgroundGlowEnabled)
    }

    @Test
    fun progressBarStylesAndNeonPresetsMatchRequirements() {
        assertEquals(4, ProgressBarStyle.entries.size)
        assertEquals(
            listOf(
                ProgressBarStyle.CLASSIC,
                ProgressBarStyle.NEON_GLOW,
                ProgressBarStyle.GRADIENT,
                ProgressBarStyle.SEGMENTED
            ),
            ProgressBarStyle.entries
        )

        for (style in ProgressBarStyle.entries) {
            assertEquals(style, ProgressBarStyle.fromName(style.name))
        }
        assertEquals(ProgressBarStyle.GRADIENT, ProgressBarStyle.fromName(null))
        assertEquals(ProgressBarStyle.GRADIENT, ProgressBarStyle.fromName("UNKNOWN_STYLE"))

        assertEquals(6, NeonColorPresets.all.size)
        assertEquals(
            listOf("Cyber Blue", "Electric Purple", "Neon Green", "Hot Pink", "Plasma Orange", "Cyan Glow"),
            NeonColorPresets.all.map { it.label }
        )
        for (preset in NeonColorPresets.all) {
            assertTrue((preset.primaryArgb ushr 24) == 0xFFL)
            assertTrue((preset.secondaryArgb ushr 24) == 0xFFL)
        }
    }

    @Test
    fun neonThemeConfigNormalizationClampsBoundsAndOpaqueAlpha() {
        val raw = NeonThemeConfig(
            neonEnabled = true,
            primaryColorArgb = 0x00123456L,
            secondaryColorArgb = 0x10ABCDEF,
            glowIntensity = 2.5f,
            glowRadiusDp = 100f,
            backgroundGlowEnabled = false
        )
        val clamped = raw.normalized()
        assertEquals(0xFF123456L, clamped.primaryColorArgb)
        assertEquals(0xFFABCDEFL, clamped.secondaryColorArgb)
        assertEquals(1f, clamped.glowIntensity, 0.0001f)
        assertEquals(20f, clamped.glowRadiusDp, 0.0001f)
        assertFalse(clamped.backgroundGlowEnabled)

        val low = NeonThemeConfig(
            glowIntensity = -0.4f,
            glowRadiusDp = 0.2f
        ).normalized()
        assertEquals(0f, low.glowIntensity, 0.0001f)
        assertEquals(2f, low.glowRadiusDp, 0.0001f)

        val nonFinite = NeonThemeConfig(
            primaryColorArgb = -1L,
            secondaryColorArgb = 0x123456789AL,
            glowIntensity = Float.NaN,
            glowRadiusDp = Float.POSITIVE_INFINITY
        ).normalized()
        assertEquals(0xFFFFFFFFL, nonFinite.primaryColorArgb)
        assertEquals(0xFF56789AL, nonFinite.secondaryColorArgb)
        assertEquals(NeonThemeConfig.DEFAULT.glowIntensity, nonFinite.glowIntensity, 0.0001f)
        assertEquals(NeonThemeConfig.DEFAULT.glowRadiusDp, nonFinite.glowRadiusDp, 0.0001f)
    }

    @Test
    fun dataStorePersistsAllProgressBarStylesAndNeonCustomizationAcrossRestarts() = runBlocking {
        val tmpDir = Files.createTempDirectory("ds_customization_test_").toFile()
        val storeFile = File(tmpDir, "ui_customization_datastore.preferences_pb")
        try {
            val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val ds1 = PreferenceDataStoreFactory.create(scope = scope1) { storeFile }
                val repo1 = CustomizationDataStore(ds1)

                // Initial defaults
                assertEquals(AppearanceCustomization.DEFAULT, repo1.customization.first())

                // Verify all 4 progress bar styles persist
                for (style in ProgressBarStyle.entries) {
                    repo1.saveProgressBarStyle(style)
                    assertEquals(style, repo1.customization.first().progressBarStyle)
                }

                // Save full custom neon configuration
                val custom = AppearanceCustomization(
                    progressBarStyle = ProgressBarStyle.SEGMENTED,
                    neonTheme = NeonThemeConfig(
                        neonEnabled = false,
                        primaryColorArgb = 0xFFEC4899L,
                        secondaryColorArgb = 0xFFF97316L,
                        glowIntensity = 0.85f,
                        glowRadiusDp = 14f,
                        backgroundGlowEnabled = false
                    )
                )
                repo1.saveCustomization(custom)
                assertEquals(custom, repo1.customization.first())
            } finally {
                scope1.cancel()
            }

            // Simulate app restart with a brand new DataStore instance pointing at the same file
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val ds2 = PreferenceDataStoreFactory.create(scope = scope2) { storeFile }
                val repo2 = CustomizationDataStore(ds2)
                val restored = repo2.customization.first()

                assertEquals(ProgressBarStyle.SEGMENTED, restored.progressBarStyle)
                assertFalse(restored.neonTheme.neonEnabled)
                assertEquals(0xFFEC4899L, restored.neonTheme.primaryColorArgb)
                assertEquals(0xFFF97316L, restored.neonTheme.secondaryColorArgb)
                assertEquals(0.85f, restored.neonTheme.glowIntensity, 0.0001f)
                assertEquals(14f, restored.neonTheme.glowRadiusDp, 0.0001f)
                assertFalse(restored.neonTheme.backgroundGlowEnabled)

                // Reset defaults
                repo2.resetDefaults()
                assertEquals(AppearanceCustomization.DEFAULT, repo2.customization.first())
            } finally {
                scope2.cancel()
            }

            // Defaults written by resetDefaults() must survive another app restart.
            val scope3 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val ds3 = PreferenceDataStoreFactory.create(scope = scope3) { storeFile }
                val repo3 = CustomizationDataStore(ds3)
                assertEquals(AppearanceCustomization.DEFAULT, repo3.customization.first())
            } finally {
                scope3.cancel()
            }
        } finally {
            storeFile.delete()
            tmpDir.deleteRecursively()
        }
    }
}
