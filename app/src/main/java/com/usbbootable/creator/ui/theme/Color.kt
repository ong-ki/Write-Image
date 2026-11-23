package com.usbbootable.creator.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// High-Contrast Dark Minimalist Glass Palette (Solid Dark Navy + Blue & Cyan)
// ============================================================================

// Solid dark navy background layers (no distracting gradients behind text)
val GlassBackgroundDeep = Color(0xFF070D1B)
val GlassBackgroundMid = Color(0xFF0B1326)
val GlassBackgroundElevated = Color(0xFF101C36)

// High-opacity crisp glass surfaces (flat fill for sharp text readability)
val GlassSurfaceFill = Color(0xF2101C36)      // ~95% opaque dark navy
val GlassSurfaceInner = Color(0xFF162544)
val GlassSheetBackground = Color(0xFA0C162C)  // ~98% opaque for bars/dialogs/sheets

// Crisp subtle glass borders
val GlassBorderSubtle = Color(0xFF28406B)
val GlassBorderHighlight = Color(0xFF3A5B94)
val GlassBorderCyanTint = Color(0xFF1E6E8C)

// Primary & Secondary Accents
val ElectricBlue = Color(0xFF2563EB)
val ElectricBlueBright = Color(0xFF3B82F6)
val ElectricBlueDeep = Color(0xFF1D4ED8)
val CyanAccent = Color(0xFF06B6D4)
val CyanAccentBright = Color(0xFF22D3EE)

// Semantic Status Colors
val GlassSoftGreen = Color(0xFF34D399)
val GlassSoftGreenDeep = Color(0xFF10B981)
val GlassSoftRed = Color(0xFFF87171)
val GlassSoftRedDeep = Color(0xFFEF4444)
val GlassSoftAmber = Color(0xFFFBBF24)

// High-Contrast Typography Colors
val GlassTextWhite = Color(0xFFFFFFFF)        // Solid white primary text
val GlassTextSoftGray = Color(0xFFB8C4D9)     // High-contrast secondary text
val GlassTextDisabled = Color(0xFF8A99B5)     // Sufficiently visible disabled text

// Material 3 Dark ColorScheme mappings
val DarkPrimary = ElectricBlueBright
val DarkOnPrimary = GlassTextWhite
val DarkPrimaryContainer = Color(0xFF1B3568)
val DarkOnPrimaryContainer = GlassTextWhite

val DarkSecondary = CyanAccentBright
val DarkOnSecondary = Color(0xFF041C24)
val DarkSecondaryContainer = Color(0xFF192C52)
val DarkOnSecondaryContainer = GlassTextWhite

val DarkTertiary = CyanAccentBright
val DarkOnTertiary = Color(0xFF04262E)
val DarkTertiaryContainer = Color(0xFF113C4D)
val DarkOnTertiaryContainer = GlassTextWhite

val DarkBackground = GlassBackgroundDeep
val DarkOnBackground = GlassTextWhite
val DarkSurface = Color(0xFF101C36)
val DarkOnSurface = GlassTextWhite
val DarkSurfaceVariant = Color(0xFF1A2B4F)
val DarkOnSurfaceVariant = GlassTextSoftGray
val DarkOutline = GlassBorderSubtle

val DarkError = GlassSoftRed
val DarkOnError = Color(0xFF2B0606)
val DarkErrorContainer = Color(0xFF4C111B)
val DarkOnErrorContainer = GlassTextWhite

// ============================================================================
// High-Contrast Light Minimalist Glass Palette (Crisp Cool White + Electric Blue)
// ============================================================================

val LightGlassBackground = Color(0xFFF3F6FB)       // Soft cool-white screen backdrop
val LightGlassSurfaceFill = Color(0xFFFFFFFF)      // Crisp pure-white elevated cards
val LightGlassSurfaceInner = Color(0xFFE9EFF8)     // Subtle slate tint for tracks & chips
val LightGlassSheetBackground = Color(0xFAFFFFFF)  // TopAppBar & NavigationBar surface
val LightGlassDialogContainer = Color(0xFFFFFFFF)  // Dialogs & modal bottom sheets
val LightGlassScrim = Color(0x660F172A)            // Soft slate modal scrim
val LightGlassBorderSubtle = Color(0xFFCBD5E1)     // Crisp slate-300 card/section borders

val LightTextPrimary = Color(0xFF0F172A)           // Deep slate-900 primary text (17.4:1)
val LightTextSecondary = Color(0xFF475569)         // Slate-600 secondary text (7.6:1)
val LightTextDisabled = Color(0xFF64748B)          // Slate-500 readable disabled text (4.8:1)

val LightChartBackground = Color(0xFFF8FAFC)
val LightChartGridLine = Color(0xFFDCE4F0)
val LightDisabledCreateContainer = Color(0xFFE2E8F0)

val DarkChartBackground = Color(0xFF0A1428)
val DarkChartGridLine = Color(0xFF1E335A)
val DarkDisabledCreateContainer = Color(0xFF13203B)

// Material 3 Light ColorScheme mappings
val LightPrimary = ElectricBlue
val LightOnPrimary = GlassTextWhite
val LightPrimaryContainer = Color(0xFFDBEAFE)
val LightOnPrimaryContainer = Color(0xFF1E3A8A)

val LightSecondary = Color(0xFF0284C7)
val LightOnSecondary = GlassTextWhite
val LightSecondaryContainer = Color(0xFFE0F2FE)
val LightOnSecondaryContainer = Color(0xFF0C4A6E)

val LightTertiary = Color(0xFF0891B2)
val LightOnTertiary = GlassTextWhite
val LightTertiaryContainer = Color(0xFFCFFAFE)
val LightOnTertiaryContainer = Color(0xFF164E63)

val LightBackground = LightGlassBackground
val LightOnBackground = LightTextPrimary
val LightSurface = LightGlassSurfaceFill
val LightOnSurface = LightTextPrimary
val LightSurfaceVariant = LightGlassSurfaceInner
val LightOnSurfaceVariant = LightTextSecondary
val LightOutline = LightGlassBorderSubtle

val LightError = Color(0xFFDC2626)
val LightOnError = GlassTextWhite
val LightErrorContainer = Color(0xFFFEE2E2)
val LightOnErrorContainer = Color(0xFF7F1D1D)

// Semantic status accessors
val StatusSuccessLight = Color(0xFF059669)
val StatusSuccessDark = GlassSoftGreen
val StatusWarningLight = Color(0xFFD97706)
val StatusWarningDark = GlassSoftAmber
