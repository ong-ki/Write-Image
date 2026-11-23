package com.usbbootable.creator.core.storage

enum class ProgressBarStyle(
    val label: String,
    val description: String
) {
    CLASSIC("Classic", "Clean traditional progress bar"),
    NEON_GLOW("Neon Glow", "Glowing bar with neon lighting"),
    GRADIENT("Gradient", "Smooth multi-tone gradient fill"),
    SEGMENTED("Segmented", "Divided into crisp segments");

    companion object {
        fun fromName(name: String?): ProgressBarStyle =
            entries.firstOrNull { it.name == name } ?: GRADIENT
    }
}

data class NeonColorPreset(
    val id: String,
    val label: String,
    val primaryArgb: Long,
    val secondaryArgb: Long
)

object NeonColorPresets {
    val CYBER_BLUE = NeonColorPreset("cyber_blue", "Cyber Blue", 0xFF3B82F6L, 0xFF22D3EEL)
    val ELECTRIC_PURPLE = NeonColorPreset("electric_purple", "Electric Purple", 0xFFA855F7L, 0xFF6366F1L)
    val NEON_GREEN = NeonColorPreset("neon_green", "Neon Green", 0xFF10B981L, 0xFF34D399L)
    val HOT_PINK = NeonColorPreset("hot_pink", "Hot Pink", 0xFFEC4899L, 0xFFF472B6L)
    val PLASMA_ORANGE = NeonColorPreset("plasma_orange", "Plasma Orange", 0xFFF97316L, 0xFFFBBF24L)
    val CYAN_GLOW = NeonColorPreset("cyan_glow", "Cyan Glow", 0xFF06B6D4L, 0xFF38BDF8L)

    val all: List<NeonColorPreset> = listOf(
        CYBER_BLUE,
        ELECTRIC_PURPLE,
        NEON_GREEN,
        HOT_PINK,
        PLASMA_ORANGE,
        CYAN_GLOW
    )
}

data class NeonThemeConfig(
    val neonEnabled: Boolean = true,
    val primaryColorArgb: Long = NeonColorPresets.CYBER_BLUE.primaryArgb,
    val secondaryColorArgb: Long = NeonColorPresets.CYBER_BLUE.secondaryArgb,
    val glowIntensity: Float = 0.65f,
    val glowRadiusDp: Float = 8f,
    val backgroundGlowEnabled: Boolean = true
) {
    fun normalized(): NeonThemeConfig {
        val safeIntensity = if (glowIntensity.isFinite()) glowIntensity.coerceIn(0f, 1f) else DEFAULT.glowIntensity
        val safeRadius = if (glowRadiusDp.isFinite()) glowRadiusDp.coerceIn(2f, 20f) else DEFAULT.glowRadiusDp
        return copy(
            primaryColorArgb = (primaryColorArgb and 0x00FFFFFFL) or 0xFF000000L,
            secondaryColorArgb = (secondaryColorArgb and 0x00FFFFFFL) or 0xFF000000L,
            glowIntensity = safeIntensity,
            glowRadiusDp = safeRadius
        )
    }

    fun withPreset(preset: NeonColorPreset): NeonThemeConfig = copy(
        primaryColorArgb = preset.primaryArgb,
        secondaryColorArgb = preset.secondaryArgb
    )

    companion object {
        val DEFAULT = NeonThemeConfig()
    }
}

data class AppearanceCustomization(
    val progressBarStyle: ProgressBarStyle = ProgressBarStyle.GRADIENT,
    val neonTheme: NeonThemeConfig = NeonThemeConfig.DEFAULT
) {
    fun normalized(): AppearanceCustomization = copy(
        neonTheme = neonTheme.normalized()
    )

    companion object {
        val DEFAULT = AppearanceCustomization()
    }
}
