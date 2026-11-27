package dev.mirutime.app.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize

// One source avatar for every list that names a plugin (Extensions, Browse,
// the filter sheets, Migration): the declared icon over its monogram. The
// letter is only a fallback and is dropped the moment the icon renders — a
// transparent PNG must not show it through its own holes.
@Composable
fun PluginAvatar(
    name: String,
    iconUrl: String?,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(MiruRadius.Control)
    var loaded by remember(iconUrl) { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(MiruSize.Control)
            .clip(shape)
            .background(extra.accent.copy(alpha = 0.14f)),
    ) {
        if (!loaded) {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = extra.accent,
            )
        }
        if (!iconUrl.isNullOrBlank()) {
            AsyncImage(
                model = iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(shape),
                onSuccess = { loaded = true },
                onError = { loaded = false },
            )
        }
    }
}
