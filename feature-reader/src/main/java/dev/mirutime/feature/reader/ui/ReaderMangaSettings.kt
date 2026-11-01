package dev.mirutime.feature.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.reader.R
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SheetSection
import dev.mirutime.uikit.SettingsSliderRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.feature.reader.MangaColorFilter
import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.MangaReadingDirection
import dev.mirutime.feature.reader.MangaReadingMode
import dev.mirutime.feature.reader.MangaWebtoonPadding
import kotlin.math.roundToInt

// Manga-only settings. Every control here maps to a viewer behavior; text
// typography and TTS never appear in this sheet.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MangaReaderSettingsControls(
    style: MangaReaderStyle,
    onStyleChange: (MangaReaderStyle) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val update: (MangaReaderStyle) -> Unit = { onStyleChange(it.sanitized()) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        SheetSection(title = stringResource(R.string.rd_manga_mode)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp, vertical = MiruSpace.S),
            ) {
                FilterChip(
                    selected = style.mode == MangaReadingMode.Paged,
                    onClick = { update(style.copy(mode = MangaReadingMode.Paged)) },
                    label = { Text(stringResource(R.string.rd_manga_paged)) },
                )
                FilterChip(
                    selected = style.mode == MangaReadingMode.Webtoon,
                    onClick = { update(style.copy(mode = MangaReadingMode.Webtoon)) },
                    label = { Text(stringResource(R.string.rd_manga_webtoon)) },
                )
            }
        }

        SheetSection(title = stringResource(R.string.rd_manga_display)) {
            if (style.mode == MangaReadingMode.Paged) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp, vertical = MiruSpace.S),
                ) {
                    FilterChip(
                        selected = style.direction == MangaReadingDirection.Ltr,
                        onClick = {
                            update(style.copy(direction = MangaReadingDirection.Ltr))
                        },
                        label = { Text(stringResource(R.string.rd_manga_ltr)) },
                    )
                    FilterChip(
                        selected = style.direction == MangaReadingDirection.Rtl,
                        onClick = {
                            update(style.copy(direction = MangaReadingDirection.Rtl))
                        },
                        label = { Text(stringResource(R.string.rd_manga_rtl)) },
                    )
                }
                SettingsSwitchRow(
                    title = stringResource(R.string.rd_manga_invert_taps),
                    checked = style.tapZoneInvert,
                    onChange = { update(style.copy(tapZoneInvert = it)) },
                    horizontalPadding = 0.dp,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.rd_manga_double_tap_zoom),
                    checked = style.doubleTapZoom,
                    onChange = { update(style.copy(doubleTapZoom = it)) },
                    horizontalPadding = 0.dp,
                )
            }
            SettingsSwitchRow(
                title = stringResource(R.string.rd_manga_page_counter),
                checked = style.showPageCounter,
                onChange = { update(style.copy(showPageCounter = it)) },
                horizontalPadding = 0.dp,
            )
            if (style.mode == MangaReadingMode.Webtoon) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp, vertical = MiruSpace.S),
                ) {
                    MangaWebtoonPadding.entries.forEach { padding ->
                        FilterChip(
                            selected = style.webtoonPadding == padding,
                            onClick = { update(style.copy(webtoonPadding = padding)) },
                            label = { Text(webtoonPaddingLabel(padding)) },
                        )
                    }
                }
            }
        }

        SheetSection(title = stringResource(R.string.rd_manga_image)) {
            SettingsSliderRow(
                title = stringResource(R.string.rd_manga_brightness),
                valueText = "${(style.brightness * 100).roundToInt()}%",
                value = style.brightness,
                range = MangaReaderStyle.MinBrightness..MangaReaderStyle.MaxBrightness,
                steps = 7,
                onChange = { update(style.copy(brightness = it)) },
                horizontalPadding = 0.dp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp, vertical = MiruSpace.S),
            ) {
                MangaColorFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = style.colorFilter == filter,
                        onClick = { update(style.copy(colorFilter = filter)) },
                        label = { Text(colorFilterLabel(filter)) },
                    )
                }
            }
        }

        SheetSection(title = stringResource(R.string.rd_reset)) {
            SettingsValueRow(
                title = stringResource(R.string.rd_manga_reset),
                value = "",
                onClick = onReset,
                horizontalPadding = 0.dp,
            )
        }
    }
}

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
