package dev.mirutime.app.features.video.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.video.domain.VideoVariantChoice
import dev.mirutime.app.features.video.domain.VideoVariantStatus
import dev.mirutime.app.features.video.domain.isVideoResolutionLabel
import dev.mirutime.app.features.video.domain.videoVariantChoices
import dev.mirutime.core.api.VideoStream
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.ChipOption
import dev.mirutime.uikit.ChipRow
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SettingsSliderRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.uikit.SheetSection
import java.util.Locale
import kotlin.math.roundToInt

val VideoSpeedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

val VideoSeekStepOptions = listOf(5_000, 10_000, 15_000, 30_000)

// 3.5 s is the pre-setting player timeout; "never" keeps the controls until
// the user taps the video (media3 semantics for a non-positive timeout).
val VideoAutoHideOptions = listOf(3_500, 5_000, 10_000, 15_000, 0)

private const val DragThresholdMin = 0.02f
private const val DragThresholdMax = 0.20f
// 0.18 width / 0.01 = 18 intervals, hence 17 interior stops.
private const val DragThresholdSteps = 17

/** One selectable audio/text track; `trackGroup == null` is the Off choice. */
data class VideoTrackOption(
    val key: String,
    val label: String,
    val trackGroup: TrackGroup? = null,
    val trackIndex: Int = 0,
    val selected: Boolean = false,
)

fun trackOptionLabel(format: Format): String =
    format.label?.takeIf { it.isNotBlank() }
        ?: format.language?.takeIf { it.isNotBlank() }
        ?: ""

/** HLS video levels expose height, not a label/language. */
fun videoTrackLabel(format: Format): String =
    format.label?.takeIf { it.isNotBlank() }
        ?: format.height.takeIf { it > 0 }?.let { "${it}p" }
        ?: ""

fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) {
        "${speed.toInt()}x"
    } else {
        "${String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')}x"
    }

private fun secondsLabel(ms: Int): String =
    if (ms % 1_000 == 0) {
        (ms / 1_000).toString()
    } else {
        String.format(Locale.ROOT, "%.1f", ms / 1_000f)
    }

/**
 * Player settings panel, matching NoveLA's `VideoSettingsPanel`: playback
 * chips first, then Video (variant and HLS resolution rows), Gestures, Audio,
 * Subtitles and Screen. Rows show the current value and open a chooser; the
 * panel itself never becomes a wall of inline radio lists.
 */
@Composable
fun VideoSettingsSheet(
    currentVariantLabel: String,
    currentResolutionLabel: String?,
    currentAudioLabel: String,
    audioEnabled: Boolean,
    currentSubtitleLabel: String,
    subtitlesEnabled: Boolean,
    speed: Float,
    keepScreenOn: Boolean,
    onSpeed: (Float) -> Unit,
    onVariantClick: () -> Unit,
    onResolutionClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSubtitlesClick: () -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val muted = LocalMiruExtraColors.current.muted
    AppBottomSheet(
        title = stringResource(R.string.video_settings),
        description = stringResource(R.string.video_settings_desc),
        onClose = onClose,
    ) {
        SheetSection(
            title = stringResource(R.string.video_section_playback),
            summary = speedLabel(speed),
        ) {
            VideoChipLabel(stringResource(R.string.video_speed))
            ChipRow(
                options = VideoSpeedOptions.map { ChipOption(it.toString(), speedLabel(it)) },
                selectedIds = setOf(speed.toString()),
                onToggle = { onSpeed(it.toFloat()) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(bottom = MiruSpace.Xs),
            )
            VideoChipLabel(stringResource(R.string.video_seek_step))
            ChipRow(
                options = VideoSeekStepOptions.map {
                    ChipOption(
                        it.toString(),
                        stringResource(R.string.video_seek_step_fmt, it / 1000),
                    )
                },
                selectedIds = setOf(AppSettings.videoSeekStepMs.toString()),
                onToggle = { AppSettings.videoSeekStepMs = it.toInt() },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(bottom = MiruSpace.Xs),
            )
            VideoChipLabel(stringResource(R.string.video_auto_hide))
            ChipRow(
                options = VideoAutoHideOptions.map { option ->
                    ChipOption(
                        option.toString(),
                        if (option == 0) {
                            stringResource(R.string.video_auto_hide_never)
                        } else {
                            stringResource(R.string.video_seconds_value, secondsLabel(option))
                        },
                    )
                },
                selectedIds = setOf(AppSettings.videoControllerAutoHideMs.toString()),
                onToggle = { AppSettings.videoControllerAutoHideMs = it.toInt() },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(bottom = MiruSpace.S),
            )
            SettingsSwitchRow(
                title = stringResource(R.string.video_auto_next),
                subtitle = stringResource(R.string.video_auto_next_desc),
                checked = AppSettings.videoAutoNext,
                onChange = { AppSettings.videoAutoNext = it },
                horizontalPadding = 0.dp,
            )
        }

        SheetSection(
            title = stringResource(R.string.video_section_video),
            summary = currentVariantLabel,
        ) {
            SettingsValueRow(
                title = stringResource(R.string.video_variant),
                value = currentVariantLabel,
                valueColor = muted,
                onClick = onVariantClick,
                horizontalPadding = 0.dp,
            )
            // One HLS level is not a choice: the row is absent, not greyed.
            if (currentResolutionLabel != null) {
                SettingsValueRow(
                    title = stringResource(R.string.video_resolution),
                    value = currentResolutionLabel,
                    valueColor = muted,
                    onClick = onResolutionClick,
                    horizontalPadding = 0.dp,
                )
            }
        }

        SheetSection(title = stringResource(R.string.video_section_gestures)) {
            SettingsSwitchRow(
                title = stringResource(R.string.video_gesture_double_tap),
                checked = AppSettings.videoGestureDoubleTap,
                onChange = { AppSettings.videoGestureDoubleTap = it },
                horizontalPadding = 0.dp,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.video_gesture_vertical_swipe),
                checked = AppSettings.videoGestureVerticalSwipe,
                onChange = { AppSettings.videoGestureVerticalSwipe = it },
                horizontalPadding = 0.dp,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.video_gesture_long_press),
                checked = AppSettings.videoGestureLongPressSpeed,
                onChange = { AppSettings.videoGestureLongPressSpeed = it },
                horizontalPadding = 0.dp,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.video_gesture_swapped_sides),
                subtitle = stringResource(R.string.video_gesture_swapped_sides_desc),
                checked = AppSettings.videoGestureSwappedSides,
                onChange = { AppSettings.videoGestureSwappedSides = it },
                horizontalPadding = 0.dp,
            )
            SettingsSliderRow(
                title = stringResource(R.string.video_gesture_sensitivity),
                subtitle = stringResource(R.string.video_gesture_sensitivity_desc),
                valueText = "${(AppSettings.videoGestureDragThreshold * 100).roundToInt()}%",
                value = AppSettings.videoGestureDragThreshold,
                range = DragThresholdMin..DragThresholdMax,
                steps = DragThresholdSteps,
                onChange = {
                    AppSettings.videoGestureDragThreshold = (it * 100).roundToInt() / 100f
                },
                horizontalPadding = 0.dp,
            )
        }

        SheetSection(
            title = stringResource(R.string.video_section_audio),
            summary = currentAudioLabel,
        ) {
            SettingsValueRow(
                title = stringResource(R.string.video_audio),
                value = currentAudioLabel,
                valueColor = muted,
                enabled = audioEnabled,
                onClick = onAudioClick,
                horizontalPadding = 0.dp,
            )
        }

        SheetSection(
            title = stringResource(R.string.video_section_subtitles),
            summary = currentSubtitleLabel,
        ) {
            SettingsValueRow(
                title = stringResource(R.string.video_subtitle_track),
                value = currentSubtitleLabel,
                valueColor = muted,
                enabled = subtitlesEnabled,
                onClick = onSubtitlesClick,
                horizontalPadding = 0.dp,
            )
        }

        SheetSection(title = stringResource(R.string.video_section_screen)) {
            SettingsSwitchRow(
                title = stringResource(R.string.video_keep_screen),
                subtitle = stringResource(R.string.video_keep_screen_desc),
                checked = keepScreenOn,
                onChange = onKeepScreenOn,
                horizontalPadding = 0.dp,
            )
        }
    }
}

/**
 * Variant chooser, split into Quality and Voice like NoveLA's `VariantDialog`.
 * Probe status survives in the badge; selection is applied by the caller.
 */
@Composable
fun VideoVariantSheet(
    streams: List<VideoStream>,
    selectedStreamIndex: Int,
    probeStatuses: Map<String, VideoVariantStatus>,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    deadDisabled: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val choices = videoVariantChoices(streams, probeStatuses)
    val quality = choices.filter { isVideoResolutionLabel(it.label) }
    val voice = choices.filterNot { isVideoResolutionLabel(it.label) }
    AppBottomSheet(
        title = stringResource(R.string.video_variant_title),
        description = stringResource(R.string.video_quality_choose_desc),
        onClose = onClose,
    ) {
        if (quality.isNotEmpty()) {
            SheetSection(title = stringResource(R.string.video_quality)) {
                quality.forEach { choice ->
                    VideoVariantRow(
                        choice = choice,
                        selected = choice.index == selectedStreamIndex,
                        enabled = !(deadDisabled && choice.dead),
                        onSelect = { onSelect(choice.index) },
                    )
                }
            }
        }
        if (voice.isNotEmpty()) {
            SheetSection(title = stringResource(R.string.video_voiceover)) {
                voice.forEach { choice ->
                    VideoVariantRow(
                        choice = choice,
                        selected = choice.index == selectedStreamIndex,
                        enabled = !(deadDisabled && choice.dead),
                        onSelect = { onSelect(choice.index) },
                    )
                }
            }
        }
        if (onRefresh != null) {
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.video_variant_recheck))
            }
        }
    }
}

@Composable
private fun VideoChipLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = MiruSpace.S, bottom = MiruSpace.Xs),
    )
}

@Composable
private fun VideoVariantRow(
    choice: VideoVariantChoice,
    selected: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(vertical = MiruSpace.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
    ) {
        Text(
            text = choice.label.ifBlank {
                stringResource(R.string.video_variant_fallback, choice.index + 1)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) extra.accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (choice.dead) {
            Text(
                text = stringResource(R.string.video_variant_dead),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = extra.accent,
            )
        }
    }
}
