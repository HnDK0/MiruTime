package dev.mirutime.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

// One visual frame for every text field: 40dp row, theme radius, filled plate,
// 1dp idle border and 2dp accent/error border. Sits inside MiruTextField and
// MiruSearchDecoration; screens never build their own field chrome.
@Composable
internal fun MiruFieldFrame(
    modifier: Modifier = Modifier,
    minHeight: Dp = MiruSize.Control,
    focused: Boolean = false,
    enabled: Boolean = true,
    isError: Boolean = false,
    plate: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalMiruExtraColors.current
    val shape = MaterialTheme.shapes.small
    val stroke = if (isError || focused) MiruSize.StrokeFocus else MiruSize.Stroke
    val borderColor = when {
        isError -> scheme.error
        focused && enabled -> extra.accent
        else -> scheme.outlineVariant
    }
    val fill = when {
        !plate -> Color.Transparent
        enabled -> miruCardSurface()
        else -> miruCardSurface().copy(alpha = 0.12f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(fill, shape)
            .border(stroke, borderColor, shape),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
