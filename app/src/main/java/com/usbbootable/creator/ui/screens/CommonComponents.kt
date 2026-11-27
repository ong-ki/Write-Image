package com.usbbootable.creator.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usbbootable.creator.ui.theme.GlassCardShape16
import com.usbbootable.creator.ui.theme.GlassCardShape20
import com.usbbootable.creator.ui.theme.GlassCardSurface
import com.usbbootable.creator.ui.theme.GlassTextWhite
import com.usbbootable.creator.ui.theme.LocalAppColors
import com.usbbootable.creator.ui.theme.pressScale
import com.usbbootable.creator.ui.theme.statusSuccessColor
import com.usbbootable.creator.ui.theme.statusWarningColor

internal val HomeControlShape: Shape = RoundedCornerShape(12.dp)
private val StatusPillShape: Shape = RoundedCornerShape(8.dp)
private val ScreenMaxWidth = 640.dp
private val ScreenPadding = 16.dp
private val ScreenSpacing = 16.dp

/**
 * Scrollable, width-capped page column matching the Home screen layout across all tabs.
 */
@Composable
internal fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ScreenMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ScreenSpacing),
            content = content
        )
    }
}

/**
 * Lazy, width-capped page list matching the Home screen padding, spacing, and max width.
 */
@Composable
internal fun ScreenLazyColumn(
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = ScreenMaxWidth)
                .fillMaxSize(),
            contentPadding = PaddingValues(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ScreenSpacing),
            content = content
        )
    }
}

/**
 * Shared Home-style card container (20dp rounded corners, subtle theme-aware border, flat surface).
 */
@Composable
internal fun AppCard(
    modifier: Modifier = Modifier,
    accentBorderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCardSurface(
        modifier = modifier.fillMaxWidth(),
        shape = GlassCardShape20,
        accentBorderColor = accentBorderColor,
        borderWidth = borderWidth
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/**
 * Home-style section heading with an optional trailing text action.
 */
@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = LocalAppColors.current.textPrimary,
            modifier = Modifier.semantics { heading() }
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/**
 * Groups related content under a Home-style [SectionHeader] inside a Home-style [AppCard].
 */
@Composable
internal fun TitledCardSection(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionHeader(
            title = title,
            actionLabel = actionLabel,
            onAction = onAction
        )
        AppCard(
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

internal enum class BannerTone {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

@Immutable
internal data class BannerColors(
    val container: Color,
    val content: Color,
    val border: Color,
    val icon: Color
)

@Composable
internal fun bannerColors(tone: BannerTone): BannerColors {
    val appColors = LocalAppColors.current
    val successColor = statusSuccessColor()
    val warningColor = statusWarningColor()
    val errorColor = MaterialTheme.colorScheme.error
    return when (tone) {
        BannerTone.INFO -> BannerColors(
            container = if (appColors.isDark) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f)
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            border = appColors.borderSubtle,
            icon = MaterialTheme.colorScheme.primary
        )
        BannerTone.SUCCESS -> BannerColors(
            container = lerp(appColors.surfaceCard, successColor, if (appColors.isDark) 0.22f else 0.14f),
            content = if (appColors.isDark) {
                appColors.textPrimary
            } else {
                lerp(successColor, appColors.textPrimary, 0.52f)
            },
            border = successColor.copy(alpha = if (appColors.isDark) 0.65f else 0.45f),
            icon = successColor
        )
        BannerTone.WARNING -> BannerColors(
            container = lerp(appColors.surfaceCard, warningColor, if (appColors.isDark) 0.22f else 0.14f),
            content = if (appColors.isDark) {
                appColors.textPrimary
            } else {
                lerp(warningColor, appColors.textPrimary, 0.56f)
            },
            border = warningColor.copy(alpha = if (appColors.isDark) 0.65f else 0.45f),
            icon = warningColor
        )
        BannerTone.ERROR -> BannerColors(
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            border = errorColor.copy(alpha = if (appColors.isDark) 0.65f else 0.45f),
            icon = errorColor
        )
    }
}

internal fun defaultBannerIcon(tone: BannerTone): ImageVector = when (tone) {
    BannerTone.INFO -> Icons.Rounded.Info
    BannerTone.SUCCESS -> Icons.Rounded.CheckCircle
    BannerTone.WARNING -> Icons.Rounded.WarningAmber
    BannerTone.ERROR -> Icons.Rounded.ErrorOutline
}

/**
 * Shared Home-style banner used for top-of-screen notices, result summaries, and inline alerts.
 */
@Composable
internal fun AppBanner(
    message: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = defaultBannerIcon(tone),
    iconContentDescription: String? = null,
    isLoading: Boolean = false,
    rounded: Boolean = true,
    fontWeight: FontWeight = FontWeight.Normal,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    dismissContentDescription: String = "Dismiss",
    footerActions: (@Composable RowScope.(BannerColors) -> Unit)? = null
) {
    val palette = bannerColors(tone)
    val shape = if (rounded) HomeControlShape else RectangleShape
    val verticalPadding = if (rounded) 12.dp else 8.dp

    Surface(
        color = palette.container,
        shape = shape,
        border = BorderStroke(1.dp, palette.border),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = palette.icon
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = iconContentDescription,
                        tint = palette.icon,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = fontWeight,
                    color = palette.content,
                    modifier = Modifier.weight(1f)
                )

                if (actionLabel != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        enabled = actionEnabled
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.icon
                        )
                    }
                }

                if (onDismiss != null) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = dismissContentDescription,
                            tint = palette.content,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (footerActions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = { footerActions(palette) }
                )
            }
        }
    }
}

/**
 * Primary CTA button matching the Home screen's main action styling.
 */
@Composable
internal fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    loadingText: String = text,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    minHeight: Dp = 56.dp,
    labelContent: (@Composable RowScope.() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val appColors = LocalAppColors.current
    val isReady = enabled && !isLoading

    Button(
        onClick = {
            if (isReady) onClick()
        },
        enabled = isReady,
        interactionSource = interactionSource,
        shape = GlassCardShape16,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = appColors.disabledCreateContainer,
            disabledContentColor = appColors.textDisabled
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .pressScale(interactionSource, enabled = isReady)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = appColors.textSecondary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = loadingText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (labelContent != null) {
                labelContent()
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Emphasized tonal action button matching Home's secondary high-emphasis action style.
 */
@Composable
internal fun AppTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    loadingText: String = text,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    minHeight: Dp = 48.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isReady = enabled && !isLoading

    FilledTonalButton(
        onClick = onClick,
        enabled = isReady,
        interactionSource = interactionSource,
        shape = HomeControlShape,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .pressScale(interactionSource, enabled = isReady)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = loadingText,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Standard or destructive outline button matching Home's outline buttons.
 */
@Composable
internal fun AppOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    loadingText: String = text,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    isDestructive: Boolean = false,
    compactTextStyle: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val appColors = LocalAppColors.current
    val isReady = enabled && !isLoading
    val border = if (isDestructive) {
        BorderStroke(
            width = 1.5.dp,
            color = if (isReady) MaterialTheme.colorScheme.error else appColors.borderSubtle
        )
    } else {
        BorderStroke(
            width = 1.dp,
            color = if (isReady) MaterialTheme.colorScheme.outline else appColors.borderSubtle.copy(alpha = 0.6f)
        )
    }
    val colors = if (isDestructive) {
        ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
            disabledContentColor = appColors.textDisabled
        )
    } else {
        ButtonDefaults.outlinedButtonColors(
            disabledContentColor = appColors.textDisabled
        )
    }
    val textStyle = if (compactTextStyle) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.labelLarge
    }

    OutlinedButton(
        onClick = onClick,
        enabled = isReady,
        interactionSource = interactionSource,
        shape = HomeControlShape,
        border = border,
        colors = colors,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressScale(interactionSource, enabled = isReady)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = loadingText,
                style = textStyle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = textStyle,
                fontWeight = if (compactTextStyle) FontWeight.SemiBold else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Selectable option button matching Home's Flashing Mode selector.
 */
@Composable
internal fun AppSelectableOptionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accessibilityDescription: String = label,
    maxLines: Int = 2
) {
    val appColors = LocalAppColors.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = HomeControlShape,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                appColors.borderSubtle
            }
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                appColors.textSecondary
            },
            disabledContentColor = appColors.textDisabled
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = accessibilityDescription
            }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Shared empty state inside a Home-style card.
 */
@Composable
internal fun AppEmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    compact: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val appColors = LocalAppColors.current
    AppCard(modifier = modifier) {
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIconBadge(
                    icon = icon,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 40.dp,
                    iconSize = 20.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = appColors.textPrimary
                        )
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusIconBadge(
                    icon = icon,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 56.dp,
                    iconSize = 28.dp
                )
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = appColors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (actionLabel != null && onAction != null) {
                    AppOutlineButton(
                        text = actionLabel,
                        onClick = onAction,
                        compactTextStyle = true
                    )
                }
            }
        }
    }
}

/**
 * Circular tinted icon badge matching Home's StepBadge and WriteResultPanel icon styling.
 */
@Composable
internal fun StatusIconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    iconSize: Dp = 18.dp,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
internal fun StatusPill(text: String, color: Color) {
    val appColors = LocalAppColors.current
    val pillBg = if (appColors.isDark) color.copy(alpha = 0.22f) else color.copy(alpha = 0.14f)
    val pillBorder = if (appColors.isDark) color.copy(alpha = 0.55f) else color.copy(alpha = 0.40f)
    val pillText = if (appColors.isDark) GlassTextWhite else lerp(color, appColors.textPrimary, 0.35f)
    Surface(
        color = pillBg,
        shape = StatusPillShape,
        border = BorderStroke(0.75.dp, pillBorder)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = pillText,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = LocalAppColors.current.borderSubtle
    )
}

/**
 * Consistent whole-row switch item with a 48dp minimum touch target and switch semantics.
 */
@Composable
internal fun AppSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val appColors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(HomeControlShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) appColors.textPrimary else appColors.textDisabled,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) appColors.textSecondary else appColors.textDisabled,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
    }
}

/**
 * Consistent clickable list row with leading icon, title, subtitle, and optional chevron.
 */
@Composable
internal fun AppListRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showChevron: Boolean = true
) {
    val appColors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(HomeControlShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else appColors.textDisabled,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) appColors.textPrimary else appColors.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else appColors.textDisabled,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showChevron) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = appColors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
