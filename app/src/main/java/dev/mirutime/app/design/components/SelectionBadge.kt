package dev.mirutime.app.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace

// ONE selection affordance for migration: an outlined circle when off, the
// filled accent check when on. Works for single- and multi-select; the caller
// owns the toggle semantics (`selected` / `toggleable`), this is just the
// visual. On a cover it gets a surface backing so it reads over art.
@Composable
fun SelectionBadge(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val base = modifier.size(MiruSize.Touch / 2).clip(CircleShape)
    Box(
        modifier = base
            .then(
                if (selected) {
                    Modifier.background(extra.accent)
                } else {
                    // Hollow ring with a scrim outer edge: one state on covers
                    // and in rows, no dark disc, no filled background.
                    Modifier
                        .border(
                            MiruSpace.Xxs,
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f),
                            CircleShape,
                        )
                        .padding(MiruSpace.Xxs)
                        .border(MiruSpace.Xxs, extra.muted, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = extra.onAccent,
                modifier = Modifier.size(MiruSize.Touch / 3),
            )
        }
    }
}
