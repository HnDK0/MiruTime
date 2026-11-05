package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow

// One half of a pair: caption above the current value on a bordered plate.
// Both halves share this box so the two windows read as one control.
@Composable
fun MiruPairButton(
    caption: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(MiruRadius.Control)
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = MiruSize.Row)
            .clip(shape)
            .background(miruCardSurface())
            .border(MiruSize.Stroke, MaterialTheme.colorScheme.outline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = MiruSpace.M, vertical = MiruSpace.S),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = LocalMiruExtraColors.current.muted,
            maxLines = 1,
        )
        Spacer(Modifier.height(MiruSpace.Xxs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
