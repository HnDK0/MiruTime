package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp

enum class MiruButtonStyle { Primary, InversePrimary, Secondary, Destructive, Text }

enum class MiruIconButtonStyle { Bare, Plate }

internal fun MiruButtonStyle.minVisualHeight(): Dp =
    if (this == MiruButtonStyle.Primary || this == MiruButtonStyle.InversePrimary) {
        MiruSize.ButtonPrimary
    } else {
        MiruSize.Button
    }

/** Visual/interaction only. Busy describes the caller's operation; it never changes enabled. */
@Composable
fun MiruButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MiruButtonStyle = MiruButtonStyle.Secondary,
    enabled: Boolean = true,
    busy: Boolean = false,
    contentDescription: String? = null,
    busyDescription: String? = null,
    leadingIcon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalMiruExtraColors.current
    val shape = MaterialTheme.shapes.small
    val backing = when (style) {
        MiruButtonStyle.Primary -> scheme.primary
        MiruButtonStyle.InversePrimary -> scheme.onBackground
        MiruButtonStyle.Secondary -> scheme.surfaceContainerLow
        MiruButtonStyle.Destructive -> scheme.error
        MiruButtonStyle.Text -> Color.Transparent
    }
    val ink = when (style) {
        MiruButtonStyle.Primary -> scheme.onPrimary
        MiruButtonStyle.InversePrimary -> scheme.background
        MiruButtonStyle.Secondary -> scheme.onSurface
        MiruButtonStyle.Destructive -> scheme.onError
        MiruButtonStyle.Text -> extra.accent
    }
    // A busy button keeps the active fill and ink even when the caller also
    // disables it: the spinner must read as progress, not as an idle disabled
    // control.
    val contentInk = if (enabled || busy) ink else scheme.onSurface.copy(alpha = 0.38f)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MiruSize.Touch) {
        Row(
            modifier = modifier
                .semantics(mergeDescendants = true) {
                    contentDescription?.let { this.contentDescription = it }
                    if (busy) {
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        busyDescription?.let { stateDescription = it }
                    }
                }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .minimumInteractiveComponentSize()
                .heightIn(min = style.minVisualHeight())
                .defaultMinSize(minWidth = MiruSize.Touch)
                .clip(shape)
                .background(
                    when {
                        enabled || busy -> backing
                        style == MiruButtonStyle.Text -> Color.Transparent
                        else -> scheme.onSurface.copy(alpha = 0.12f)
                    },
                )
                .then(
                    if (style == MiruButtonStyle.Secondary && (enabled || busy)) {
                        Modifier.border(MiruSize.Stroke, scheme.outlineVariant, shape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S, Alignment.CenterHorizontally),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides contentInk,
                LocalTextStyle provides MaterialTheme.typography.labelLarge,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(MiruSize.Indicator),
                        // A busy CTA is often disabled; keep the only activity
                        // cue above the 3:1 non-text contrast floor.
                        color = if (enabled || busy) contentInk else scheme.onSurface.copy(alpha = 0.60f),
                        strokeWidth = MiruSpace.Xxs,
                    )
                } else if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(MiruSize.Icon))
                }
                content()
            }
        }
    }
}

@Composable
fun MiruIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MiruIconButtonStyle = MiruIconButtonStyle.Bare,
    selected: Boolean? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyDescription: String? = null,
    tint: Color? = null,
    iconSize: Dp = MiruSize.Icon,
    contentAlignment: Alignment = Alignment.Center,
) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalMiruExtraColors.current
    val shape = MaterialTheme.shapes.small
    val ink = when {
        !enabled -> extra.muted.copy(alpha = 0.38f)
        tint != null -> tint
        selected == true && style == MiruIconButtonStyle.Plate -> extra.onAccent
        selected == true -> extra.accent
        else -> scheme.onSurface
    }
    Box(
        modifier = modifier
            .size(MiruSize.Touch)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                selected?.let { this.selected = it }
                if (busy) {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    busyDescription?.let { stateDescription = it }
                }
            }
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = contentAlignment,
    ) {
        Box(
            modifier = Modifier
                .size(MiruSize.Control)
                .then(
                    if (style == MiruIconButtonStyle.Plate) {
                        Modifier.background(if (selected == true) extra.accent else scheme.surfaceContainerLow, shape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(MiruSize.Indicator),
                    color = ink,
                    strokeWidth = MiruSpace.Xxs,
                )
            } else {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(iconSize))
            }
        }
    }
}
