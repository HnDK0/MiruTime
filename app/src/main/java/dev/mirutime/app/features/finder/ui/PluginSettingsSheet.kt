@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.finder.ui

import android.widget.Toast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruPickerHeader
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.ChoiceOption
import dev.mirutime.uikit.ChoiceSheet
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.finder.domain.PluginSetting
import dev.mirutime.uikit.MiruTextField

// Per-plugin settings sheet. Only two groups now:
// 1) the app-owned base URL;
// 2) the plugin's own settings, rendered only when the plugin declared them
//    (`settings` from FinderDataProvider.settingsSchema,
//    `getSettingsSchema` / `lua_preferences`). Every setting is its own
//    section titled with the plugin's own label; the UI never branches on a
//    plugin id. A select with <= 3 options is a chip row, more options — a
//    value row opening a one-of-N choice sheet.
// Translation and uninstall live in the actions sheet, not here.
@Composable
fun PluginSettingsSheet(
    name: String,
    description: String,
    baseUrl: String,
    settings: List<PluginSetting>,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val extra = LocalMiruExtraColors.current
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
        if (settings.isNotEmpty()) {
            PluginGroupHeader(stringResource(R.string.finder_plugin_from_plugin))
            settings.forEach { setting ->
                when (setting) {
                    is PluginSetting.Select -> SelectSetting(setting)
                }
            }
        }
    }
}

// `select` widget: up to three options are
// visible right in the setting's own section; more options turn the setting
// into one value row that opens a one-of-N choice sheet.
@Composable
private fun SelectSetting(setting: PluginSetting.Select) {
    val current = setting.current()
    if (setting.options.size <= 3) {
        // Plugin labels arrive as sentences ("Chapter Fetch Delay (may reduce
        // 'Security Check' triggers)"), so they render verbatim instead of the
        // app's small-caps section style.
        SheetSection(title = setting.label, preserveCase = true) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                setting.options.forEach { option ->
                    MiruFilterChip(
                        selected = option.key == current,
                        onClick = { setting.onChange(option.key) },
                        label = option.label,
                    )
                }
            }
        }
    } else {
        var open by remember { mutableStateOf(false) }
        val currentLabel = setting.options.firstOrNull { it.key == current }?.label ?: current
        // The value row lives in the setting's own section, so a >3-option
        // setting keeps the same visual block as a chip row instead of looking
        // attached to the previous section.
        SheetSection(title = setting.label, preserveCase = true) {
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
                onSelect = setting.onChange,
                onClose = { open = false },
            )
        }
    }
}

// Hairline + label separating app-owned settings from what the plugin itself
// declared. Plugin labels stay in the plugin's own language (often English).
@Composable
private fun PluginGroupHeader(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.L),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        MiruPickerHeader(text, modifier = Modifier.padding(top = MiruSpace.M))
    }
}
