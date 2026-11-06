package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow

/**
 * Compact secondary action: a 32dp visual plate on a 48dp touch slot. The one
 * owner of the "small quiet button" used by chapter toolbars, book actions and
 * selection strips. `progress` is a determinate queue ring, `busy` an
 * indeterminate one; neither changes `enabled`.
 *
 * Width comes from the caller (`weight`/`fillMaxWidth`); in a plain bounded Row
 * the plate takes the remaining width, so pass a weight when sharing a row.
 */
@Composable
fun MiruCompactButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyDescription: String? = null,
    progress: Float? = null,
    selected: Boolean = false,
    muted: Boolean = false,
    ink: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(MiruRadius.Control)
    val textInk = when {
        ink != null -> ink
        muted -> extra.muted
        selected -> extra.accent
        else -> MaterialTheme.colorScheme.onSurface
    }.let { if (enabled) it else extra.muted.copy(alpha = 0.6f) }
    Box(
        modifier = modifier
            .heightIn(min = MiruSize.Touch)
            .defaultMinSize(minWidth = MiruSize.Touch)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(shape)
            .semantics(mergeDescendants = true) {
                when {
                    progress != null -> progressBarRangeInfo =
                        ProgressBarRangeInfo(progress, 0f..1f)
                    busy -> {
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        busyDescription?.let { stateDescription = it }
                    }
                }
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MiruSize.ButtonCompact)
                .clip(shape)
                .background(
                    if (selected) extra.accent.copy(alpha = 0.12f) else miruCardSurface(),
                )
                .border(
                    MiruSize.Stroke,
                    if (selected) extra.accent.copy(alpha = 0.40f) else MaterialTheme.colorScheme.outline,
                    shape,
                )
                .padding(horizontal = MiruSpace.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress },
                    strokeWidth = MiruSpace.Xxs,
                    color = extra.accent,
                    modifier = Modifier.size(MiruSize.Indicator),
                )
            } else if (busy) {
                CircularProgressIndicator(
                    strokeWidth = MiruSpace.Xxs,
                    color = extra.accent,
                    modifier = Modifier.size(MiruSize.Indicator),
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textInk,
                    modifier = Modifier.size(MiruSize.IconPlate),
                )
            }
            if (icon != null || busy || progress != null) {
                Spacer(modifier = Modifier.width(MiruSpace.Xs + MiruSpace.Xxs))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = textInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (trailing != null) Modifier.weight(1f, fill = false) else Modifier,
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(MiruSpace.Xs))
                trailing()
            }
        }
    }
}
