package dev.mirutime.feature.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.reader.MangaColorFilter
import dev.mirutime.feature.reader.MangaPagedNavigation
import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.MangaReadingDirection
import dev.mirutime.feature.reader.MangaReadingMode
import dev.mirutime.feature.reader.MangaWebtoonPadding
import dev.mirutime.feature.reader.R
import dev.mirutime.uikit.ChipOption
import dev.mirutime.uikit.ChipRow
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SettingsSliderRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SheetSection
import kotlin.math.roundToInt

// Manga-only settings. Every control here maps to a viewer behavior; text
// typography and TTS never appear in this sheet. Reset lives in the sheet
// header, never as a section at the bottom.
@Composable
fun MangaReaderSettingsControls(
    style: MangaReaderStyle,
    onStyleChange: (MangaReaderStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val update: (MangaReaderStyle) -> Unit = { onStyleChange(it.sanitized()) }
    val modeLabel = stringResource(
        if (style.mode == MangaReadingMode.Paged) R.string.rd_manga_paged else R.string.rd_manga_webtoon,
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SheetSection(title = stringResource(R.string.rd_manga_mode), summary = modeLabel) {
            ChipRow(
                options = listOf(
                    ChipOption(
                        MangaReadingMode.Paged.name,
                        stringResource(R.string.rd_manga_paged),
                    ),
                    ChipOption(
                        MangaReadingMode.Webtoon.name,
                        stringResource(R.string.rd_manga_webtoon),
                    ),
                ),
                selectedIds = setOf(style.mode.name),
                onToggle = { update(style.copy(mode = MangaReadingMode.valueOf(it))) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(vertical = MiruSpace.S),
            )
        }

        if (style.mode == MangaReadingMode.Paged) {
            SheetSection(
                title = stringResource(R.string.rd_manga_navigation),
                summary = navigationLabel(style.pagedNavigation),
            ) {
                ChipRow(
                    options = MangaPagedNavigation.entries.map {
                        ChipOption(it.name, navigationLabel(it))
                    },
                    selectedIds = setOf(style.pagedNavigation.name),
                    onToggle = {
                        update(style.copy(pagedNavigation = MangaPagedNavigation.valueOf(it)))
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.padding(vertical = MiruSpace.S),
                )
            }
        }

        SheetSection(title = stringResource(R.string.rd_manga_display)) {
            if (style.mode == MangaReadingMode.Paged) {
                ChipRow(
                    options = listOf(
                        ChipOption(
                            MangaReadingDirection.Ltr.name,
                            stringResource(R.string.rd_manga_ltr),
                        ),
                        ChipOption(
                            MangaReadingDirection.Rtl.name,
                            stringResource(R.string.rd_manga_rtl),
                        ),
                    ),
                    selectedIds = setOf(style.direction.name),
                    onToggle = {
                        update(style.copy(direction = MangaReadingDirection.valueOf(it)))
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.padding(vertical = MiruSpace.S),
                )
                if (style.pagedNavigation != MangaPagedNavigation.Swipe) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.rd_manga_invert_taps),
                        checked = style.tapZoneInvert,
                        onChange = { update(style.copy(tapZoneInvert = it)) },
                        horizontalPadding = 0.dp,
                    )
                }
            }
            Text(
                text = stringResource(R.string.rd_manga_double_tap),
                style = MaterialTheme.typography.labelSmall,
                color = LocalMiruExtraColors.current.muted,
                modifier = Modifier.padding(top = MiruSpace.S),
            )
            ChipRow(
                options = listOf(
                    ChipOption("none", stringResource(R.string.rd_manga_double_tap_none)),
                    ChipOption("menu", stringResource(R.string.rd_manga_double_tap_menu)),
                ),
                selectedIds = setOf(if (style.doubleTapMenu) "menu" else "none"),
                onToggle = { choice ->
                    update(style.copy(doubleTapMenu = choice == "menu"))
                },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(bottom = MiruSpace.S),
            )
            if (style.mode == MangaReadingMode.Paged) {
                SettingsSwitchRow(
                    title = stringResource(R.string.rd_manga_page_transition),
                    checked = style.pageTransition,
                    onChange = { update(style.copy(pageTransition = it)) },
                    horizontalPadding = 0.dp,
                )
            }
        }

        if (style.mode == MangaReadingMode.Webtoon) {
            SheetSection(
                title = stringResource(R.string.rd_manga_padding_title),
                summary = webtoonPaddingLabel(style.webtoonPadding),
            ) {
                Text(
                    text = stringResource(R.string.rd_manga_padding_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(bottom = MiruSpace.S),
                )
                ChipRow(
                    options = MangaWebtoonPadding.entries.map {
                        ChipOption(it.name, webtoonPaddingLabel(it))
                    },
                    selectedIds = setOf(style.webtoonPadding.name),
                    onToggle = {
                        update(style.copy(webtoonPadding = MangaWebtoonPadding.valueOf(it)))
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.padding(bottom = MiruSpace.S),
                )
            }
        }

        SheetSection(
            title = stringResource(R.string.rd_manga_page_counter),
            summary = stringResource(if (style.showPageCounter) R.string.rd_on else R.string.rd_off),
        ) {
            Text(
                text = stringResource(R.string.rd_manga_counter_desc),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
                modifier = Modifier.padding(bottom = MiruSpace.Xs),
            )
            SettingsSwitchRow(
                title = stringResource(R.string.rd_manga_counter_show),
                checked = style.showPageCounter,
                onChange = { update(style.copy(showPageCounter = it)) },
                horizontalPadding = 0.dp,
            )
        }

        SheetSection(
            title = stringResource(R.string.rd_manga_image),
            summary = "${(style.brightness * 100).roundToInt()}%",
        ) {
            SettingsSliderRow(
                title = stringResource(R.string.rd_manga_brightness),
                valueText = "${(style.brightness * 100).roundToInt()}%",
                value = style.brightness,
                range = MangaReaderStyle.MinBrightness..MangaReaderStyle.MaxBrightness,
                steps = 7,
                onChange = { update(style.copy(brightness = it)) },
                horizontalPadding = 0.dp,
            )
            ChipRow(
                options = MangaColorFilter.entries.map {
                    ChipOption(it.name, colorFilterLabel(it))
                },
                selectedIds = setOf(style.colorFilter.name),
                onToggle = { update(style.copy(colorFilter = MangaColorFilter.valueOf(it))) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(vertical = MiruSpace.S),
            )
        }
    }
}

@Composable
private fun navigationLabel(navigation: MangaPagedNavigation): String = stringResource(
    when (navigation) {
        MangaPagedNavigation.Swipe -> R.string.rd_manga_nav_swipe
        MangaPagedNavigation.TapEdges -> R.string.rd_manga_nav_tap
        MangaPagedNavigation.Both -> R.string.rd_manga_nav_both
    },
)

@Composable
private fun webtoonPaddingLabel(padding: MangaWebtoonPadding): String = stringResource(
    when (padding) {
        MangaWebtoonPadding.None -> R.string.rd_manga_padding_none
        MangaWebtoonPadding.Small -> R.string.rd_manga_padding_small
        MangaWebtoonPadding.Medium -> R.string.rd_manga_padding_medium
        MangaWebtoonPadding.Large -> R.string.rd_manga_padding_large
    },
)

@Composable
private fun colorFilterLabel(filter: MangaColorFilter): String = stringResource(
    when (filter) {
        MangaColorFilter.None -> R.string.rd_manga_filter_none
        MangaColorFilter.Grayscale -> R.string.rd_manga_filter_gray
        MangaColorFilter.Sepia -> R.string.rd_manga_filter_sepia
        MangaColorFilter.Invert -> R.string.rd_manga_filter_invert
    },
)
