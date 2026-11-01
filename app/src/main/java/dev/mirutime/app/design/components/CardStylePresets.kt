package dev.mirutime.app.design.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.mirutime.app.R
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.uikit.MiruCardPreset

// One mapping shared by the library card, the picker, Settings and the
// dashboard. Legacy persisted values stay readable; Share is no longer a
// selectable preset (it was the flat twin of Mat).
internal val GridStyles = listOf(
    CardStyle.Mat,
    CardStyle.Above,
    CardStyle.OnCover,
    CardStyle.OnCoverAbove,
    CardStyle.ProgressAbove,
)

internal fun CardStyle.toPreset(): MiruCardPreset = when (this) {
    CardStyle.Mat -> MiruCardPreset.Mat
    CardStyle.Above -> MiruCardPreset.Above
    CardStyle.OnCover -> MiruCardPreset.OnCover
    CardStyle.OnCoverAbove -> MiruCardPreset.OnCoverAbove
    CardStyle.ProgressAbove -> MiruCardPreset.ProgressAbove
    // Legacy persisted values, kept readable for backup/restore.
    CardStyle.CoverTitle -> MiruCardPreset.OnCover
    CardStyle.BelowCover -> MiruCardPreset.Mat
    CardStyle.Stats -> MiruCardPreset.ProgressAbove
    CardStyle.Share -> MiruCardPreset.Mat
    CardStyle.Horizontal -> MiruCardPreset.List
}

@Composable
fun cardStyleLabel(style: CardStyle): String = stringResource(
    when (style) {
        CardStyle.Above -> R.string.card_style_above
        CardStyle.Share -> R.string.card_style_share
        CardStyle.OnCover, CardStyle.CoverTitle -> R.string.card_style_on_cover
        CardStyle.OnCoverAbove -> R.string.card_style_on_cover_above
        CardStyle.ProgressAbove, CardStyle.Stats -> R.string.card_style_progress_above
        CardStyle.Horizontal -> R.string.card_style_list
        CardStyle.Mat, CardStyle.BelowCover -> R.string.card_style_mat
    },
)

// The list layout is not a grid choice; it always renders CardStyle.Horizontal.
internal fun CardStyle.isGridPreset(): Boolean = this != CardStyle.Horizontal
