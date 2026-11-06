package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics

// THE single place for round icon buttons: 40dp visible plate inside a 48dp
// hit box (visual and touch sizes are separate by rule). Shape is a moderate
// 12dp radius — full ovals are banned app-wide. The disc is the plate with
// onSurface ink in every theme. A filter count rides as a badge; the count is
// exposed as the button's state description, not as a stray text node.
@Composable
fun MiruRoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
    badgeContentDescription: String? = null,
) {
    val extra = LocalMiruExtraColors.current
    Box(modifier) {
        MiruIconButton(
            icon = icon,
            contentDescription = contentDescription,
            onClick = onClick,
            style = MiruIconButtonStyle.Plate,
            stateDescription = badgeContentDescription
                ?: if (badgeCount > 0) {
                    if (badgeCount > 99) "99+" else badgeCount.toString()
                } else {
                    null
                },
        )
        if (badgeCount > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = MiruSpace.S, end = MiruSpace.Xxs)
                    .heightIn(min = MiruSize.Indicator)
                    .widthIn(min = MiruSize.Indicator)
                    .background(extra.accent, CircleShape)
                    .padding(horizontal = MiruSpace.Xs)
                    .clearAndSetSemantics {},
            ) {
                Text(
                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.onAccent,
                    maxLines = 1,
                )
            }
        }
    }
}
