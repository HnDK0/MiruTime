package dev.mirutime.uikit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// THE single place for round icon buttons: 40dp visible plate inside a 48dp
// hit box (visual and touch sizes are separate by rule). Shape is a moderate
// 12dp radius — full ovals are banned app-wide. The disc is the plate with
// onSurface ink in every theme.
@Composable
fun MiruRoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MiruIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        style = MiruIconButtonStyle.Plate,
    )
}
