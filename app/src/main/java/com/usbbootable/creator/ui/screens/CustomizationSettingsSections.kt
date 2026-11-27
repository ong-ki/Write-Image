package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usbbootable.creator.core.storage.AppearanceCustomization
import com.usbbootable.creator.core.storage.NeonColorPreset
import com.usbbootable.creator.core.storage.NeonColorPresets
import com.usbbootable.creator.core.storage.NeonThemeConfig
import com.usbbootable.creator.core.storage.ProgressBarStyle
import com.usbbootable.creator.ui.theme.LocalAppColors
import com.usbbootable.creator.ui.theme.adaptAccentForSurface
import com.usbbootable.creator.ui.theme.effectivePrimaryColor
import com.usbbootable.creator.ui.theme.effectiveSecondaryColor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val HueSpectrumColors = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000)
)

@Composable
fun CustomizationSettingsContent(
    savedCustomization: AppearanceCustomization,
    onApplyCustomization: (AppearanceCustomization) -> Unit,
    onResetDefaults: () -> Unit
) {
    var draft by remember(savedCustomization) { mutableStateOf(savedCustomization) }
    var editingSlot by rememberSaveable { mutableIntStateOf(0) } // 0 = Primary, 1 = Secondary

    val appColors = LocalAppColors.current
    val neon = draft.neonTheme
    val previewPrimary = adaptAccentForSurface(neon.effectivePrimaryColor, appColors.isDark)
    val previewSecondary = adaptAccentForSurface(neon.effectiveSecondaryColor, appColors.isDark)
    val isCustomModified = draft != AppearanceCustomization.DEFAULT

    fun commit(updated: AppearanceCustomization) {
        val clean = updated.normalized()
        draft = clean
        onApplyCustomization(clean)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. Progress Bar Style Selection (4 visually distinct styles in a 2x2 grid)
        TitledCardSection(
            title = "Progress Bar Style",
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProgressBarStyle.entries.chunked(2).forEach { rowStyles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowStyles.forEach { style ->
                        ProgressBarStyleTile(
                            style = style,
                            selected = draft.progressBarStyle == style,
                            neonTheme = neon,
                            onClick = {
                                commit(draft.copy(progressBarStyle = style))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2. Neon Customization (switches, 3x2 preset grid, custom HSV & glow sliders)
        TitledCardSection(
            title = "Neon Customization",
            actionLabel = if (isCustomModified) "Reset" else null,
            onAction = {
                draft = AppearanceCustomization.DEFAULT
                onResetDefaults()
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppSwitchRow(
                title = "Enable Neon Effects",
                subtitle = "Custom accent colors and glow styling",
                checked = neon.neonEnabled,
                enabled = true,
                onCheckedChange = { enabled ->
                    commit(draft.copy(neonTheme = neon.copy(neonEnabled = enabled)))
                }
            )

            AppDivider()

            AppSwitchRow(
                title = "Background Glow",
                subtitle = "Subtle ambient backdrop lighting",
                checked = neon.backgroundGlowEnabled,
                enabled = neon.neonEnabled,
                onCheckedChange = { bgEnabled ->
                    commit(draft.copy(neonTheme = neon.copy(backgroundGlowEnabled = bgEnabled)))
                }
            )

            AppDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonColorPresets.all.chunked(3).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPresets.forEach { preset ->
                                val isPresetSelected = neon.primaryColorArgb == preset.primaryArgb &&
                                    neon.secondaryColorArgb == preset.secondaryArgb
                                NeonPresetTile(
                                    preset = preset,
                                    selected = isPresetSelected,
                                    enabled = neon.neonEnabled,
                                    onClick = {
                                        commit(draft.copy(neonTheme = neon.withPreset(preset)))
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            AppDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Custom Colors",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorSlotChip(
                        label = "Primary",
                        argb = neon.primaryColorArgb,
                        selected = editingSlot == 0,
                        enabled = neon.neonEnabled,
                        onClick = { editingSlot = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    ColorSlotChip(
                        label = "Secondary",
                        argb = neon.secondaryColorArgb,
                        selected = editingSlot == 1,
                        enabled = neon.neonEnabled,
                        onClick = { editingSlot = 1 },
                        modifier = Modifier.weight(1f)
                    )
                }

                val activeArgb = if (editingSlot == 0) neon.primaryColorArgb else neon.secondaryColorArgb
                InlineHsvColorPicker(
                    argb = activeArgb,
                    enabled = neon.neonEnabled,
                    onColorChanged = { updatedArgb ->
                        val updatedNeon = if (editingSlot == 0) {
                            neon.copy(primaryColorArgb = updatedArgb)
                        } else {
                            neon.copy(secondaryColorArgb = updatedArgb)
                        }
                        draft = draft.copy(neonTheme = updatedNeon)
                    },
                    onColorCommitted = {
                        onApplyCustomization(draft.normalized())
                    }
                )
            }

            AppDivider()

            LabeledSlider(
                label = "Glow Intensity",
                valueText = "${(neon.glowIntensity * 100f).roundToInt()}%",
                value = neon.glowIntensity,
                valueRange = 0f..1f,
                enabled = neon.neonEnabled,
                accentColor = previewPrimary,
                onValueChange = { intensity ->
                    draft = draft.copy(neonTheme = neon.copy(glowIntensity = intensity))
                },
                onValueChangeFinished = {
                    onApplyCustomization(draft.normalized())
                }
            )

            LabeledSlider(
                label = "Glow Radius",
                valueText = "${neon.glowRadiusDp.roundToInt()} dp",
                value = neon.glowRadiusDp,
                valueRange = 2f..20f,
                enabled = neon.neonEnabled,
                accentColor = previewSecondary,
                onValueChange = { radius ->
                    draft = draft.copy(neonTheme = neon.copy(glowRadiusDp = radius))
                },
                onValueChangeFinished = {
                    onApplyCustomization(draft.normalized())
                }
            )
        }
    }
}

@Composable
private fun ProgressBarStyleTile(
    style: ProgressBarStyle,
    selected: Boolean,
    neonTheme: NeonThemeConfig,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val accent = adaptAccentForSurface(neonTheme.effectivePrimaryColor, appColors.isDark)
    val shortCaption = when (style) {
        ProgressBarStyle.CLASSIC -> "Solid flat fill"
        ProgressBarStyle.NEON_GLOW -> "Cyber neon glow"
        ProgressBarStyle.GRADIENT -> "Multi-color fill"
        ProgressBarStyle.SEGMENTED -> "Discrete blocks"
    }

    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            appColors.surfaceInner
        },
        shape = HomeControlShape,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else appColors.borderSubtle
        ),
        modifier = modifier
            .clip(HomeControlShape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = style.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        appColors.textPrimary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            StyledProgressBar(
                progress = 0.72f,
                style = style,
                neonTheme = neonTheme,
                trackColor = if (selected) appColors.surfaceCard else appColors.backgroundDeep,
                height = 14.dp
            )

            Text(
                text = shortCaption,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                } else {
                    appColors.textSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NeonPresetTile(
    preset: NeonColorPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val primary = adaptAccentForSurface(Color(preset.primaryArgb), appColors.isDark)
    val secondary = adaptAccentForSurface(Color(preset.secondaryArgb), appColors.isDark)
    Surface(
        color = if (selected && enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            appColors.surfaceInner
        },
        shape = HomeControlShape,
        border = BorderStroke(
            width = if (selected && enabled) 2.dp else 1.dp,
            color = if (selected && enabled) MaterialTheme.colorScheme.primary else appColors.borderSubtle
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(HomeControlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (enabled) primary else appColors.textDisabled)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (enabled) secondary else appColors.textDisabled)
            )
            Spacer(modifier = Modifier.width(6.dp))
            val shortName = when (preset.id) {
                "cyber_blue" -> "Blue"
                "electric_purple" -> "Purple"
                "neon_green" -> "Green"
                "hot_pink" -> "Pink"
                "plasma_orange" -> "Orange"
                "cyan_glow" -> "Cyan"
                else -> preset.label
            }
            Text(
                text = shortName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected && enabled) FontWeight.SemiBold else FontWeight.Medium,
                color = when {
                    !enabled -> appColors.textDisabled
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> appColors.textSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ColorSlotChip(
    label: String,
    argb: Long,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val color = Color(argb)
    val hex = String.format(Locale.US, "#%06X", (argb and 0xFFFFFFL))
    Surface(
        color = if (selected && enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            appColors.surfaceInner
        },
        shape = HomeControlShape,
        border = BorderStroke(
            width = if (selected && enabled) 2.dp else 1.dp,
            color = if (selected && enabled) MaterialTheme.colorScheme.primary else appColors.borderSubtle
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(HomeControlShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, appColors.textPrimary.copy(alpha = 0.45f), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        !enabled -> appColors.textDisabled
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> appColors.textPrimary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = hex,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !enabled -> appColors.textDisabled
                        selected -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        else -> appColors.textSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun InlineHsvColorPicker(
    argb: Long,
    enabled: Boolean,
    onColorChanged: (Long) -> Unit,
    onColorCommitted: () -> Unit
) {
    val appColors = LocalAppColors.current
    val hsv = remember(argb) { argbToHsv(argb) }
    val hue = hsv[0]
    val sat = hsv[1]
    val value = hsv[2].coerceIn(0.25f, 1f)
    val currentColor = adaptAccentForSurface(Color(argb), appColors.isDark)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hue",
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) appColors.textSecondary else appColors.textDisabled
            )
            Text(
                text = "${hue.roundToInt()}°",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (enabled) appColors.textPrimary else appColors.textDisabled
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.height(32.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(7.dp)
            ) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(HueSpectrumColors),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                    alpha = if (enabled) 1f else 0.4f
                )
            }
            Slider(
                value = hue,
                onValueChange = { newHue ->
                    onColorChanged(hsvToArgb(newHue, sat, value))
                },
                onValueChangeFinished = onColorCommitted,
                valueRange = 0f..360f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = currentColor,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }

        LabeledSlider(
            label = "Saturation",
            valueText = "${(sat * 100f).roundToInt()}%",
            value = sat,
            valueRange = 0f..1f,
            enabled = enabled,
            accentColor = currentColor,
            onValueChange = { newSat ->
                onColorChanged(hsvToArgb(hue, newSat, value))
            },
            onValueChangeFinished = onColorCommitted
        )

        LabeledSlider(
            label = "Brightness",
            valueText = "${(value * 100f).roundToInt()}%",
            value = value,
            valueRange = 0.25f..1f,
            enabled = enabled,
            accentColor = currentColor,
            onValueChange = { newVal ->
                onColorChanged(hsvToArgb(hue, sat, newVal))
            },
            onValueChangeFinished = onColorCommitted
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    accentColor: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val appColors = LocalAppColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) appColors.textSecondary else appColors.textDisabled
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (enabled) appColors.textPrimary else appColors.textDisabled
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = appColors.surfaceInner,
                disabledThumbColor = appColors.textDisabled,
                disabledActiveTrackColor = appColors.textDisabled
            ),
            modifier = Modifier.height(32.dp)
        )
    }
}

private fun argbToHsv(argb: Long): FloatArray {
    val r = ((argb ushr 16) and 0xFFL) / 255f
    val g = ((argb ushr 8) and 0xFFL) / 255f
    val b = (argb and 0xFFL) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val hue = when {
        delta < 1e-5f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    val sat = if (max < 1e-5f) 0f else delta / max
    return floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), max.coerceIn(0f, 1f))
}

private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Long {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)

    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c

    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
    val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
    val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
    return 0xFF000000L or (r shl 16) or (g shl 8) or b
}
