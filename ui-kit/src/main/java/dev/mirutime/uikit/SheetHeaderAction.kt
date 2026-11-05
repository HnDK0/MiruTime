package dev.mirutime.uikit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow

/**
 * The one Reset action of a sheet: it lives in the title row, never at the
 * bottom of the scrolling body. The visual height stays compact; the touch
 * target is still 48dp.
 */
@Composable
fun SheetHeaderAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ink = if (enabled) {
        LocalMiruExtraColors.current.accent
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides MiruSize.Touch) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(MiruRadius.Control))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .minimumInteractiveComponentSize()
                .heightIn(min = MiruSize.Control)
                .padding(horizontal = MiruSpace.S),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
