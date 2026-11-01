package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role

// The standard list row inside a section card (tools, source page, add-book,
// ...): accent icon, title + one muted line, optional trailing slot (default:
// a small forward arrow). 56dp minimum, the whole row is the touch target.
// Rows are separated by MiruRowDivider and sit in a card with zero content
// padding — the row pads itself, so the ripple reaches the card edges.
@Composable
fun MiruActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) extra.accent else extra.muted,
            modifier = Modifier.size(MiruSize.Icon),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = extra.muted,
                modifier = Modifier.size(MiruSize.Indicator),
            )
        }
    }
}

@Composable
fun MiruRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MiruSpace.L)
            .height(MiruSize.Stroke)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    )
}
