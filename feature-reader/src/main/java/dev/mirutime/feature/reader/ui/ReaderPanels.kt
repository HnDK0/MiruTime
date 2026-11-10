package dev.mirutime.feature.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.feature.reader.R
import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.ReaderMode
import dev.mirutime.feature.reader.ReaderPalette
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.api.ReaderAssetPort
import dev.mirutime.feature.tts.TtsCapabilities
import dev.mirutime.feature.tts.TtsSettings
import dev.mirutime.feature.tts.TtsSleepTimerState
import dev.mirutime.feature.tts.TtsStatus
import dev.mirutime.feature.tts.ui.TtsSettingsPanel
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SheetHeaderAction
import dev.mirutime.uikit.SheetSection
import kotlinx.coroutines.flow.StateFlow

// One reader panel at a time; every panel is the standard 70%-capped sheet.
enum class ReaderPanel { Style, More, Translation, Tts }

@Composable
fun ReaderPanelHost(
    panel: ReaderPanel?,
    style: ReaderStyle,
    previewStyle: ReaderStyle = style,
    onStyleChange: (ReaderStyle) -> Unit,
    onStylePreview: (ReaderStyle) -> Unit = onStyleChange,
    onStyleCommit: (ReaderStyle) -> Unit = onStyleChange,
    onStyleCancel: () -> Unit = {},
    onDismiss: () -> Unit,
    bookUrl: String,
    onOpenRegex: (String) -> Unit,
    mode: ReaderMode,
    onModeChange: (ReaderMode) -> Unit,
    assets: ReaderAssetPort,
    translationPanel: @Composable (bookUrl: String, onDismiss: () -> Unit) -> Unit,
    showMode: Boolean = true,
    manga: Boolean = false,
    mangaStyle: MangaReaderStyle = MangaReaderStyle.Default,
    onMangaStyleChange: (MangaReaderStyle) -> Unit = {},
    ttsSettings: TtsSettings = TtsSettings(),
    ttsCapabilities: TtsCapabilities = TtsCapabilities(),
    ttsStatus: TtsStatus = TtsStatus.Idle,
    ttsMessage: String? = null,
    ttsPreviewText: String = "",
    ttsPreviewTranslatedText: String = "",
    ttsHighlight: Boolean = false,
    highlightColor: Long = ReaderStyle.Default.highlightColor,
    onTtsSettingsChange: (TtsSettings) -> Unit = {},
    onTtsTest: (String) -> Unit = {},
    onTtsReset: () -> Unit = {},
    onTtsRetry: () -> Unit = {},
    onTtsToggle: () -> Unit = {},
    onTtsHighlightChange: (Boolean) -> Unit = {},
    onHighlightColorChange: (Long) -> Unit = {},
    onTtsDiscoverVoices: () -> Unit = {},
    onTtsPreviewVoice: (String?, String?, Float, Float, String) -> Unit =
        { _, _, _, _, _ -> },
    onTtsPreviewStop: () -> Unit = {},
    sleepTimer: StateFlow<TtsSleepTimerState?>? = null,
    onSleepTimerMinutes: (Int) -> Unit = {},
    onSleepTimerChapters: (Int) -> Unit = {},
    onSleepTimerCancel: () -> Unit = {},
    autoScrollEnabled: Boolean = false,
    autoScrollSupported: Boolean = true,
    autoScrollSubtitle: String? = null,
    onAutoScrollChange: ((Boolean) -> Unit)? = null,
) {
    when (panel) {
        ReaderPanel.Style -> if (manga) {
            MangaStylePanel(
                style = mangaStyle,
                onStyleChange = onMangaStyleChange,
                readerStyle = style,
                onReaderStyleChange = onStyleChange,
                onDismiss = onDismiss,
            )
        } else {
            ReaderStylePanel(
                style = previewStyle,
                onStylePreview = onStylePreview,
                onStyleCommit = onStyleCommit,
                onStyleCancel = onStyleCancel,
                onDismiss = onDismiss,
                assets = assets,
            )
        }
        ReaderPanel.More -> if (!manga) {
            ReaderMorePanel(
                style = style,
                onStyleChange = onStyleChange,
                onDismiss = onDismiss,
                bookUrl = bookUrl,
                onOpenRegex = onOpenRegex,
                mode = mode,
                onModeChange = onModeChange,
                showMode = showMode,
                autoScrollEnabled = autoScrollEnabled,
                autoScrollSupported = autoScrollSupported,
                autoScrollSubtitle = autoScrollSubtitle,
                onAutoScrollChange = onAutoScrollChange,
            )
        }
        ReaderPanel.Translation -> if (!manga) {
            translationPanel(bookUrl, onDismiss)
        }
        ReaderPanel.Tts -> if (!manga) {
            TtsSettingsPanel(
                settings = ttsSettings,
                capabilities = ttsCapabilities,
                status = ttsStatus,
                message = ttsMessage,
                ttsPreviewText = ttsPreviewText,
                ttsPreviewTranslatedText = ttsPreviewTranslatedText,
                highlight = ttsHighlight,
                highlightColor = highlightColor,
                highlightColors = ReaderPalette.highlightColors,
                onSettingsChange = onTtsSettingsChange,
                onTest = onTtsTest,
                onReset = onTtsReset,
                onRetry = onTtsRetry,
                onToggle = onTtsToggle,
                onHighlightChange = onTtsHighlightChange,
                onHighlightColorChange = onHighlightColorChange,
                onDismiss = onDismiss,
                onDiscoverVoices = onTtsDiscoverVoices,
                onPreviewVoice = onTtsPreviewVoice,
                onPreviewStop = onTtsPreviewStop,
                sleepTimer = sleepTimer,
                onSleepTimerMinutes = onSleepTimerMinutes,
                onSleepTimerChapters = onSleepTimerChapters,
                onSleepTimerCancel = onSleepTimerCancel,
            )
        }
        null -> Unit
    }
}

@Composable
private fun MangaStylePanel(
    style: MangaReaderStyle,
    onStyleChange: (MangaReaderStyle) -> Unit,
    readerStyle: ReaderStyle,
    onReaderStyleChange: (ReaderStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(style) { mutableStateOf(style) }

    AppBottomSheet(
        title = stringResource(R.string.rd_settings),
        description = stringResource(R.string.rd_manga_settings_desc),
        onClose = onDismiss,
        doneLabel = stringResource(R.string.st_save),
        onDone = {
            onStyleChange(draft.sanitized())
            onDismiss()
        },
        // Reset is a sheet-header action, never a section at the bottom.
        headerAction = {
            SheetHeaderAction(
                label = stringResource(R.string.rd_reset),
                onClick = { draft = MangaReaderStyle.Default },
            )
        },
    ) {
        MangaReaderSettingsControls(
            style = draft,
            onStyleChange = { draft = it },
        )
        // Reader-wide and applied at once: fullscreen is a screen mode, not a
        // manga style value, so it does not wait for the sheet's Save.
        SheetSection(title = stringResource(R.string.rd_screen)) {
            SettingsSwitchRow(
                title = stringResource(R.string.rd_fullscreen),
                subtitle = stringResource(R.string.rd_fullscreen_desc),
                checked = readerStyle.fullscreen,
                onChange = { onReaderStyleChange(readerStyle.copy(fullscreen = it)) },
                horizontalPadding = 0.dp,
            )
        }
    }
}

@Composable
private fun ReaderStylePanel(
    style: ReaderStyle,
    onStylePreview: (ReaderStyle) -> Unit,
    onStyleCommit: (ReaderStyle) -> Unit,
    onStyleCancel: () -> Unit,
    onDismiss: () -> Unit,
    assets: ReaderAssetPort,
) {
    var resetConfirm by remember { mutableStateOf(false) }

    AppBottomSheet(
        title = stringResource(R.string.rd_style),
        description = stringResource(R.string.rd_style_desc),
        onClose = {
            onStyleCancel()
            onDismiss()
        },
        doneLabel = stringResource(R.string.st_save),
        closeLabel = stringResource(R.string.lib_cancel),
        onDone = {
            onStyleCommit(style)
            onDismiss()
        },
        // Reset is the sheet-header action; it still asks before discarding.
        headerAction = {
            SheetHeaderAction(
                label = stringResource(R.string.rd_reset_style),
                onClick = { resetConfirm = true },
            )
        },
    ) {
        ReaderStyleControls(
            style = style,
            onStyleChange = onStylePreview,
            onReset = { onStylePreview(ReaderStyle.Default) },
            assets = assets,
            hostedInSheet = true,
            deferAssetDeletion = true,
        )
    }

    if (resetConfirm) {
        AppBottomSheet(
            title = stringResource(R.string.rd_reset_confirm_title),
            description = stringResource(R.string.rd_reset_confirm_desc),
            onClose = { resetConfirm = false },
            doneLabel = stringResource(R.string.rd_reset_style),
            doneDanger = true,
            closeLabel = stringResource(R.string.lib_cancel),
            onDone = {
                resetConfirm = false
                onStylePreview(ReaderStyle.Default)
            },
            content = {},
        )
    }
}

@Composable
private fun ReaderMorePanel(
    style: ReaderStyle,
    onStyleChange: (ReaderStyle) -> Unit,
    onDismiss: () -> Unit,
    bookUrl: String,
    onOpenRegex: (String) -> Unit,
    mode: ReaderMode,
    onModeChange: (ReaderMode) -> Unit,
    showMode: Boolean,
    autoScrollEnabled: Boolean = false,
    autoScrollSupported: Boolean = true,
    autoScrollSubtitle: String? = null,
    onAutoScrollChange: ((Boolean) -> Unit)? = null,
) {
    var draft by remember(style) { mutableStateOf(style) }

    AppBottomSheet(
        title = stringResource(R.string.rd_more),
        description = stringResource(R.string.rd_more_desc),
        onClose = onDismiss,
        doneLabel = stringResource(R.string.st_save),
        onDone = {
            onStyleChange(draft)
            onDismiss()
        },
    ) {
        SheetSection(title = stringResource(R.string.rd_tools)) {
            MiruActionRow(
                icon = Icons.AutoMirrored.Filled.Rule,
                title = stringResource(R.string.rd_regex),
                subtitle = stringResource(R.string.rd_regex_desc),
                onClick = {
                    onDismiss()
                    onOpenRegex(bookUrl)
                },
            )
        }
        if (showMode) {
            SheetSection(title = stringResource(R.string.rd_mode)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MiruFilterChip(
                        selected = mode == ReaderMode.Feed,
                        onClick = { onModeChange(ReaderMode.Feed) },
                        label = stringResource(R.string.rd_mode_feed),
                    )
                    MiruFilterChip(
                        selected = mode == ReaderMode.Paged,
                        onClick = { onModeChange(ReaderMode.Paged) },
                        label = stringResource(R.string.rd_mode_paged),
                    )
                }
            }
        }
        // Reading controls live here now, not in the Style sheet.
        ReaderReadingControls(
            style = draft,
            onStyleChange = { draft = it },
            hostedInSheet = true,
            autoScrollEnabled = autoScrollEnabled,
            autoScrollSupported = autoScrollSupported,
            autoScrollSubtitle = autoScrollSubtitle,
            onAutoScrollChange = onAutoScrollChange,
        )
        SheetSection(title = stringResource(R.string.rd_menu_opens_with)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MiruFilterChip(
                    selected = !draft.doubleTapMenu,
                    onClick = { draft = draft.copy(doubleTapMenu = false) },
                    label = stringResource(R.string.rd_tap_one),
                )
                MiruFilterChip(
                    selected = draft.doubleTapMenu,
                    onClick = { draft = draft.copy(doubleTapMenu = true) },
                    label = stringResource(R.string.rd_tap_two),
                )
            }
        }
        SheetSection(title = stringResource(R.string.rd_highlighting)) {
            SettingsSwitchRow(
                title = stringResource(R.string.rd_manual_highlight),
                checked = draft.manualHighlight,
                onChange = { draft = draft.copy(manualHighlight = it).sanitized() },
                horizontalPadding = 0.dp,
            )
        }
    }
}
