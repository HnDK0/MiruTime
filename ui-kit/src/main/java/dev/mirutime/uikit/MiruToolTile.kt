package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Book/tool tile for the 2-column grid. Grows with the system font instead of
// clipping at 200%; the visual minimum is 68dp. A badge is optional and can
// carry live state (On/Off, rule count, downloaded chapters).
@Composable
fun MiruToolTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    icon: ImageVector? = null,
    badge: String? = null,
    badgeHighlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(MiruRadius.Control)
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(shape)
            .background(extra.accent.copy(alpha = if (enabled) 0.06f else 0.03f))
            .border(
                width = 1.dp,
                color = extra.accent.copy(alpha = if (enabled) 0.14f else 0.08f),
                shape = shape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(MiruSpace.S),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(MiruRadius.Split))
                    .background(extra.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    icon != null -> Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = extra.accent,
                        modifier = Modifier.size(MiruSize.IconSmall),
                    )
                    glyph != null -> Text(
                        text = glyph,
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.accent,
                    )
                }
            }
            if (badge != null) {
                Text(
                    text = badge,
                    style = MiruCardType.Meta,
                    color = if (badgeHighlighted) extra.accent else extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = MiruSpace.Xs)
                        .clip(RoundedCornerShape(MiruRadius.Split))
                        .background(
                            if (badgeHighlighted) {
                                extra.accent.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                            },
                        )
                        .padding(horizontal = MiruSpace.Xs, vertical = 2.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(top = MiruSpace.Xs)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
                maxLines = if (isLargeText()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MiruCardType.Meta,
                color = extra.muted,
                maxLines = if (isLargeText()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
