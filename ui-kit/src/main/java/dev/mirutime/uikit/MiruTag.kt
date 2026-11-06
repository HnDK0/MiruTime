package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// 24dp tonal tag. Plain tags are labels (onSurface ink on a 7% wash);
// `emphasized` marks the one interactive tag (the genre overflow) with accent
// ink on a hairline outline. An interactive tag gets the 48dp touch slot.
@Composable
fun MiruTag(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(MiruRadius.Control)
    val ink = if (emphasized) extra.accent else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .heightIn(min = if (onClick != null) MiruSize.RowDense else MiruSize.Chip)
            .let { base ->
                if (onClick != null) {
                    base.clip(shape).clickable(role = Role.Button, onClick = onClick)
                } else {
                    base
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 24.dp)
                .clip(shape)
                .then(
                    if (emphasized) {
                        Modifier.border(1.dp, extra.accent.copy(alpha = 0.55f), shape)
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                    },
                )
                .padding(horizontal = MiruSpace.S),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ink,
                maxLines = 1,
            )
        }
    }
}
