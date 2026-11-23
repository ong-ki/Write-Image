package com.usbbootable.creator.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.customizationPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ui_customization_datastore",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * Persistent Android DataStore repository for Progress Bar Style and Neon Theme Customization.
 */
class CustomizationDataStore(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.customizationPreferencesDataStore)

    val customization: Flow<AppearanceCustomization> = dataStore.data
        .catch { err ->
            if (err is IOException) emit(emptyPreferences()) else throw err
        }
        .map { prefs ->
            val style = ProgressBarStyle.fromName(prefs[KEY_PROGRESS_BAR_STYLE])
            val neon = NeonThemeConfig(
                neonEnabled = prefs[KEY_NEON_ENABLED] ?: NeonThemeConfig.DEFAULT.neonEnabled,
                primaryColorArgb = prefs[KEY_NEON_PRIMARY_ARGB] ?: NeonThemeConfig.DEFAULT.primaryColorArgb,
                secondaryColorArgb = prefs[KEY_NEON_SECONDARY_ARGB] ?: NeonThemeConfig.DEFAULT.secondaryColorArgb,
                glowIntensity = prefs[KEY_GLOW_INTENSITY] ?: NeonThemeConfig.DEFAULT.glowIntensity,
                glowRadiusDp = prefs[KEY_GLOW_RADIUS_DP] ?: NeonThemeConfig.DEFAULT.glowRadiusDp,
                backgroundGlowEnabled = prefs[KEY_BG_GLOW_ENABLED] ?: NeonThemeConfig.DEFAULT.backgroundGlowEnabled
            ).normalized()
            AppearanceCustomization(
                progressBarStyle = style,
                neonTheme = neon
            )
        }

    suspend fun saveProgressBarStyle(style: ProgressBarStyle) {
        dataStore.edit { prefs ->
            prefs[KEY_PROGRESS_BAR_STYLE] = style.name
        }
    }

    suspend fun saveCustomization(customization: AppearanceCustomization) {
        val clean = customization.normalized()
        dataStore.edit { prefs ->
            prefs[KEY_PROGRESS_BAR_STYLE] = clean.progressBarStyle.name
            prefs[KEY_NEON_ENABLED] = clean.neonTheme.neonEnabled
            prefs[KEY_NEON_PRIMARY_ARGB] = clean.neonTheme.primaryColorArgb
            prefs[KEY_NEON_SECONDARY_ARGB] = clean.neonTheme.secondaryColorArgb
            prefs[KEY_GLOW_INTENSITY] = clean.neonTheme.glowIntensity
            prefs[KEY_GLOW_RADIUS_DP] = clean.neonTheme.glowRadiusDp
            prefs[KEY_BG_GLOW_ENABLED] = clean.neonTheme.backgroundGlowEnabled
        }
    }

    suspend fun resetDefaults() {
        saveCustomization(AppearanceCustomization.DEFAULT)
    }

    companion object {
        private val KEY_PROGRESS_BAR_STYLE = stringPreferencesKey("progress_bar_style")
        private val KEY_NEON_ENABLED = booleanPreferencesKey("neon_enabled")
        private val KEY_NEON_PRIMARY_ARGB = longPreferencesKey("neon_primary_argb")
        private val KEY_NEON_SECONDARY_ARGB = longPreferencesKey("neon_secondary_argb")
        private val KEY_GLOW_INTENSITY = floatPreferencesKey("neon_glow_intensity")
        private val KEY_GLOW_RADIUS_DP = floatPreferencesKey("neon_glow_radius_dp")
        private val KEY_BG_GLOW_ENABLED = booleanPreferencesKey("neon_bg_glow_enabled")
    }
}
