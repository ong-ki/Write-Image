package com.usbbootable.creator.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// Premium Dark Glassmorphism Palette (Deep Navy / Midnight Blue + Electric Blue & Cyan)
// ============================================================================

// Deep navy / midnight blue background layers
val GlassBackgroundDeep = Color(0xFF050A17)
val GlassBackgroundMid = Color(0xFF0A1328)
val GlassBackgroundElevated = Color(0xFF0F1C3A)

// Ambient gradient lighting accents
val GlassAmbientElectricBlue = Color(0xFF2563EB)
val GlassAmbientCyan = Color(0xFF06B6D4)
val GlassAmbientIndigo = Color(0xFF1E3A8A)

// Semi-transparent dark blue glass surfaces
val GlassSurfaceTop = Color(0xFF182B52)
val GlassSurfaceBottom = Color(0xFF0D1832)
val GlassSurfaceInner = Color(0xFF132244)
val GlassSheetBackground = Color(0xFF0C162E)

// Specular glass borders
val GlassBorderSpecular = Color(0x45FFFFFF)
val GlassBorderSubtle = Color(0x2293C5FD)
val GlassBorderCyanTint = Color(0x4022D3EE)

// Primary & Secondary Accents
val ElectricBlue = Color(0xFF3B82F6)
val ElectricBlueBright = Color(0xFF60A5FA)
val ElectricBlueDeep = Color(0xFF1D4ED8)
val CyanAccent = Color(0xFF06B6D4)
val CyanAccentBright = Color(0xFF22D3EE)

// Semantic Status Colors
val GlassSoftGreen = Color(0xFF34D399)
val GlassSoftGreenDeep = Color(0xFF059669)
val GlassSoftRed = Color(0xFFF87171)
val GlassSoftRedDeep = Color(0xFFDC2626)
val GlassSoftAmber = Color(0xFFFBBF24)

// Typography Colors
val GlassTextWhite = Color(0xFFF8FAFC)
val GlassTextSoftGray = Color(0xFF94A3B8)
val GlassTextMuted = Color(0xFF64748B)

// Material 3 Dark ColorScheme mappings
val DarkPrimary = ElectricBlueBright
val DarkOnPrimary = Color(0xFF041029)
val DarkPrimaryContainer = Color(0xFF172E63)
val DarkOnPrimaryContainer = Color(0xFFDBEAFE)

val DarkSecondary = CyanAccentBright
val DarkOnSecondary = Color(0xFF032026)
val DarkSecondaryContainer = Color(0xFF132A4A)
val DarkOnSecondaryContainer = Color(0xFFE0F2FE)

val DarkTertiary = CyanAccentBright
val DarkOnTertiary = Color(0xFF042F2E)
val DarkTertiaryContainer = Color(0xFF0F3D4C)
val DarkOnTertiaryContainer = Color(0xFFCCFBF1)

val DarkBackground = GlassBackgroundDeep
val DarkOnBackground = GlassTextWhite
val DarkSurface = Color(0xFF0D1730)
val DarkOnSurface = GlassTextWhite
val DarkSurfaceVariant = Color(0xFF162547)
val DarkOnSurfaceVariant = GlassTextSoftGray
val DarkOutline = Color(0xFF2B4270)

val DarkError = GlassSoftRed
val DarkOnError = Color(0xFF450A0A)
val DarkErrorContainer = Color(0xFF4C111B)
val DarkOnErrorContainer = Color(0xFFFEE2E2)

// Material 3 Light-Glass ColorScheme mappings (for explicit Light mode option in Settings)
val LightPrimary = Color(0xFF2563EB)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDBEAFE)
val LightOnPrimaryContainer = Color(0xFF1E3A8A)

val LightSecondary = Color(0xFF0891B2)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFCFFAFE)
val LightOnSecondaryContainer = Color(0xFF164E63)

val LightTertiary = Color(0xFF0D9488)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFCCFBF1)
val LightOnTertiaryContainer = Color(0xFF134E4A)

val LightBackground = Color(0xFF091226)
val LightOnBackground = GlassTextWhite
val LightSurface = Color(0xFF101D3B)
val LightOnSurface = GlassTextWhite
val LightSurfaceVariant = Color(0xFF192B52)
val LightOnSurfaceVariant = Color(0xFFABBCD4)
val LightOutline = Color(0xFF334E82)

val LightError = GlassSoftRed
val LightOnError = Color(0xFF450A0A)
val LightErrorContainer = Color(0xFF5A1622)
val LightOnErrorContainer = Color(0xFFFEE2E2)

// Semantic status accessors
val StatusSuccessLight = GlassSoftGreen
val StatusSuccessDark = GlassSoftGreen
val StatusWarningLight = GlassSoftAmber
val StatusWarningDark = GlassSoftAmber
