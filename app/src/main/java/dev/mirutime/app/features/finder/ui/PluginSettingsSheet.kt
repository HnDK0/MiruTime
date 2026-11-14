@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.finder.ui

import android.widget.Toast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruPickerHeader
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.ChoiceOption
import dev.mirutime.uikit.ChoiceSheet
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.finder.domain.PluginSetting
import dev.mirutime.app.features.finder.domain.PluginSettingChipLabelMax
import dev.mirutime.app.features.finder.domain.fitsChipRow
import dev.mirutime.uikit.MiruTextField

// Per-plugin settings sheet. Only two groups now:
// 1) the app-owned base URL;
// 2) the plugin's own settings, rendered only when the plugin declared them
//    (`settings` from FinderDataProvider.settingsSchema,
//    `getSettingsSchema` / `lua_preferences`). Every setting is its own
//    section titled with the plugin's own label; the UI never branches on a
//    plugin id. Few short options are a chip row, everything else a value row
//    opening a one-of-N choice sheet.
// A failed getSettingsSchema shows one line with Retry: the base URL stays
// editable, and "the plugin declared nothing" is never painted as an error.
// There is no loading state on purpose: whether the plugin has settings at all
// is unknown until it answers, so a spinner would flash on every plugin.
// Translation and uninstall live in the actions sheet, not here.
@Composable
fun PluginSettingsSheet(
    name: String,
    description: String,
    baseUrl: String,
    settings: List<PluginSetting>,
    settingsError: String?,
    // True while a retry is in flight. The first load deliberately has no
    // spinner: whether the plugin declares settings at all is unknown until it
    // answers, so one would flash on every plugin that declares none.
    settingsLoading: Boolean,
    onRetrySettings: () -> Unit,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(baseUrl) }

    AppBottomSheet(
        title = name,
        description = description,
        onClose = onClose,
        doneLabel = stringResource(R.string.finder_save),
        onDone = {
            onSave(url)
            // Only confirm a save the sheet actually represents. The base URL
            // is app-owned, but an empty schema has no plugin settings to save.
            if (settings.isNotEmpty()) {
                Toast.makeText(context, R.string.finder_plugin_saved, Toast.LENGTH_SHORT).show()
            }
            onClose()
        },
    ) {
        SheetSection(title = stringResource(R.string.finder_plugin_base_url)) {
            MiruTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = stringResource(R.string.finder_plugin_base_url_hint),
                modifier = Modifier.fillMaxWidth(),
                plate = false,
            )
        }
        when {
            // A retry is running: the failed row is replaced by progress, so a
            // slow plugin never leaves the group blank and the button cannot be
            // tapped a second time.
            settingsLoading && settingsError != null -> {
                PluginGroupHeader(stringResource(R.string.finder_plugin_from_plugin))
                SettingsLoading()
            }
            settingsError != null -> {
                PluginGroupHeader(stringResource(R.string.finder_plugin_from_plugin))
                SettingsError(message = settingsError, onRetry = onRetrySettings)
            }
            settings.isNotEmpty() -> {
                PluginGroupHeader(stringResource(R.string.finder_plugin_from_plugin))
                settings.forEach { setting ->
                    key(setting.key) {
                        when (setting) {
                            is PluginSetting.Select -> SelectSetting(setting)
                        }
                    }
                }
            }
        }
    }
}

// `select` widget: few short options are visible right in the setting's own
// section; long labels or more options turn the setting into one value row
// that opens a one-of-N choice sheet.
@Composable
private fun SelectSetting(setting: PluginSetting.Select) {
    // The sheet owns what it shows: the store is not observable, so the value
    // is read once per setting key and every pick is written through. Keyed by
    // setting.key, not the Select instance: that instance carries lambdas, so
    // a recreated Select would not compare equal and would snap the chip back.
    var current by remember(setting.key) { mutableStateOf(setting.current()) }
    val onChange: (String) -> Unit = { value ->
        current = value
        setting.onChange(value)
    }
    val currentLabel = setting.options.firstOrNull { it.key == current }?.label ?: current
    // One collapsible section per setting, like every settings sheet in the app:
    // the section stays closed and its summary answers "what is it set to?".
    // Do not open it by default -- an initially expanded section on a
    // ModalBottomSheet is laid out before the sheet re-measures, and the
    // footer (Close/Save) ends up below the window edge until the next gesture
    // (verified on an emulator; expanding by tap is fine).
    //
    // The summary is skipped for long labels: it is laid out beside the
    // weighted title, and a sentence there squeezes "Chapter list (re-add books
    // after changing)" into a four-line rag. Short values ("Fanfic", "2
    // seconds") fit, and there the summary earns its place.
    val summary = currentLabel.takeIf { it.length <= PluginSettingChipLabelMax }
    if (setting.options.fitsChipRow()) {
        // Plugin labels arrive as sentences ("Chapter Fetch Delay (may reduce
        // 'Security Check' triggers)"), so they render verbatim instead of the
        // app's small-caps section style.
        SheetSection(
            title = setting.label,
            preserveCase = true,
            summary = summary,
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                setting.options.forEach { option ->
                    MiruFilterChip(
                        selected = option.key == current,
                        onClick = { onChange(option.key) },
                        label = option.label,
                    )
                }
            }
        }
    } else {
        var open by remember { mutableStateOf(false) }
        // The value row lives in the setting's own section, so a >3-option
        // setting keeps the same visual block as a chip row instead of looking
        // attached to the previous section.
        SheetSection(
            title = setting.label,
            preserveCase = true,
            summary = summary,
        ) {
            SettingsValueRow(
                title = currentLabel,
                value = "",
                onClick = { open = true },
            )
        }
        if (open) {
            ChoiceSheet(
                title = setting.label,
                description = "",
                options = setting.options.map { ChoiceOption(it.key, it.label) },
                selected = current,
                onSelect = onChange,
                onClose = { open = false },
            )
        }
    }
}

// One line for a plugin that could not answer getSettingsSchema. The detail is
// the same redacted diagnostic the filter sheet shows; Retry re-runs the Lua
// call, and the base URL above stays editable meanwhile.
@Composable
private fun SettingsLoading() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.L)
            .defaultMinSize(minHeight = MiruSize.Touch),
    ) {
        CircularProgressIndicator(
            strokeWidth = MiruSpace.Xxs,
            color = LocalMiruExtraColors.current.accent,
            modifier = Modifier.size(MiruSpace.Xl),
        )
        Text(
            text = stringResource(R.string.finder_plugin_settings_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMiruExtraColors.current.muted,
        )
    }
}

@Composable
private fun SettingsError(message: String, onRetry: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.L)
            .defaultMinSize(minHeight = MiruSize.Touch),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.finder_plugin_settings_error),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MiruSpace.Xs),
            )
        }
        Spacer(modifier = Modifier.width(MiruSpace.S))
        TextButton(onClick = onRetry) {
            Text(
                text = stringResource(R.string.finder_filter_retry),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// Label separating app-owned settings from what the plugin itself declared.
// Plugin labels stay in the plugin's own language (often English).
@Composable
private fun PluginGroupHeader(text: String) {
    // Label only, no rule of its own: the first setting's SheetSection draws a
    // divider right after this label, and a second hairline above it made the
    // pair read as an underline of the header.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.L, bottom = MiruSpace.S),
    ) {
        MiruPickerHeader(text)
    }
}
