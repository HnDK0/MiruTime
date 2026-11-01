@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.SettingsRadioRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SettingsTextField
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig

// The one translation editor shared by the per-plugin sheet, the per-novel
// sheet and the book page's translator tool:
// enabled switch, service (inheriting the global one, named outright),
// language pair, scope, hidden-title chips (FULL only) and the LLM prompt.
@Composable
internal fun TranslationConfigContent(
    config: TranslationConfig,
    // Hosts that already own the service can hide this section.
    showProvider: Boolean = true,
    showScope: Boolean = true,
    // Reader inserts its global parallel control where scope sits elsewhere.
    afterPair: (@Composable () -> Unit)? = null,
    onChange: (TranslationConfig) -> Unit,
) {
    val s = AppSettings
    val provider = TranslationProviderOption.from(config.provider.ifBlank { s.provider })
    val isLlm = provider.isLlm

    SettingsSwitchRow(
        title = stringResource(R.string.st_translation_enable),
        checked = config.enabled,
        onChange = { onChange(config.copy(enabled = it)) },
        horizontalPadding = 0.dp,
    )
    if (showProvider) {
        // Ten radio rows would fill the whole sheet; keep the section folded so
        // the pair and scope stay reachable without scrolling.
        SheetSection(
            title = stringResource(R.string.st_tr_provider),
            initiallyExpanded = false,
        ) {
            SettingsRadioRow(
                // Inheriting resolves to the global provider, so the row names it
                // outright instead of an abstract "from global".
                title = stringResource(R.string.st_pt_inherit_fmt, providerLabel(s.provider)),
                selected = config.provider.isBlank(),
                onClick = { onChange(config.copy(provider = "")) },
            )
            TranslationProviderOption.entries.forEach { option ->
                SettingsRadioRow(
                    title = stringResource(option.label),
                    selected = config.provider == option.key,
                    onClick = { onChange(config.copy(provider = option.key)) },
                )
            }
        }
    }
    TranslationPairControls(
        source = config.source,
        target = config.target,
        onPairChange = { src, tgt -> onChange(config.copy(source = src, target = tgt)) },
    )
    afterPair?.invoke()
    if (showScope) {
        SheetSection(title = stringResource(R.string.st_pt_scope)) {
            SettingsRadioRow(
                title = stringResource(R.string.st_pt_scope_standard),
                subtitle = stringResource(R.string.st_pt_scope_standard_desc),
                selected = !config.full,
                onClick = { onChange(config.copy(full = false)) },
            )
            SettingsRadioRow(
                title = stringResource(R.string.st_pt_scope_full),
                subtitle = stringResource(R.string.st_pt_scope_full_desc),
                selected = config.full,
                onClick = { onChange(config.copy(full = true)) },
            )
        }
        if (config.full) {
            SheetSection(title = stringResource(R.string.st_pt_hide)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                    MiruFilterChip(
                        selected = config.hideLibrary,
                        onClick = { onChange(config.copy(hideLibrary = !config.hideLibrary)) },
                        label = stringResource(R.string.st_pt_hide_library),
                    )
                    MiruFilterChip(
                        selected = config.hideHistory,
                        onClick = { onChange(config.copy(hideHistory = !config.hideHistory)) },
                        label = stringResource(R.string.st_pt_hide_history),
                    )
                    MiruFilterChip(
                        selected = config.hideCatalog,
                        onClick = { onChange(config.copy(hideCatalog = !config.hideCatalog)) },
                        label = stringResource(R.string.st_pt_hide_catalog),
                    )
                    MiruFilterChip(
                        selected = config.hideSearch,
                        onClick = { onChange(config.copy(hideSearch = !config.hideSearch)) },
                        label = stringResource(R.string.st_pt_hide_search),
                    )
                }
            }
        }
    }
    if (isLlm) {
        // Visible straight away: choosing an LLM provider is where the
        // per-source override prompt becomes relevant.
        SheetSection(title = stringResource(R.string.st_tr_prompts), initiallyExpanded = false) {
            SettingsTextField(
                label = stringResource(R.string.st_tr_prompt_label),
                value = config.prompt,
                onChange = { onChange(config.copy(prompt = it)) },
                tip = stringResource(R.string.st_pt_prompt_tip),
                singleLine = false,
                minLines = 3,
                maxLines = 8,
            )
        }
    }
}

// Pair gate: an inherited side resolves to the global value, and only a
// resolved, non-identical pair can translate.
internal fun isTranslationPairValid(config: TranslationConfig): Boolean {
    val s = AppSettings
    val source = config.source.ifBlank { s.globalSource }
    val target = config.target.ifBlank { s.globalTarget }
    return source.isNotBlank() && target.isNotBlank() && source != target
}

// Save gate shared by all translation editors: "off" is always savable, so the
// user can store a disabled config (or just a provider) without inventing a
// pair first. An enabled config still needs a usable pair.
internal fun isTranslationSaveEnabled(config: TranslationConfig): Boolean =
    !config.enabled || isTranslationPairValid(config)
