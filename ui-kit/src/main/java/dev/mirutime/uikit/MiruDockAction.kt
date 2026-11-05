package dev.mirutime.uikit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// One dock item: icon over a one-line label, 72dp wide on a 48dp touch box.
// Active reads from the accent; disabled dims both the icon and the label.
@Composable
fun MiruDockAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val iconColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.35f)
        active -> scheme.primary
        else -> scheme.onSurface
    }
    val labelColor = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.35f)
        active -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .width(72.dp)
            .heightIn(min = MiruSize.Touch)
            .clip(RoundedCornerShape(MiruRadius.Large))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = MiruSpace.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(MiruSize.Icon),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
        )
    }
}
