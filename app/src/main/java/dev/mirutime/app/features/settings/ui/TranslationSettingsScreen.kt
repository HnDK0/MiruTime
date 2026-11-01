@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.uikit.SettingsRadioRow
import dev.mirutime.uikit.SettingsSliderRow
import dev.mirutime.uikit.SettingsSwitchRow
import dev.mirutime.uikit.SettingsTextField
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.translator.ProviderId

private val GeminiModels = listOf(
    "gemini-3.1-flash-lite",
    "gemini-2.5-flash-lite",
    "gemini-2.0-flash-exp",
    "gemini-1.5-flash",
    "gemini-1.5-pro",
)
private val OpenAiModels = listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "deepseek-chat", "mistral-small-latest")

// Translation (ported from NoveLA's translation settings): the global pair and
// switch, provider, provider keys/models, the LLM prompt, per-novel prompts,
// LLM limits and the per-novel / per-plugin overrides. Per-novel pairs live on
// the book page, per-plugin ones in the plugin settings; this screen is the
// global level they inherit from. Only the provider block that matches the
// choice is shown.
@Composable
fun TranslationSettingsScreen(
    contentPadding: PaddingValues,
    onOpen: (route: String) -> Unit,
) {
    val s = AppSettings
    var promptSheet by remember { mutableStateOf(false) }
    val providerOption = TranslationProviderOption.from(s.provider)
    val isLlm = providerOption.isLlm
    // Both editable OpenAI base URLs carry the key and chapter text, so the
    // shared endpoint policy guards them alike: a LAN host needs Android 17's
    // local-network grant, a public http host is rejected.
    val endpointProvider = when (providerOption) {
        TranslationProviderOption.OpenAi -> ProviderId.OPENAI
        TranslationProviderOption.OpenAiCompatible -> ProviderId.OPENAI_COMPATIBLE
        else -> ProviderId.GOOGLE_PA
    }
    val endpointBaseUrl = when (providerOption) {
        TranslationProviderOption.OpenAi -> s.openAiBaseUrl
        TranslationProviderOption.OpenAiCompatible -> s.openAiCompatibleBaseUrl
        else -> ""
    }
    val localNetwork = rememberLocalNetworkPermission(
        provider = endpointProvider,
        baseUrl = endpointBaseUrl,
    )
    // The global switch is only meaningful once a target language is set.
    val hasGlobalPair = s.globalTarget.isNotBlank()

    SettingsPage(contentPadding) {
        item(key = "mode") {
            MiruSectionCard(
                title = stringResource(R.string.st_tr_global_title),
                description = stringResource(R.string.st_tr_global_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.st_tr_global_switch),
                    subtitle = stringResource(
                        if (hasGlobalPair) R.string.st_tr_global_switch_desc else R.string.st_tr_global_needs_pair,
                    ),
                    checked = s.translationGlobalMode && hasGlobalPair,
                    onChange = { s.translationGlobalMode = it },
                    enabled = hasGlobalPair,
                )
                MiruRowDivider()
                // Same pair editor as the per-novel and per-plugin levels.
                // At the global level a blank side is simply "not set", not
                // an inherited value.
                Column(
                    modifier = Modifier.padding(
                        horizontal = MiruSpace.L,
                        vertical = MiruSpace.S,
                    ),
                ) {
                    TranslationPairControls(
                        source = s.globalSource,
                        target = s.globalTarget,
                        onPairChange = { source, target ->
                            s.globalSource = source
                            s.globalTarget = target
                            // The global mode can never stay on without a target.
                            if (s.globalTarget.isBlank()) s.translationGlobalMode = false
                        },
                        isGlobal = true,
                        sectionTitle = stringResource(R.string.st_tr_pair),
                    )
                }
            }
        }
        item(key = "provider") {
            MiruSectionCard(
                title = stringResource(R.string.st_tr_provider),
                description = stringResource(R.string.st_tr_provider_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                TranslationProviderOption.entries.forEachIndexed { index, option ->
                    if (index > 0) MiruRowDivider()
                    SettingsRadioRow(
                        title = stringResource(option.label),
                        subtitle = stringResource(option.description),
                        selected = s.provider == option.key,
                        onClick = {
                            s.provider = option.key
                            // Yandex PA caps concurrency lower than the rest.
                            s.chunkConcurrency = s.chunkConcurrency.coerceAtMost(option.maxChunkConcurrency)
                        },
                    )
                }
            }
        }
        when (providerOption) {
            TranslationProviderOption.GooglePa -> item(key = "pa") {
                MiruSectionCard(
                    title = stringResource(R.string.st_tr_pa_keys),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_keys_label),
                        value = s.googlePaKeys,
                        onChange = { s.googlePaKeys = it },
                        placeholder = "AIzaSy…\nAIzaSy…",
                        tip = stringResource(R.string.st_tr_pa_keys_tip),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                    )
                }
            }
            TranslationProviderOption.Gemini -> item(key = "gemini") {
                MiruSectionCard(
                    title = stringResource(R.string.st_provider_gemini),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_gemini_key),
                        value = s.geminiKeys,
                        onChange = { s.geminiKeys = it },
                        placeholder = "AIzaSy…\nAIzaSy…",
                        tip = stringResource(R.string.st_tr_gemini_key_tip),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                    )
                    MiruRowDivider()
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_model),
                        value = s.geminiModel,
                        onChange = { s.geminiModel = it },
                        tip = stringResource(R.string.st_tr_gemini_model_tip),
                    )
                    ModelChips(models = GeminiModels, selected = s.geminiModel, onPick = { s.geminiModel = it })
                }
            }
            TranslationProviderOption.OpenAi -> item(key = "openai") {
                MiruSectionCard(
                    title = stringResource(R.string.st_provider_openai),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_openai_url),
                        value = s.openAiBaseUrl,
                        onChange = { s.openAiBaseUrl = it },
                        tip = stringResource(R.string.st_tr_openai_url_tip),
                        error = localNetwork.errorRes?.let { stringResource(it) },
                    )
                    if (localNetwork.required) {
                        MiruRowDivider()
                        SettingsValueRow(
                            title = stringResource(R.string.st_tr_local_network_allow),
                            subtitle = stringResource(
                                if (localNetwork.denied) {
                                    R.string.st_tr_local_network_denied
                                } else {
                                    R.string.st_tr_local_network_allow_desc
                                },
                            ),
                            value = stringResource(R.string.st_tr_local_network_grant),
                            onClick = localNetwork.request,
                        )
                    }
                    MiruRowDivider()
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_openai_keys),
                        value = s.openAiKeys,
                        onChange = { s.openAiKeys = it },
                        placeholder = "sk-…\nsk-…",
                        tip = stringResource(R.string.st_tr_openai_keys_tip),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                    )
                    MiruRowDivider()
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_model),
                        value = s.openAiModel,
                        onChange = { s.openAiModel = it },
                        tip = stringResource(R.string.st_tr_openai_model_tip),
                    )
                    ModelChips(models = OpenAiModels, selected = s.openAiModel, onPick = { s.openAiModel = it })
                }
            }
            TranslationProviderOption.OpenAiCompatible -> item(key = "openai_compatible") {
                MiruSectionCard(
                    title = stringResource(R.string.st_provider_openai_compatible),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_openai_url),
                        value = s.openAiCompatibleBaseUrl,
                        onChange = { s.openAiCompatibleBaseUrl = it },
                        tip = stringResource(R.string.st_tr_openai_compatible_url_tip),
                        error = localNetwork.errorRes?.let { stringResource(it) },
                    )
                    if (localNetwork.required) {
                        MiruRowDivider()
                        SettingsValueRow(
                            title = stringResource(R.string.st_tr_local_network_allow),
                            subtitle = stringResource(
                                if (localNetwork.denied) {
                                    R.string.st_tr_local_network_denied
                                } else {
                                    R.string.st_tr_local_network_allow_desc
                                },
                            ),
                            value = stringResource(R.string.st_tr_local_network_grant),
                            onClick = localNetwork.request,
                        )
                    }
                    MiruRowDivider()
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_openai_keys),
                        value = s.openAiCompatibleKeys,
                        onChange = { s.openAiCompatibleKeys = it },
                        placeholder = "sk-…\nsk-…",
                        tip = stringResource(R.string.st_tr_openai_keys_tip),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                    )
                    MiruRowDivider()
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_model),
                        value = s.openAiCompatibleModel,
                        onChange = { s.openAiCompatibleModel = it },
                        tip = stringResource(R.string.st_tr_openai_compatible_model_tip),
                    )
                }
            }
            TranslationProviderOption.Anthropic -> item(key = "anthropic") {
                MiruSectionCard(
                    title = stringResource(R.string.st_provider_anthropic),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_anthropic_keys),
                        value = s.anthropicKeys,
                        onChange = { s.anthropicKeys = it },
                        placeholder = "sk-ant-…\nsk-ant-…",
                        tip = stringResource(R.string.st_tr_anthropic_keys_tip),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                    )
                    MiruRowDivider()
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_model),
                        value = s.anthropicModel,
                        onChange = { s.anthropicModel = it },
                        tip = stringResource(R.string.st_tr_anthropic_model_tip),
                    )
                }
            }
            TranslationProviderOption.DeepL -> item(key = "deepl") {
                MiruSectionCard(
                    title = stringResource(R.string.st_provider_deepl),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_deepl_keys),
                        value = s.deeplKeys,
                        onChange = { s.deeplKeys = it },
                        placeholder = "…:fx\n…:fx",
                        tip = stringResource(R.string.st_tr_deepl_keys_tip),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                    )
                }
            }
            TranslationProviderOption.YandexCloud -> item(key = "yandex_cloud") {
                MiruSectionCard(
                    title = stringResource(R.string.st_provider_yandex_cloud),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_yandex_cloud_keys),
                        value = s.yandexCloudKeys,
                        onChange = { s.yandexCloudKeys = it },
                        placeholder = "AQVN…\nAQVN…",
                        tip = stringResource(R.string.st_tr_yandex_cloud_keys_tip),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                    )
                    MiruRowDivider()
                    SettingsTextField(
                        label = stringResource(R.string.st_tr_yandex_cloud_folder),
                        value = s.yandexCloudFolderId,
                        onChange = { s.yandexCloudFolderId = it },
                        tip = stringResource(R.string.st_tr_yandex_cloud_folder_tip),
                    )
                }
            }
            TranslationProviderOption.GoogleFree,
            TranslationProviderOption.YandexFree,
            TranslationProviderOption.YandexPa -> Unit
        }
        item(key = "advanced") {
            val maxConcurrency = providerOption.maxChunkConcurrency
            val concurrency = s.chunkConcurrency.coerceIn(1, maxConcurrency)
            MiruSectionCard(
                title = stringResource(R.string.st_tr_advanced),
                description = stringResource(R.string.st_tr_advanced_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsSliderRow(
                    title = stringResource(R.string.st_tr_timeout),
                    subtitle = stringResource(R.string.st_tr_timeout_desc),
                    valueText = stringResource(R.string.st_tr_seconds_fmt, s.timeoutSeconds),
                    value = s.timeoutSeconds.toFloat(),
                    range = 30f..600f,
                    steps = 18,
                    onChange = { s.timeoutSeconds = it.toInt() / 30 * 30 },
                )
                MiruRowDivider()
                SettingsSliderRow(
                    title = stringResource(R.string.st_tr_attempts),
                    subtitle = stringResource(R.string.st_tr_attempts_desc),
                    valueText = s.maxAttempts.toString(),
                    value = s.maxAttempts.toFloat(),
                    range = 1f..10f,
                    steps = 8,
                    onChange = { s.maxAttempts = it.toInt() },
                )
                MiruRowDivider()
                SettingsSliderRow(
                    title = stringResource(R.string.st_tr_max_chars),
                    subtitle = stringResource(R.string.st_tr_max_chars_desc),
                    valueText = if (s.maxCharsPerRequest == 0) {
                        stringResource(R.string.st_tr_tokens_auto)
                    } else {
                        s.maxCharsPerRequest.toString()
                    },
                    value = s.maxCharsPerRequest.toFloat(),
                    range = 0f..50_000f,
                    steps = 49,
                    onChange = { s.maxCharsPerRequest = it.toInt() / 1_000 * 1_000 },
                )
                MiruRowDivider()
                SettingsSliderRow(
                    title = stringResource(R.string.st_tr_concurrency),
                    subtitle = stringResource(R.string.st_tr_concurrency_desc),
                    valueText = concurrency.toString(),
                    value = concurrency.toFloat(),
                    range = 1f..maxConcurrency.toFloat(),
                    steps = maxConcurrency - 2,
                    onChange = { s.chunkConcurrency = it.toInt().coerceIn(1, maxConcurrency) },
                )
            }
        }
        if (isLlm) {
            item(key = "prompts") {
                MiruSectionCard(
                    title = stringResource(R.string.st_tr_prompts),
                    description = stringResource(R.string.st_tr_prompts_desc),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SettingsValueRow(
                        title = stringResource(R.string.st_tr_system_prompt),
                        value = if (s.activePrompt.isBlank()) {
                            stringResource(R.string.st_tr_prompt_default)
                        } else {
                            stringResource(R.string.st_tr_prompt_custom)
                        },
                        onClick = { promptSheet = true },
                    )
                    MiruRowDivider()
                    SettingsValueRow(
                        title = stringResource(R.string.st_tr_novel_prompts),
                        subtitle = stringResource(R.string.st_tr_novel_prompts_desc),
                        value = stringResource(R.string.st_tr_novel_prompts_count_fmt, s.novelPrompts.size),
                        onClick = { onOpen(SettingsRoutes.NovelPrompts) },
                    )
                }
            }
            item(key = "limits") {
                MiruSectionCard(
                    title = stringResource(R.string.st_tr_limits),
                    description = stringResource(R.string.st_tr_limits_desc),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SettingsSliderRow(
                        title = stringResource(R.string.st_tr_tokens),
                        subtitle = stringResource(R.string.st_tr_tokens_desc),
                        valueText = if (s.maxOutputTokens == 0) {
                            stringResource(R.string.st_tr_tokens_auto)
                        } else {
                            s.maxOutputTokens.toString()
                        },
                        value = s.maxOutputTokens.toFloat(),
                        range = 0f..16_000f,
                        steps = 15,
                        onChange = { s.maxOutputTokens = it.toInt() / 1000 * 1000 },
                    )
                }
            }
        }
        item(key = "display") {
            MiruSectionCard(
                title = stringResource(R.string.st_tr_display),
                description = stringResource(R.string.st_tr_display_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                // NoveLA semantics: off / translation first / original first.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MiruSpace.L),
                ) {
                    MiruFilterChip(
                        selected = !s.parallelEnabled,
                        onClick = { s.parallelEnabled = false },
                        label = stringResource(R.string.st_off),
                    )
                    MiruFilterChip(
                        selected = s.parallelEnabled && s.parallelOrder == "TRANSLATION_FIRST",
                        onClick = {
                            s.parallelEnabled = true
                            s.parallelOrder = "TRANSLATION_FIRST"
                        },
                        label = stringResource(R.string.st_tr_order_translation),
                    )
                    MiruFilterChip(
                        selected = s.parallelEnabled && s.parallelOrder == "ORIGINAL_FIRST",
                        onClick = {
                            s.parallelEnabled = true
                            s.parallelOrder = "ORIGINAL_FIRST"
                        },
                        label = stringResource(R.string.st_tr_order_original),
                    )
                }
            }
        }
        item(key = "novels") {
            // Per-novel overrides sit next to the global pair they inherit
            // from; the per-plugin overrides follow below.
            MiruSectionCard(
                title = stringResource(R.string.st_tr_novels),
                description = stringResource(R.string.st_tr_novels_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsValueRow(
                    title = stringResource(R.string.st_tr_novels_open),
                    value = stringResource(
                        R.string.st_tr_novels_enabled_fmt,
                        s.novelTranslation.values.count { it.enabled },
                    ),
                    onClick = { onOpen(SettingsRoutes.NovelTranslation) },
                )
            }
        }
        item(key = "plugins") {
            MiruSectionCard(
                title = stringResource(R.string.st_tr_plugins),
                description = stringResource(R.string.st_tr_plugins_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                SettingsValueRow(
                    title = stringResource(R.string.st_tr_plugins_open),
                    value = stringResource(
                        R.string.st_tr_plugins_enabled_fmt,
                        s.pluginTranslation.values.count { it.enabled },
                    ),
                    onClick = { onOpen(SettingsRoutes.PluginTranslation) },
                )
            }
        }
    }

    if (promptSheet) {
        PromptManagerSheet(onClose = { promptSheet = false })
    }
}

@Composable
private fun ModelChips(models: List<String>, selected: String, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MiruSpace.L)
            .padding(bottom = MiruSpace.M),
    ) {
        models.forEach { model ->
            MiruFilterChip(selected = selected == model, onClick = { onPick(model) }, label = model)
        }
    }
}

// The LLM system prompt: editor with the two placeholders, saved presets
// (tap loads, cross deletes), "save as preset" by name, and the locale switch
// for the language names put into the placeholders. Applies on Apply.
@Composable
fun PromptManagerSheet(onClose: () -> Unit) {
    val s = AppSettings
    var text by remember { mutableStateOf(s.activePrompt) }
    var englishNames by remember { mutableStateOf(s.promptEnglishLocale) }
    var presetName by remember { mutableStateOf("") }
    val extra = LocalMiruExtraColors.current
    val clean = presetName.trim()

    AppBottomSheet(
        title = stringResource(R.string.st_tr_system_prompt),
        description = stringResource(R.string.st_tr_prompt_sheet_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.st_apply),
        onDone = {
            s.activePrompt = text
            s.promptEnglishLocale = englishNames
            onClose()
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.st_tr_prompt_label)) },
            placeholder = { Text(stringResource(R.string.st_tr_prompt_placeholder)) },
            minLines = 4,
            maxLines = 10,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.st_tr_prompt_placeholders),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.Xs),
        )
        SheetSection(title = stringResource(R.string.st_tr_presets), initiallyExpanded = true) {
            if (s.promptPresets.isEmpty()) {
                Text(
                    text = stringResource(R.string.st_tr_presets_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                )
            }
            s.promptPresets.toList().forEach { (name, prompt) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { text = prompt }, modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { s.promptPresets.removeAll { it.first == name } }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.st_delete),
                            tint = extra.muted,
                            modifier = Modifier.padding(0.dp),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it.take(32) },
                label = { Text(stringResource(R.string.st_tr_preset_name)) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.S),
            )
            TextButton(
                onClick = {
                    s.promptPresets.removeAll { it.first.equals(clean, ignoreCase = true) }
                    s.promptPresets.add(0, clean to text)
                    presetName = ""
                },
                enabled = clean.isNotEmpty() && text.isNotBlank(),
            ) {
                Text(
                    text = stringResource(R.string.st_tr_preset_save),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        SettingsSwitchRow(
            title = stringResource(R.string.st_tr_english_names),
            subtitle = stringResource(R.string.st_tr_english_names_desc),
            checked = englishNames,
            onChange = { englishNames = it },
        )
        TextButton(onClick = { text = "" }, enabled = text.isNotEmpty()) {
            Text(
                text = stringResource(R.string.st_tr_prompt_reset),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
