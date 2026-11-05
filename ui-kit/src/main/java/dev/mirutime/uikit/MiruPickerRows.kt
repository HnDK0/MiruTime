package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection

@Composable
fun MiruPickerHeader(
    text: String,
    modifier: Modifier = Modifier,
    preserveCase: Boolean = false,
) {
    Text(
        text = if (preserveCase) text else text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalMiruExtraColors.current.muted,
        modifier = modifier,
    )
}

/** Main selection and trailing actions are siblings; a disabled main never disables the star. */
@Composable
fun MiruPickerRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalMiruExtraColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) scheme.primaryContainer else Color.Transparent)
            .drawBehind {
                if (selected) {
                    val edgeWidth = MiruSpace.Xxs.toPx()
                    val startX = if (layoutDirection == LayoutDirection.Rtl) size.width - edgeWidth else 0f
                    drawRect(
                        color = extra.accent,
                        topLeft = Offset(startX, 0f),
                        size = Size(edgeWidth, size.height),
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MiruSize.Touch)
                .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
                .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = when {
                        !enabled -> extra.muted
                        selected -> scheme.onPrimaryContainer
                        else -> scheme.onSurface
                    },
                )
                if (meta != null) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
        }
        trailing?.invoke()
    }
}
