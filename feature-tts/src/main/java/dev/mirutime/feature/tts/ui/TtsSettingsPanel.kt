package dev.mirutime.feature.tts.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.tts.Voice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.tts.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.ChipRail
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.SheetHeaderAction
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruCompactTextField
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSlider
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.feature.tts.TtsCapabilities
import dev.mirutime.feature.tts.TtsMaxPitch
import dev.mirutime.feature.tts.TtsMaxSpeed
import dev.mirutime.feature.tts.TtsMinPitch
import dev.mirutime.feature.tts.TtsMinSpeed
import dev.mirutime.feature.tts.TtsPitchStep
import dev.mirutime.feature.tts.TtsSavedVoice
import dev.mirutime.feature.tts.TtsSettings
import dev.mirutime.feature.tts.TtsSleepTimerMaxChapters
import dev.mirutime.feature.tts.TtsSleepTimerMaxMinutes
import dev.mirutime.feature.tts.TtsSleepTimerMode
import dev.mirutime.feature.tts.TtsSleepTimerState
import dev.mirutime.feature.tts.TtsSpeedStep
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.TtsVoice
import dev.mirutime.feature.tts.applySavedVoice
import dev.mirutime.feature.tts.ttsAddSavedVoice
import dev.mirutime.feature.tts.ttsFilterSavedVoices
import dev.mirutime.feature.tts.ttsFilterVoices
import dev.mirutime.feature.tts.ttsGroupSavedVoices
import dev.mirutime.feature.tts.ttsGroupVoicesByEngine
import dev.mirutime.feature.tts.ttsIsVoiceFavorite
import dev.mirutime.feature.tts.ttsSliderSteps
import dev.mirutime.feature.tts.ttsSortVoices
import dev.mirutime.feature.tts.ttsToggleVoiceFavorite
import dev.mirutime.feature.tts.ttsVoiceLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private val TtsSpeedSliderSteps = ttsSliderSteps(TtsMinSpeed..TtsMaxSpeed, TtsSpeedStep)
private val TtsPitchSliderSteps = ttsSliderSteps(TtsMinPitch..TtsMaxPitch, TtsPitchStep)
private const val VoiceSearchDebounceMs = 220L

private enum class TtsSettingSection { Engine, AutoNext, SleepTimer, Highlight, TestReset }

// Secondary TTS surfaces. They replace the settings sheet instead of stacking
// a second ModalBottomSheet on top of it (one sheet window at a time).
private enum class TtsOverlay { Voices, SavedVoices, DeleteSavedVoice }

/**
 * Compact player pinned above a reader settings sheet. It is hosted by the
 * sheet itself (not the page behind it) so the scrim can never cover it.
 * Stop clears the TTS session; the settings sheet stays usable underneath.
 */
@Composable
fun TtsMiniPlayer(
    status: TtsStatus,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onStartHere: () -> Unit,
    onFocus: () -> Unit,
    onPrevParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = status == TtsStatus.Playing || status == TtsStatus.Loading
    val session = playing || status == TtsStatus.Paused
    val shape = RoundedCornerShape(18.dp)
    val accent = MaterialTheme.colorScheme.primary

    // Thin bar: playback + Start here/Focus/Next chapter. Chapter number and
    // progress live in the reader chrome, not here (FR-054/FR-055).
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.16f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TtsPlayPauseButton(
                playing = playing,
                onClick = onToggle,
                size = 40.dp,
                iconSize = 22.dp,
            )
            MiniIconAction(
                icon = Icons.Filled.KeyboardArrowLeft,
                description = stringResource(R.string.rd_tts_prev_paragraph),
                enabled = session,
                onClick = onPrevParagraph,
            )
            MiniIconAction(
                icon = Icons.Filled.KeyboardArrowRight,
                description = stringResource(R.string.rd_tts_next_paragraph),
                enabled = session,
                onClick = onNextParagraph,
            )
            MiniIconAction(
                icon = Icons.Filled.TouchApp,
                description = stringResource(R.string.rd_tts_start_here),
                onClick = onStartHere,
            )
            MiniIconAction(
                icon = Icons.Filled.Search,
                description = stringResource(R.string.rd_tts_focus),
                enabled = session,
                onClick = onFocus,
            )
            Spacer(Modifier.weight(1f))
            if (session) MiniStopButton(onStop = onStop)
        }
    }
}

@Composable
private fun MiniIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun MiniStopButton(onStop: () -> Unit) {
    // Closes the TTS session: a larger red circle with a cross, no cramped
    // "Stop" caption that used to wrap in the thin bar.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .clickable(role = Role.Button, onClick = onStop),
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.rd_tts_stop),
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * The settings sheet in the reader: settings only (speed, pitch, voices,
 * engine, auto-next, highlight, test/reset) plus ONE primary Play/Pause to
 * start the session. The full transport (chapter/chunk, Start here, Focus)
 * lives in the mini and floating players, never here (FR-054, 6.6).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TtsSettingsPanel(
    settings: TtsSettings,
    capabilities: TtsCapabilities,
    status: TtsStatus,
    message: String?,
    highlight: Boolean,
    highlightColor: Long,
    highlightColors: List<Long>,
    onSettingsChange: (TtsSettings) -> Unit,
    onTest: (String) -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onToggle: () -> Unit,
    onHighlightChange: (Boolean) -> Unit,
    onHighlightColorChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    onDiscoverVoices: () -> Unit = {},
    sleepTimer: StateFlow<TtsSleepTimerState?>? = null,
    onSleepTimerMinutes: (Int) -> Unit = {},
    onSleepTimerChapters: (Int) -> Unit = {},
    onSleepTimerCancel: () -> Unit = {},
) {
    val ready = capabilities.ready
    val failed = status == TtsStatus.Failed
    val playing = status == TtsStatus.Playing || status == TtsStatus.Loading
    val testText = stringResource(R.string.rd_tts_test_text)
    var section by rememberSaveable { mutableStateOf<TtsSettingSection?>(null) }
    var overlay by rememberSaveable { mutableStateOf<TtsOverlay?>(null) }
    // Which voice slot the picker edits: the original-language voice or the
    // independent translated-text voice (FR-040).
    var voiceSlotTranslated by rememberSaveable { mutableStateOf(false) }
    var deleteIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    // The floating player needs the system overlay permission; ask in our own
    // sheet (never AlertDialog) and enable the switch only after the grant.
    val context = LocalContext.current
    var overlayPrompt by rememberSaveable { mutableStateOf(false) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            onSettingsChange(settings.copy(floatingPlayer = true))
        }
    }
    // The engine accordion must also see engines installed after app start.
    LaunchedEffect(Unit) { onDiscoverVoices() }

    when (overlay) {
        TtsOverlay.Voices -> {
            TtsVoicePickerSheet(
                capabilities = capabilities,
                settings = settings,
                onSettingsChange = onSettingsChange,
                currentVoiceId = if (voiceSlotTranslated) {
                    settings.translatedVoiceId
                } else {
                    settings.voiceId
                },
                currentEngineId = (if (voiceSlotTranslated) {
                    settings.translatedEngineId
                } else {
                    settings.engineId
                }) ?: capabilities.currentEngineId,
                failed = failed,
                onSelect = { voiceId, voiceEngineId ->
                    onSettingsChange(
                        if (voiceSlotTranslated) {
                            settings.copy(
                                translatedVoiceId = voiceId,
                                translatedEngineId = voiceEngineId,
                            )
                        } else {
                            settings.copy(voiceId = voiceId, engineId = voiceEngineId)
                        },
                    )
                    overlay = null
                },
                onDismiss = { overlay = null },
                onDiscover = onDiscoverVoices,
            )
            return
        }
        TtsOverlay.SavedVoices -> {
            TtsSavedVoicesSheet(
                settings = settings,
                capabilities = capabilities,
                onSettingsChange = onSettingsChange,
                onDeleteRequest = { index ->
                    deleteIndex = index
                    overlay = TtsOverlay.DeleteSavedVoice
                },
                onDismiss = { overlay = null },
            )
            return
        }
        TtsOverlay.DeleteSavedVoice -> {
            TtsDeleteSavedVoiceSheet(
                voice = deleteIndex?.let(settings.savedVoices::getOrNull),
                onDelete = {
                    val index = deleteIndex
                    if (index != null) {
                        onSettingsChange(
                            settings.copy(
                                savedVoices = settings.savedVoices
                                    .filterIndexed { i, _ -> i != index },
                            ),
                        )
                    }
                    deleteIndex = null
                    overlay = TtsOverlay.SavedVoices
                },
                onDismiss = {
                    deleteIndex = null
                    overlay = TtsOverlay.SavedVoices
                },
            )
            return
        }
        null -> Unit
    }

    AppBottomSheet(
        title = stringResource(R.string.rd_tts_panel_title),
        description = stringResource(R.string.rd_tts_panel_desc),
        onClose = onDismiss,
        // Reset lives in the header, not inside the "Test & reset" section.
        headerAction = {
            SheetHeaderAction(
                label = stringResource(R.string.rd_tts_reset),
                onClick = onReset,
            )
        },
    ) {
        if (!ready) {
            if (failed) {
                Text(
                    text = stringResource(R.string.rd_tts_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                TtsInlineLoading(stringResource(R.string.rd_tts_voices_loading))
            }
        } else if (failed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = MiruSpace.S),
            ) {
                Text(
                    text = message ?: stringResource(R.string.rd_tts_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.rd_tts_retry))
                }
            }
        }

        // Compact player: the two live parameters stay visible, the rest is
        // one chip row and one transport row (NoveLA's settings dialog).
        // Drag updates the draft only; the controller stops/re-queues the
        // current chunk once, on release.
        var speedDraft by remember(settings.speed) { mutableStateOf(settings.speed) }
        var pitchDraft by remember(settings.pitch) { mutableStateOf(settings.pitch) }
        TtsSliderRow(
            label = stringResource(R.string.rd_tts_speed),
            valueText = stringResource(R.string.rd_tts_speed_value, speedDraft),
        ) {
            MiruSlider(
                value = speedDraft,
                onValueChange = { speedDraft = it },
                valueRange = TtsMinSpeed..TtsMaxSpeed,
                steps = TtsSpeedSliderSteps,
                enabled = ready,
                contentDescription = stringResource(R.string.rd_tts_speed_desc),
                onValueChangeFinished = {
                    if (speedDraft != settings.speed) {
                        onSettingsChange(settings.copy(speed = speedDraft))
                    }
                },
            )
        }
        TtsSliderRow(
            label = stringResource(R.string.rd_tts_pitch),
            valueText = stringResource(R.string.rd_tts_pitch_value, pitchDraft),
        ) {
            MiruSlider(
                value = pitchDraft,
                onValueChange = { pitchDraft = it },
                valueRange = TtsMinPitch..TtsMaxPitch,
                steps = TtsPitchSliderSteps,
                enabled = ready,
                contentDescription = stringResource(R.string.rd_tts_pitch_desc),
                onValueChangeFinished = {
                    if (pitchDraft != settings.pitch) {
                        onSettingsChange(settings.copy(pitch = pitchDraft))
                    }
                },
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = MiruSpace.Xs),
        ) {
            TtsChip(
                label = stringResource(R.string.rd_tts_voices),
                icon = Icons.Outlined.RecordVoiceOver,
                enabled = ready,
                onClick = {
                    voiceSlotTranslated = false
                    overlay = TtsOverlay.Voices
                },
            )
            TtsChip(
                label = stringResource(R.string.rd_tts_voices_translation),
                icon = Icons.Outlined.Translate,
                enabled = ready,
                onClick = {
                    voiceSlotTranslated = true
                    overlay = TtsOverlay.Voices
                },
            )
            TtsChip(
                label = stringResource(R.string.rd_tts_saved_voices),
                icon = Icons.Outlined.Bookmarks,
                onClick = { overlay = TtsOverlay.SavedVoices },
            )
        }

        // One primary action as a full-width plate: start or pause the session.
        MiruButton(
            onClick = onToggle,
            style = MiruButtonStyle.Primary,
            leadingIcon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MiruSpace.Xs, bottom = MiruSpace.S),
        ) {
            Text(
                text = stringResource(
                    if (playing) R.string.rd_tts_action_pause else R.string.rd_tts_start_tts,
                ),
            )
        }

        MiruRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.rd_tts_mini_player),
            subtitle = stringResource(R.string.rd_tts_mini_player_desc),
            checked = settings.miniPlayerAlways,
            onChange = { onSettingsChange(settings.copy(miniPlayerAlways = it)) },
            horizontalPadding = 0.dp,
        )
        MiruRowDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.rd_tts_floating_player),
            subtitle = stringResource(R.string.rd_tts_floating_desc),
            checked = settings.floatingPlayer,
            onChange = { enabled ->
                when {
                    !enabled -> onSettingsChange(settings.copy(floatingPlayer = false))
                    Settings.canDrawOverlays(context) ->
                        onSettingsChange(settings.copy(floatingPlayer = true))
                    else -> overlayPrompt = true
                }
            },
            horizontalPadding = 0.dp,
        )
        MiruRowDivider()

        if (capabilities.engines.size > 1) {
            TtsAccordion(
                title = stringResource(R.string.rd_tts_engine),
                value = capabilities.engines.firstOrNull {
                    it.id == (settings.engineId ?: capabilities.currentEngineId)
                }?.label.orEmpty(),
                expanded = section == TtsSettingSection.Engine,
                onToggle = {
                    section = if (section == TtsSettingSection.Engine) {
                        null
                    } else {
                        TtsSettingSection.Engine
                    }
                },
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    capabilities.engines.forEach { engine ->
                        FilterChip(
                            selected = (settings.engineId ?: capabilities.currentEngineId) ==
                                engine.id,
                            // Switching away from a failed engine is how the
                            // user recovers, so this must not wait for ready.
                            onClick = { onSettingsChange(settings.copy(engineId = engine.id)) },
                            label = {
                                Text(
                                    text = if (engine.isDefault) {
                                        "${engine.label} · ${stringResource(R.string.rd_tts_engine_default)}"
                                    } else {
                                        engine.label
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        TtsAccordion(
            title = stringResource(R.string.rd_tts_auto_next),
            value = stringResource(if (settings.autoNextChapter) R.string.tts_state_on else R.string.tts_state_off),
            expanded = section == TtsSettingSection.AutoNext,
            onToggle = {
                section = if (section == TtsSettingSection.AutoNext) {
                    null
                } else {
                    TtsSettingSection.AutoNext
                }
            },
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.rd_tts_auto_next),
                subtitle = stringResource(R.string.rd_tts_auto_next_desc),
                checked = settings.autoNextChapter,
                onChange = { onSettingsChange(settings.copy(autoNextChapter = it)) },
                horizontalPadding = 0.dp,
            )
        }

        if (sleepTimer != null) {
            TtsSleepTimerAccordion(
                sleepTimer = sleepTimer,
                expanded = section == TtsSettingSection.SleepTimer,
                enabled = status == TtsStatus.Playing ||
                    status == TtsStatus.Loading ||
                    status == TtsStatus.Paused,
                onToggle = {
                    section = if (section == TtsSettingSection.SleepTimer) {
                        null
                    } else {
                        TtsSettingSection.SleepTimer
                    }
                },
                onMinutes = onSleepTimerMinutes,
                onChapters = onSleepTimerChapters,
                onCancel = onSleepTimerCancel,
            )
        }

        TtsAccordion(
            title = stringResource(R.string.rd_tts_highlight),
            value = stringResource(if (highlight) R.string.tts_state_on else R.string.tts_state_off),
            expanded = section == TtsSettingSection.Highlight,
            onToggle = {
                section = if (section == TtsSettingSection.Highlight) {
                    null
                } else {
                    TtsSettingSection.Highlight
                }
            },
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.rd_tts_highlight),
                subtitle = stringResource(R.string.rd_tts_highlight_desc),
                checked = highlight,
                onChange = onHighlightChange,
                horizontalPadding = 0.dp,
            )
            Text(
                text = stringResource(R.string.tts_highlight_color),
                style = MaterialTheme.typography.labelSmall,
                color = LocalMiruExtraColors.current.muted,
                modifier = Modifier.padding(top = MiruSpace.S),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.Xs),
            ) {
                highlightColors.forEach { color ->
                    TtsHighlightDot(
                        color = color,
                        selected = highlightColor == color,
                        onClick = { onHighlightColorChange(color) },
                    )
                }
            }
        }

        TtsAccordion(
            title = stringResource(R.string.rd_tts_test_reset),
            value = "",
            expanded = section == TtsSettingSection.TestReset,
            onToggle = {
                section = if (section == TtsSettingSection.TestReset) {
                    null
                } else {
                    TtsSettingSection.TestReset
                }
            },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { onTest(testText) },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.rd_tts_test))
                }
            }
        }
    }

    if (overlayPrompt) {
        AppBottomSheet(
            title = stringResource(R.string.rd_tts_overlay_permission_title),
            description = stringResource(R.string.rd_tts_overlay_permission_desc),
            onClose = { overlayPrompt = false },
            doneLabel = stringResource(R.string.rd_tts_overlay_permission_open),
            closeLabel = stringResource(R.string.rd_tts_cancel),
            onDone = {
                overlayPrompt = false
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
            content = {},
        )
    }
}

@Composable
private fun TtsSleepTimerAccordion(
    sleepTimer: StateFlow<TtsSleepTimerState?>,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onMinutes: (Int) -> Unit,
    onChapters: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val timer by sleepTimer.collectAsState()
    val timerState = timer
    var minutesDraft by rememberSaveable { mutableStateOf("") }
    var chaptersDraft by rememberSaveable { mutableStateOf("") }
    val value = if (timerState != null) {
        ttsSleepTimerLabel(timerState)
    } else {
        stringResource(R.string.rd_tts_sleep_timer_off)
    }
    TtsAccordion(
        title = stringResource(R.string.rd_tts_sleep_timer),
        value = value,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        SleepTimerRow(
            label = stringResource(R.string.rd_tts_sleep_timer_minutes),
            active = timerState?.mode == TtsSleepTimerMode.Minutes,
            enabled = enabled,
            value = minutesDraft,
            max = TtsSleepTimerMaxMinutes,
            onValueChange = { minutesDraft = it },
            onApply = {
                minutesDraft = it.toString()
                onMinutes(it)
            },
            onCancel = onCancel,
        )
        SleepTimerRow(
            label = stringResource(R.string.rd_tts_sleep_timer_chapters),
            active = timerState?.mode == TtsSleepTimerMode.Chapters,
            enabled = enabled,
            value = chaptersDraft,
            max = TtsSleepTimerMaxChapters,
            onValueChange = { chaptersDraft = it },
            onApply = {
                chaptersDraft = it.toString()
                onChapters(it)
            },
            onCancel = onCancel,
        )
        Text(
            text = stringResource(
                if (enabled) {
                    R.string.rd_tts_sleep_timer_pause_cancels
                } else {
                    R.string.rd_tts_sleep_timer_start_hint
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = LocalMiruExtraColors.current.muted,
            modifier = Modifier.padding(top = MiruSpace.Xs),
        )
    }
}

@Composable
private fun SleepTimerRow(
    label: String,
    active: Boolean,
    enabled: Boolean,
    value: String,
    max: Int,
    onValueChange: (String) -> Unit,
    onApply: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val parsed = value.toIntOrNull()
    val valid = parsed != null && parsed in 1..max
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MiruSize.Touch)
            .padding(top = MiruSpace.Xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        MiruCompactTextField(
            value = value,
            onValueChange = { input ->
                onValueChange(input.filter { it.isDigit() }.take(max.toString().length))
            },
            enabled = enabled,
            isError = value.isNotEmpty() && !valid,
            contentDescription = label,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (!active && valid) parsed?.let(onApply) },
            ),
        )
        IconButton(
            onClick = { if (active) onCancel() else parsed?.let(onApply) },
            enabled = enabled && (active || valid),
        ) {
            Icon(
                imageVector = if (active) Icons.Filled.Close else Icons.Filled.Check,
                contentDescription = stringResource(
                    if (active) {
                        R.string.rd_tts_sleep_timer_cancel
                    } else {
                        R.string.rd_tts_sleep_timer_apply
                    },
                ),
            )
        }
    }
}

@Composable
private fun TtsChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 32.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                maxLines = 1,
            )
        }
    }
}

/**
 * One-line control: label on the left, slider in the middle, current value on
 * the right. The slider owns no extra captions, so the two parameters stay a
 * compact two-row block instead of four.
 */
@Composable
private fun TtsSliderRow(
    label: String,
    valueText: String,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MiruSize.Touch)
            .padding(vertical = MiruSpace.Xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = MiruSize.SliderLabelWidth),
        )
        Spacer(Modifier.width(MiruSpace.M))
        Box(Modifier.weight(1f)) { content() }
        Spacer(Modifier.width(MiruSpace.M))
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.widthIn(min = MiruSize.SliderValueWidth),
        )
    }
}

/**
 * Voice list as its own sheet surface. It replaces the settings sheet while
 * open (the panel renders one sheet at a time) instead of stacking a second
 * ModalBottomSheet window on top: that ordering breaks back/dismiss handling.
 * Close or a selection returns to the settings sheet.
 *
 * Hierarchy follows LanguagePickerSheet: current voice first, saved voices
 * grouped per engine, then the full catalog grouped per engine. A star on a
 * catalog row saves/removes an auto-named favorite (voice + current rate and
 * pitch); user-renamed presets are only editable in the Saved voices sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TtsVoicePickerSheet(
    capabilities: TtsCapabilities,
    settings: TtsSettings,
    onSettingsChange: (TtsSettings) -> Unit,
    currentVoiceId: String?,
    currentEngineId: String?,
    failed: Boolean,
    onSelect: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
    onDiscover: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debouncedQuery by rememberSaveable { mutableStateOf("") }
    // Engines installed after app start are only visible after a fresh system
    // query; do it when the picker opens instead of requiring a restart.
    LaunchedEffect(Unit) { onDiscover() }
    // Typing stays immediate; filtering waits briefly so a large all-engine
    // catalog is not searched on every keystroke.
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(VoiceSearchDebounceMs)
        debouncedQuery = query
    }
    val engineLabels = remember(capabilities.engines) {
        capabilities.engines.associate { it.id to it.label }
    }
    val engineOrder = remember(capabilities.engines) { capabilities.engines.map { it.id } }
    val systemEngineLabel = stringResource(R.string.rd_tts_voice_system)
    val unknownEngineLabel = stringResource(R.string.rd_tts_voice_engine_unknown)
    val labelOf: (String?) -> String = { id ->
        id?.let { engineLabels[it] ?: it } ?: systemEngineLabel
    }
    val currentVoice = remember(capabilities.voices, currentVoiceId, currentEngineId) {
        currentVoiceId?.let { id ->
            capabilities.voices.firstOrNull {
                it.id == id && (currentEngineId == null || it.engineId == currentEngineId)
            }
        }
    }
    val filtered = remember(
        capabilities.voices,
        capabilities.currentEngineId,
        debouncedQuery,
        currentVoiceId,
        currentEngineId,
        engineLabels,
        systemEngineLabel,
    ) {
        ttsSortVoices(
            voices = ttsFilterVoices(
                voices = capabilities.voices,
                query = debouncedQuery,
                engineId = null,
                engineLabel = labelOf,
            ),
            voiceId = currentVoiceId,
            engineId = currentEngineId,
            currentEngineId = capabilities.currentEngineId,
        )
    }
    // An engine with zero voices must still be visible; only during a search
    // does an empty section become noise instead of information.
    val emptyEngineIds = remember(capabilities.engines, debouncedQuery) {
        if (debouncedQuery.isBlank()) capabilities.engines.map { it.id } else emptyList()
    }
    val voiceGroups = remember(
        filtered,
        engineOrder,
        engineLabels,
        systemEngineLabel,
        emptyEngineIds,
    ) {
        ttsGroupVoicesByEngine(
            voices = filtered,
            engineOrder = engineOrder,
            emptyEngineIds = emptyEngineIds,
            engineLabel = { id ->
                id?.let { engineLabels[it] ?: it } ?: systemEngineLabel
            },
        )
    }
    val savedFiltered = remember(
        settings.savedVoices,
        debouncedQuery,
        engineLabels,
        unknownEngineLabel,
    ) {
        ttsFilterSavedVoices(
            voices = settings.savedVoices,
            query = debouncedQuery,
            engineId = null,
        ) { id ->
            id?.let { engineLabels[it] ?: it } ?: unknownEngineLabel
        }
    }
    val savedGroups = remember(savedFiltered, engineOrder, engineLabels, unknownEngineLabel) {
        ttsGroupSavedVoices(savedFiltered, engineOrder) { id ->
            id?.let { engineLabels[it] ?: it } ?: unknownEngineLabel
        }
    }

    AppBottomSheet(
        title = stringResource(R.string.rd_tts_voices),
        description = stringResource(R.string.rd_tts_voices_desc),
        onClose = onDismiss,
        // The LazyColumn below owns scrolling; wrapping it in the sheet's
        // verticalScroll would measure it with an infinite height.
        contentScrollable = false,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.rd_tts_voice_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.rd_tts_voice_search_clear),
                        )
                    }
                }
            },
            singleLine = true,
            enabled = capabilities.voices.isNotEmpty(),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!capabilities.ready) {
            if (failed) {
                Text(
                    text = stringResource(R.string.rd_tts_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = MiruSpace.S),
                )
            } else {
                TtsInlineLoading(stringResource(R.string.rd_tts_voices_loading))
            }
            // The selected engine is down and no other engine reported a
            // usable catalog: there is nothing to list, but the error above
            // explains why instead of a fake empty list.
            if (capabilities.voices.isEmpty()) return@AppBottomSheet
        }
        Spacer(Modifier.height(MiruSpace.S))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            item(key = "current-header") {
                TtsPickerHeader(stringResource(R.string.rd_tts_voice_current))
            }
            item(key = "system-default") {
                TtsVoiceRow(
                    title = stringResource(R.string.rd_tts_voice_system),
                    subtitle = stringResource(R.string.rd_tts_voice_system_desc),
                    voiceId = null,
                    selected = currentVoiceId == null,
                    enabled = true,
                    onClick = { onSelect(null, null) },
                )
            }
            if (currentVoiceId != null) {
                item(key = "current-voice") {
                    if (currentVoice != null) {
                        TtsVoiceRow(
                            title = ttsVoiceLabel(currentVoice),
                            subtitle = voiceMetaLabel(currentVoice, labelOf),
                            voiceId = currentVoice.id,
                            selected = true,
                            enabled = true,
                            onClick = { onSelect(currentVoice.id, currentVoice.engineId) },
                        )
                    } else {
                        // The saved voice was removed by the engine; keep the
                        // row honest and let the user pick another one below.
                        TtsVoiceRow(
                            title = stringResource(R.string.rd_tts_voice_missing),
                            subtitle = labelOf(currentEngineId),
                            voiceId = currentVoiceId,
                            selected = true,
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
            if (savedGroups.isNotEmpty()) {
                item(key = "saved-divider") { TtsPickerDivider() }
                item(key = "saved-header") {
                    TtsPickerHeader(stringResource(R.string.rd_tts_saved_voices))
                }
                savedGroups.forEach { group ->
                    item(key = "saved-engine-${group.engineId ?: "unknown"}") {
                        TtsGroupHeader(group.label)
                    }
                    items(
                        items = group.voices,
                        key = { preset ->
                            "saved-${preset.engineId}\u0000${preset.voiceId}\u0000" +
                                "${preset.name}\u0000${preset.speed}\u0000${preset.pitch}"
                        },
                    ) { preset ->
                        val voiceLabel = if (preset.voiceId == null) {
                            stringResource(R.string.rd_tts_voice_system)
                        } else {
                            capabilities.voices.firstOrNull {
                                it.id == preset.voiceId &&
                                    (preset.engineId == null || it.engineId == preset.engineId)
                            }?.let(::ttsVoiceLabel)
                                ?: stringResource(R.string.rd_tts_voice_missing)
                        }
                        val detail = listOfNotNull(
                            voiceLabel.takeIf { it != preset.name },
                            stringResource(
                                R.string.rd_tts_saved_detail_fmt,
                                preset.speed,
                                preset.pitch,
                            ),
                        ).joinToString(" · ")
                        TtsSavedVoiceRow(
                            preset = preset,
                            detail = detail,
                            onClick = {
                                onSettingsChange(settings.applySavedVoice(preset))
                                onDismiss()
                            },
                        )
                    }
                }
            }
            if (voiceGroups.isNotEmpty()) {
                item(key = "all-divider") { TtsPickerDivider() }
                item(key = "all-header") {
                    TtsPickerHeader(stringResource(R.string.rd_tts_voice_all))
                }
                voiceGroups.forEach { group ->
                    item(key = "all-engine-${group.engineId ?: "unknown"}") {
                        TtsGroupHeader(group.label)
                    }
                    if (group.voices.isEmpty()) {
                        item(key = "all-engine-empty-${group.engineId ?: "unknown"}") {
                            Text(
                                text = stringResource(R.string.rd_tts_voice_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = MiruSpace.S,
                                    bottom = MiruSpace.Xs,
                                ),
                            )
                        }
                    }
                    items(
                        items = group.voices,
                        key = { voice -> "voice-${voice.engineId}\u0000${voice.id}" },
                    ) { voice ->
                        val selected = currentVoiceId == voice.id &&
                            (currentEngineId == null || voice.engineId == currentEngineId)
                        val favorite = ttsIsVoiceFavorite(settings.savedVoices, voice)
                        TtsVoiceRow(
                            title = ttsVoiceLabel(voice),
                            // The group header already names the engine.
                            subtitle = voiceMetaLabel(voice, labelOf, includeEngine = false),
                            voiceId = voice.id,
                            selected = selected,
                            enabled = true,
                            favorite = favorite,
                            onToggleFavorite = {
                                onSettingsChange(
                                    settings.copy(
                                        savedVoices = ttsToggleVoiceFavorite(
                                            settings.savedVoices,
                                            voice,
                                            settings.speed,
                                            settings.pitch,
                                        ),
                                    ),
                                )
                            },
                            onClick = { onSelect(voice.id, voice.engineId) },
                        )
                    }
                }
            }
            if (voiceGroups.isEmpty() && savedGroups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (capabilities.voices.isEmpty()) {
                            stringResource(R.string.rd_tts_voice_empty)
                        } else {
                            stringResource(R.string.rd_tts_voice_no_match)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = MiruSpace.S),
                    )
                }
            }
        }
    }
}

@Composable
private fun TtsPickerHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalMiruExtraColors.current.muted,
        modifier = Modifier.padding(top = MiruSpace.M, bottom = MiruSpace.Xxs),
    )
}

@Composable
private fun TtsGroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = MiruSpace.S, bottom = MiruSpace.Xxs, start = MiruSpace.S),
    )
}

@Composable
private fun TtsPickerDivider() {
    HorizontalDivider(Modifier.padding(vertical = MiruSpace.S))
}

@Composable
private fun TtsSavedVoiceRow(
    preset: TtsSavedVoice,
    detail: String,
    onClick: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = MiruSpace.S, top = MiruSpace.Xs, bottom = MiruSpace.Xs),
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = extra.accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(MiruSpace.S))
        Column(Modifier.weight(1f)) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TtsSavedVoicesSheet(
    settings: TtsSettings,
    capabilities: TtsCapabilities,
    onSettingsChange: (TtsSettings) -> Unit,
    onDeleteRequest: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var presetName by rememberSaveable { mutableStateOf("") }
    val currentVoiceName = capabilities.voices.firstOrNull {
        it.id == settings.voiceId &&
            (settings.engineId == null || it.engineId == settings.engineId)
    }?.locale?.displayName ?: stringResource(R.string.rd_tts_voice_system)
    val engineLabels = remember(capabilities.engines) {
        capabilities.engines.associate { it.id to it.label }
    }
    val unknownEngine = stringResource(R.string.rd_tts_voice_engine_unknown)
    val engineLabelOf: (String?) -> String = { id ->
        id?.let { engineLabels[it] ?: it } ?: unknownEngine
    }
    val newPreset = TtsSavedVoice(
        name = presetName.trim().ifEmpty { currentVoiceName },
        voiceId = settings.voiceId,
        speed = settings.speed,
        pitch = settings.pitch,
        engineId = settings.engineId,
    )
    val duplicate = ttsAddSavedVoice(settings.savedVoices, newPreset)
        .size == settings.savedVoices.size
    val grouped = remember(settings.savedVoices, engineLabels, unknownEngine) {
        settings.savedVoices.withIndex()
            .groupBy { it.value.engineId }
            .entries
            .sortedBy { engineLabelOf(it.key) }
    }

    AppBottomSheet(
        title = stringResource(R.string.rd_tts_saved_voices),
        description = currentVoiceName,
        onClose = onDismiss,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                placeholder = { Text(stringResource(R.string.rd_tts_saved_name)) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    onSettingsChange(
                        settings.copy(
                            savedVoices = ttsAddSavedVoice(
                                settings.savedVoices,
                                newPreset,
                            ),
                        ),
                    )
                    presetName = ""
                },
                enabled = !duplicate,
            ) {
                Text(stringResource(R.string.rd_tts_saved_save))
            }
        }
        if (settings.savedVoices.isEmpty()) {
            Text(
                text = stringResource(R.string.rd_tts_saved_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = MiruSpace.M),
            )
        } else {
            grouped.forEach { (engineId, presets) ->
                Text(
                    text = engineLabelOf(engineId),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(top = MiruSpace.S),
                )
                presets.forEach { indexed ->
                    val preset = indexed.value
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                // One write: engine, voice, speed and pitch.
                                onSettingsChange(settings.applySavedVoice(preset))
                                onDismiss()
                            },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(
                                    R.string.rd_tts_saved_detail_fmt,
                                    preset.speed,
                                    preset.pitch,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onDeleteRequest(indexed.index) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(
                                    R.string.rd_tts_saved_delete,
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TtsDeleteSavedVoiceSheet(
    voice: TtsSavedVoice?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val voiceName = voice?.name.orEmpty()
    AppBottomSheet(
        title = stringResource(R.string.rd_tts_saved_delete_title),
        description = if (voiceName.isBlank()) {
            ""
        } else {
            stringResource(R.string.rd_tts_saved_delete_desc, voiceName)
        },
        onClose = onDismiss,
        closeLabel = stringResource(R.string.rd_tts_cancel),
        doneLabel = stringResource(R.string.rd_tts_saved_delete_confirm),
        onDone = onDelete,
        doneDanger = true,
    ) {}
}

@Composable
private fun TtsAccordion(
    title: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "tts-accordion-arrow",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(vertical = 10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(MiruSpace.S))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
        ) {
            Column(Modifier.padding(bottom = MiruSpace.S)) { content() }
        }
    }
    MiruRowDivider()
}


@Composable
private fun TtsHighlightDot(
    color: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun TtsStatusPill(status: TtsStatus, atEnd: Boolean) {
    val color = when (status) {
        TtsStatus.Playing, TtsStatus.Loading -> MaterialTheme.colorScheme.primary
        TtsStatus.Paused -> MaterialTheme.colorScheme.tertiary
        TtsStatus.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = ttsStatusLabel(status, atEnd),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun StopTtsButton(onStop: () -> Unit, enabled: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onStop),
    ) {
        Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = stringResource(R.string.rd_tts_stop),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TtsPlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (playing) R.string.rd_tts_action_pause else R.string.rd_tts_action_play,
            ),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun TtsVoiceRow(
    title: String,
    subtitle: String?,
    voiceId: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (selected) {
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(start = MiruSpace.S, top = MiruSpace.Xs, bottom = MiruSpace.Xs),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    selected -> extra.accent
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (voiceId != null) {
                Text(
                    text = stringResource(R.string.rd_tts_voice_id_fmt, voiceId),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMiruExtraColors.current.muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The star is a separate 48dp target; selection stays on the row.
        if (favorite != null && onToggleFavorite != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (favorite) {
                            R.string.rd_tts_voice_favorite_remove
                        } else {
                            R.string.rd_tts_voice_favorite_add
                        },
                    ),
                    tint = if (favorite) extra.accent else extra.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun TtsInlineLoading(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MiruSpace.S),
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(MiruSpace.S))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ttsStatusLabel(status: TtsStatus, atEnd: Boolean): String = when (status) {
    TtsStatus.Playing -> stringResource(R.string.rd_tts_playing)
    TtsStatus.Loading -> stringResource(R.string.rd_tts_loading)
    TtsStatus.Paused -> stringResource(R.string.rd_tts_paused)
    TtsStatus.Failed -> stringResource(R.string.rd_tts_status_failed)
    else -> if (atEnd) {
        stringResource(R.string.rd_tts_finished_short)
    } else {
        stringResource(R.string.rd_tts_ready)
    }
}

@Composable
private fun ttsChapterLabel(chapterNumber: Int, chapterCount: Int): String =
    if (chapterNumber > 0 && chapterCount > 0) {
        stringResource(R.string.rd_tts_chapter_position_fmt, chapterNumber, chapterCount)
    } else {
        ""
    }

private fun ttsProgressPercentValue(sentenceNumber: Int, chunkCount: Int): Int =
    if (chunkCount <= 0) {
        0
    } else {
        ((sentenceNumber.toFloat() / chunkCount) * 100f).toInt().coerceIn(0, 100)
    }

@Composable
private fun voiceMetaLabel(
    voice: TtsVoice,
    engineLabel: (String?) -> String,
    includeEngine: Boolean = true,
): String {
    val quality = when {
        voice.quality >= Voice.QUALITY_HIGH -> R.string.rd_tts_quality_high
        voice.quality >= Voice.QUALITY_NORMAL -> R.string.rd_tts_quality_normal
        else -> R.string.rd_tts_quality_basic
    }
    val connection = if (voice.needsInternet) {
        R.string.rd_tts_voice_online
    } else {
        R.string.rd_tts_voice_offline
    }
    return buildList {
        // A null engine id means "the live engine"; do not mislabel it as the
        // System default voice in a row that is an actual voice.
        if (includeEngine && voice.engineId != null) add(engineLabel(voice.engineId))
        add(stringResource(R.string.rd_tts_quality_fmt, stringResource(quality)))
        add(stringResource(connection))
    }.filter { it.isNotBlank() }.joinToString(" · ")
}
