package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

// Source/plugin badge: a compact uppercase chip with a status dot and an
// outbound glyph. The visual face is 20dp; the dense touch slot is 40dp.
@Composable
fun MiruSourceChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(MiruRadius.Badge)
    val label = remember(text) { text.uppercase(Locale.ROOT) }
    Box(
        modifier = modifier
            .heightIn(min = MiruSize.RowDense)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = contentAlignment,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
            modifier = Modifier
                .heightIn(min = 20.dp)
                .clip(shape)
                .background(extra.accent.copy(alpha = 0.14f))
                .border(1.dp, extra.accent.copy(alpha = 0.28f), shape)
                .padding(horizontal = MiruSpace.S),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(extra.accent),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = extra.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = null,
                tint = extra.accent.copy(alpha = 0.8f),
                modifier = Modifier.size(MiruSize.IconSmall),
            )
        }
    }
}
