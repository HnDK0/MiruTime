package dev.mirutime.app.features.video.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SettingsSliderRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SettingsValueRow
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
        VideoSectionHeader(stringResource(R.string.video_section_playback))
        VideoChipLabel(stringResource(R.string.video_speed))
        VideoChipRow {
            VideoSpeedOptions.forEach { option ->
                VideoChoiceChip(
                    text = speedLabel(option),
                    selected = option == speed,
                    onClick = { onSpeed(option) },
                )
            }
        }
        VideoChipLabel(stringResource(R.string.video_seek_step))
        VideoChipRow {
            VideoSeekStepOptions.forEach { option ->
                VideoChoiceChip(
                    text = stringResource(R.string.video_seek_step_fmt, option / 1000),
                    selected = option == AppSettings.videoSeekStepMs,
                    onClick = { AppSettings.videoSeekStepMs = option },
                )
            }
        }
        VideoChipLabel(stringResource(R.string.video_auto_hide))
        VideoChipRow {
            VideoAutoHideOptions.forEach { option ->
                VideoChoiceChip(
                    text = if (option == 0) {
                        stringResource(R.string.video_auto_hide_never)
                    } else {
                        stringResource(R.string.video_seconds_value, secondsLabel(option))
                    },
                    selected = option == AppSettings.videoControllerAutoHideMs,
                    onClick = { AppSettings.videoControllerAutoHideMs = option },
                )
            }
        }
        SettingsSwitchRow(
            title = stringResource(R.string.video_auto_next),
            subtitle = stringResource(R.string.video_auto_next_desc),
            checked = AppSettings.videoAutoNext,
            onChange = { AppSettings.videoAutoNext = it },
            horizontalPadding = 0.dp,
        )

        VideoSectionHeader(stringResource(R.string.video_section_video))
        // The variant value can be a full "server · quality · language" label;
        // split the row 50/50 so the label never collapses to one letter.
        VideoVariantValueRow(
            value = currentVariantLabel,
            onClick = onVariantClick,
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

        VideoSectionHeader(stringResource(R.string.video_section_gestures))
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

        VideoSectionHeader(stringResource(R.string.video_section_audio))
        SettingsValueRow(
            title = stringResource(R.string.video_audio),
            value = currentAudioLabel,
            valueColor = muted,
            enabled = audioEnabled,
            onClick = onAudioClick,
            horizontalPadding = 0.dp,
        )

        VideoSectionHeader(stringResource(R.string.video_section_subtitles))
        SettingsValueRow(
            title = stringResource(R.string.video_subtitle_track),
            value = currentSubtitleLabel,
            valueColor = muted,
            enabled = subtitlesEnabled,
            onClick = onSubtitlesClick,
            horizontalPadding = 0.dp,
        )

        VideoSectionHeader(stringResource(R.string.video_section_screen))
        SettingsSwitchRow(
            title = stringResource(R.string.video_keep_screen),
            subtitle = stringResource(R.string.video_keep_screen_desc),
            checked = keepScreenOn,
            onChange = onKeepScreenOn,
            horizontalPadding = 0.dp,
        )
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
            VideoSectionHeader(stringResource(R.string.video_quality))
            quality.forEach { choice ->
                VideoVariantRow(
                    choice = choice,
                    selected = choice.index == selectedStreamIndex,
                    onSelect = { onSelect(choice.index) },
                )
            }
        }
        if (voice.isNotEmpty()) {
            VideoSectionHeader(stringResource(R.string.video_voiceover))
            voice.forEach { choice ->
                VideoVariantRow(
                    choice = choice,
                    selected = choice.index == selectedStreamIndex,
                    onSelect = { onSelect(choice.index) },
                )
            }
        }
    }
}

@Composable
private fun VideoVariantValueRow(
    value: String,
    onClick: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(vertical = MiruSpace.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        Text(
            text = stringResource(R.string.video_variant),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = extra.muted,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = extra.muted,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun VideoSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = MiruSpace.M, bottom = MiruSpace.Xs),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoChipRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MiruSpace.Xs),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
    ) {
        content()
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
private fun VideoChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = text, maxLines = 2) },
        // Material's filter chip already adds a 48dp minimum interactive
        // component; the explicit token keeps the target stable at 200% font.
        modifier = Modifier.heightIn(min = MiruSize.Touch),
    )
}

@Composable
private fun VideoVariantRow(
    choice: VideoVariantChoice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onSelect)
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
