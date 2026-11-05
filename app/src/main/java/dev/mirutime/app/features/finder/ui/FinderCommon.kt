package dev.mirutime.app.features.finder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.design.components.PluginAvatar
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.library.domain.ContentType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon

@Composable
internal fun typeLabel(type: ContentType): String = stringResource(
    when (type) {
        ContentType.Novel -> R.string.finder_type_novel
        ContentType.Manga -> R.string.finder_type_manga
        ContentType.Video -> R.string.finder_type_video
    },
)

// Plugin tile: the shared avatar (declared icon over its monogram). Kept as a
// named helper because the Extensions rows spell the no-icon case out.
@Composable
internal fun MonogramTile(name: String) {
    PluginAvatar(name = name, iconUrl = null)
}

@Composable
internal fun sourceDisplayName(source: CatalogSource): String = when {
    source.id == LocalSources.Books -> stringResource(R.string.finder_local_books_title)
    source.id == LocalSources.Manga -> stringResource(R.string.finder_local_manga_title)
    else -> source.name
}

// Icon tile of a Finder row: plugins get the monogram, local sources a
// folder glyph on the same soft accent tile.
@Composable
internal fun SourceIconTile(source: CatalogSource) {
    if (!source.local) {
        PluginAvatar(name = source.name, iconUrl = source.iconUrl)
        return
    }
    val extra = LocalMiruExtraColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MiruSize.Control)
            .clip(RoundedCornerShape(MiruRadius.Control))
            .background(extra.accent.copy(alpha = 0.14f)),
    ) {
        Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = extra.accent,
            modifier = Modifier.size(22.dp),
        )
    }
}
