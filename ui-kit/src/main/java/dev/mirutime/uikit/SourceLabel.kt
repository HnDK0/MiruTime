package dev.mirutime.uikit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Source is data, so it gets the same label language as the rest of the card:
// a compact theme plate, never a raw text line. `+N` is the accent update
// indicator used by Dashboard rails.
@Composable
fun MiruSourceLabel(
    text: String,
    modifier: Modifier = Modifier,
    newChapters: Int = 0,
    textStyle: TextStyle = MiruCardType.Meta,
) {
    val extra = LocalMiruExtraColors.current
    val updateLabel = newChaptersLabel(newChapters)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(MiruRadius.Split),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = textStyle,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (newChapters > 0) {
            Surface(
                shape = RoundedCornerShape(MiruRadius.Split),
                color = extra.accent,
                contentColor = extra.onAccent,
            ) {
                Text(
                    text = updateLabel,
                    maxLines = 1,
                    style = MiruCardType.Rating,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

internal fun newChaptersLabel(count: Int): String = if (count > 0) "+$count" else ""
