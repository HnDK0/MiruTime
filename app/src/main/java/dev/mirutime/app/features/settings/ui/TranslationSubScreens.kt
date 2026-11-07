@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruIconButton
import dev.mirutime.uikit.MiruPairButton
import dev.mirutime.uikit.MiruPickerHeader
import dev.mirutime.uikit.MiruPickerRow
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.uikit.SettingsValueRow
import dev.mirutime.uikit.SheetSection
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.library.data.LibraryDataProvider
import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.NovelPrompt
import dev.mirutime.app.features.settings.data.NovelTranslation
import dev.mirutime.app.features.settings.data.PluginTranslation
import dev.mirutime.app.features.settings.data.SettingsLanguages
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.app.features.settings.data.TranslationPair
import dev.mirutime.uikit.MiruSearchBar
import dev.mirutime.uikit.MiruTextField

// Novel-specific prompts (TRANSLATION_NOVEL_PROMPTS): a searchable list of
// every novel that has its own LLM prompt. Prompts are CREATED from the book's
// translator tool; here they are reviewed, edited and deleted. `append` adds
// to the global prompt, otherwise it replaces it for that novel.
@Composable
fun NovelPromptsScreen(contentPadding: PaddingValues) {
    val s = AppSettings
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<NovelPrompt?>(null) }
    val shown = s.novelPrompts.filter {
        query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) ||
            it.prompt.contains(query.trim(), ignoreCase = true)
    }

    SettingsPage(contentPadding) {
        item(key = "search") {
            MiruCard(contentPadding = PaddingValues(MiruSpace.S)) {
                MiruSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.st_np_search),
                    onClear = { query = "" },
                    modifier = Modifier.fillMaxWidth(),
                    plate = false,
                )
            }
        }
        item(key = "list") {
            MiruSectionCard(
                title = stringResource(R.string.st_tr_novel_prompts),
                description = stringResource(R.string.st_np_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.st_np_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMiruExtraColors.current.muted,
                        modifier = Modifier.padding(MiruSpace.L),
                    )
                }
                shown.forEachIndexed { index, item ->
                    if (index > 0) MiruRowDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { editing = item }
                            .defaultMinSize(minHeight = MiruSize.Row)
                            .padding(horizontal = MiruSpace.L, vertical = MiruSpace.S),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(
                                    if (item.append) R.string.st_np_mode_append else R.string.st_np_mode_replace,
                                ).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalMiruExtraColors.current.accent,
                            )
                        }
                        Text(
                            text = item.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalMiruExtraColors.current.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    editing?.let { item ->
        var text by remember(item.bookUrl) { mutableStateOf(item.prompt) }
        var append by remember(item.bookUrl) { mutableStateOf(item.append) }
        AppBottomSheet(
            title = item.title,
            description = stringResource(R.string.st_np_sheet_desc),
            onClose = { editing = null },
            doneLabel = stringResource(R.string.st_save),
            onDone = {
                val index = s.novelPrompts.indexOfFirst { it.bookUrl == item.bookUrl }
                if (index >= 0) s.novelPrompts[index] = item.copy(prompt = text, append = append)
                editing = null
            },
        ) {
            MiruTextField(
                value = text,
                onValueChange = { text = it },
                label = stringResource(R.string.st_tr_prompt_label),
                singleLine = false,
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                modifier = Modifier.padding(top = MiruSpace.M),
            ) {
                MiruFilterChip(
                    selected = append,
                    onClick = { append = true },
                    label = stringResource(R.string.st_np_mode_append),
                )
                MiruFilterChip(
                    selected = !append,
                    onClick = { append = false },
                    label = stringResource(R.string.st_np_mode_replace),
                )
            }
            Text(
                text = stringResource(R.string.st_np_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
                modifier = Modifier.padding(top = MiruSpace.S),
            )
            TextButton(
                onClick = {
                    s.novelPrompts.removeAll { it.bookUrl == item.bookUrl }
                    editing = null
                },
            ) {
                Text(
                    text = stringResource(R.string.st_delete),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// First paint for the plugin/novel lists: skeleton rows in the real slots,
// never a lone "Loading…" line. Rows use the same card+divider language.
@Composable
private fun TranslationListSkeleton() {
    Column(
        modifier = Modifier.padding(horizontal = MiruSpace.L, vertical = MiruSpace.M),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
    ) {
        repeat(3) {
            SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 44.dp)
        }
    }
}

// Per-plugin translation (TRANSLATION_PLUGIN_* maps):
// each installed plugin can switch translation on, own a language pair and
// provider, translate only chapters (STANDARD) or also title + description
// (FULL), carry its own LLM prompt and hide translated titles in lists.
// The three levels combine: per-novel || per-plugin || global.
@Composable
fun PluginTranslationScreen(
    contentPadding: PaddingValues,
    provider: FinderDataProvider,
) {
    val s = AppSettings
    var sources by remember { mutableStateOf<List<CatalogSource>?>(null) }
    var editing by remember { mutableStateOf<CatalogSource?>(null) }
    LaunchedEffect(provider) {
        sources = provider.sources().filter { !it.local }
    }

    SettingsPage(contentPadding) {
        item(key = "list") {
            MiruSectionCard(
                title = stringResource(R.string.st_tr_plugins),
                description = stringResource(R.string.st_pt_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                val list = sources
                if (list == null) {
                    TranslationListSkeleton()
                } else {
                    list.forEachIndexed { index, source ->
                        if (index > 0) MiruRowDivider()
                        val config = s.pluginTranslation[source.id] ?: PluginTranslation()
                        SettingsValueRow(
                            title = source.name,
                            subtitle = if (config.enabled) {
                                stringResource(
                                    R.string.st_pt_summary_fmt,
                                    config.source.ifBlank { "·" },
                                    config.target.ifBlank { "·" },
                                    stringResource(if (config.full) R.string.st_pt_scope_full else R.string.st_pt_scope_standard),
                                )
                            } else {
                                stringResource(R.string.st_pt_off)
                            },
                            value = stringResource(if (config.enabled) R.string.st_on else R.string.st_off),
                            onClick = { editing = source },
                        )
                    }
                }
            }
        }
    }

    editing?.let { source ->
        PluginTranslationSheet(
            pluginId = source.id,
            name = source.name,
            onClose = { editing = null },
        )
    }
}

// Per-novel translation (TRANSLATION_BOOK_ENABLED_MAP /
// TRANSLATION_BOOK_LANG_PAIR): every book in the library can switch translation
// on and own a language pair; an empty side inherits the global value, switching
// the novel off resets it to inheritance. Rows mirror the per-plugin screen.
@Composable
fun NovelTranslationScreen(
    contentPadding: PaddingValues,
    provider: LibraryDataProvider,
) {
    val s = AppSettings
    var books by remember { mutableStateOf<List<LibraryBook>?>(null) }
    var editing by remember { mutableStateOf<LibraryBook?>(null) }
    LaunchedEffect(provider) {
        books = provider.books()
    }

    SettingsPage(contentPadding) {
        item(key = "list") {
            MiruSectionCard(
                title = stringResource(R.string.st_tr_novels),
                description = stringResource(R.string.st_tr_novels_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                val list = books
                if (list == null) {
                    TranslationListSkeleton()
                } else {
                    list.forEachIndexed { index, book ->
                        if (index > 0) MiruRowDivider()
                        val config = s.novelTranslation[book.bookKey] ?: NovelTranslation()
                        SettingsValueRow(
                            title = book.title,
                            // Enabled rows preview the effective pair: an
                            // empty side shows the global value it inherits.
                            subtitle = if (config.enabled) {
                                stringResource(
                                    R.string.st_pt_pair_fmt,
                                    config.source.ifBlank { s.globalSource.ifBlank { "·" } },
                                    config.target.ifBlank { s.globalTarget.ifBlank { "·" } },
                                )
                            } else {
                                stringResource(R.string.st_pt_off)
                            },
                            value = stringResource(if (config.enabled) R.string.st_on else R.string.st_off),
                            onClick = { editing = book },
                        )
                    }
                }
            }
        }
    }

    editing?.let { book ->
        NovelTranslationSheet(
            bookUrl = book.bookKey,
            name = book.title,
            onClose = { editing = null },
        )
    }
}

private enum class PluginPick { Source, Target }

// Full per-plugin translation editor. The editor
// itself is shared with the per-novel sheet and the book page.
@Composable
fun PluginTranslationSheet(pluginId: String, name: String, onClose: () -> Unit) {
    val s = AppSettings
    var config by remember { mutableStateOf(s.pluginTranslation[pluginId] ?: TranslationConfig()) }

    AppBottomSheet(
        title = name,
        description = stringResource(R.string.st_pt_sheet_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.st_save),
        // A resolved pair is required and source must differ from target; the
        // helper under the pair says which side is missing or why it clashes.
        doneEnabled = isTranslationSaveEnabled(config),
        onDone = {
            s.pluginTranslation[pluginId] = config
            onClose()
        },
    ) {
        TranslationConfigContent(config) { config = it }
    }
}

// Full per-novel translation sheet: the same editor
// as the per-plugin sheet, saved into the per-novel map.
@Composable
private fun NovelTranslationSheet(bookUrl: String, name: String, onClose: () -> Unit) {
    val s = AppSettings
    var config by remember { mutableStateOf(s.novelTranslation[bookUrl] ?: TranslationConfig()) }

    AppBottomSheet(
        title = name,
        description = stringResource(R.string.st_tr_novel_sheet_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.st_save),
        doneEnabled = isTranslationSaveEnabled(config),
        onDone = {
            s.novelTranslation[bookUrl] = config
            onClose()
        },
    ) {
        TranslationConfigContent(config) { config = it }
    }
}

// Source → target pair controls shared by the per-plugin and per-novel
// editors: the two buttons, the same-pair error, the "not resolved yet"
// helper and the swap. Everything works on the effective pair - an inherited
// side resolves to the global value and names it in the button ("Global —
// English") - so the labels, the guard and the swap always describe the pair
// that would actually be used. Picking stacks the language picker on top of
// the current sheet.
@Composable
internal fun TranslationPairControls(
    source: String,
    target: String,
    onPairChange: (source: String, target: String) -> Unit,
    // Global level: there is nothing to inherit, so a blank side reads "Not
    // set" instead of naming the effective global value.
    isGlobal: Boolean = false,
    // Section header override; the per-novel / per-plugin editors keep the
    // shared "Language pair" wording.
    sectionTitle: String? = null,
) {
    val s = AppSettings
    val extra = LocalMiruExtraColors.current
    var pick by remember { mutableStateOf<PluginPick?>(null) }
    val effectiveSource = if (isGlobal) source else source.ifBlank { s.globalSource }
    val effectiveTarget = if (isGlobal) target else target.ifBlank { s.globalTarget }
    val pairResolved = effectiveSource.isNotBlank() && effectiveTarget.isNotBlank()
    val samePair = pairResolved && effectiveSource == effectiveTarget
    val effectiveSourceLabel = if (effectiveSource.isBlank()) {
        stringResource(R.string.st_tr_not_set)
    } else {
        uiLanguageName(effectiveSource)
    }
    val effectiveTargetLabel = if (effectiveTarget.isBlank()) {
        stringResource(R.string.st_tr_not_set)
    } else {
        uiLanguageName(effectiveTarget)
    }

    SheetSection(title = sectionTitle ?: stringResource(R.string.st_pt_pair)) {
        // One card with two equal halves: the caption lives inside its own
        // half, so the two language windows always line up.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MiruSpace.S),
        ) {
            PairButton(
                caption = stringResource(R.string.st_tr_source),
                // An inherited side names the effective global value
                // outright instead of an abstract "from global"; the
                // global level itself just says "Not set".
                label = when {
                    source.isNotBlank() -> uiLanguageName(source)
                    isGlobal -> stringResource(R.string.st_tr_not_set)
                    else -> stringResource(R.string.st_pt_inherit_fmt, effectiveSourceLabel)
                },
                onClick = { pick = PluginPick.Source },
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowRightAlt,
                contentDescription = null,
                tint = extra.muted,
                modifier = Modifier.size(MiruSize.Indicator),
            )
            PairButton(
                caption = stringResource(R.string.st_tr_target),
                label = when {
                    target.isNotBlank() -> uiLanguageName(target)
                    isGlobal -> stringResource(R.string.st_tr_not_set)
                    else -> stringResource(R.string.st_pt_inherit_fmt, effectiveTargetLabel)
                },
                onClick = { pick = PluginPick.Target },
                modifier = Modifier.weight(1f),
            )
        }
        if (samePair) {
            Text(
                text = stringResource(R.string.st_pt_pair_same),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (!pairResolved && !isGlobal) {
            // Effective pair, recomputed on every pick: once both sides
            // resolve the helper disappears instead of showing stale globals.
            Text(
                text = stringResource(
                    R.string.st_pt_global_pair_fmt,
                    effectiveSourceLabel,
                    effectiveTargetLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(top = MiruSpace.S),
            )
        }
    }

    pick?.let { which ->
        val pickingTarget = which == PluginPick.Target
        LanguagePickerSheet(
            title = stringResource(if (pickingTarget) R.string.st_tr_target else R.string.st_tr_source),
            // The list tints the language in effect for this side - explicit
            // or inherited from the global one - so an inherited side still
            // shows where it currently points.
            selected = if (pickingTarget) effectiveTarget else effectiveSource,
            // Tapping an explicit selection clears it back to global; tapping
            // the inherited value pins it as the explicit language.
            onPick = { code ->
                val explicit = if (pickingTarget) target else source
                val next = if (code == explicit) "" else code
                val nextSource = if (pickingTarget) source else next
                val nextTarget = if (pickingTarget) next else target
                onPairChange(nextSource, nextTarget)
                // A completed pair joins the recents (newest first).
                s.recordTranslationPair(nextSource, nextTarget)
                pick = null
            },
            // A recent pair applies both languages at once and joins the
            // recents again, so the newest pair stays first.
            onApplyPair = { pair ->
                onPairChange(pair.source, pair.target)
                s.recordTranslationPair(pair.source, pair.target)
                pick = null
            },
            onClose = { pick = null },
        )
    }
}

// One half of the source → target pair. Both halves share the same box and
// text style: an inherited side is named by its label ("Global — English"),
// not by a different border, so the two windows always read as one control.
@Composable
private fun PairButton(
    caption: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MiruPairButton(
        caption = caption,
        label = label,
        onClick = onClick,
        modifier = modifier,
    )
}

// Language picker of the per-plugin and per-novel pairs: search, recent pairs
// (apply both languages or delete), favorite languages (star) and the full
// list. Uses the same AppBottomSheet shell and Close pattern as every other
// sheet, so it stacks over the editor and returns to it. The row tinted +
// accent-labelled is the language currently in effect for the picked side
// (explicit or inherited); tapping the explicit one clears it back to the
// global value. The plain language list still changes only the picked side.
@Composable
private fun LanguagePickerSheet(
    title: String,
    selected: String,
    onPick: (String) -> Unit,
    onApplyPair: (TranslationPair) -> Unit,
    onClose: () -> Unit,
) {
    val s = AppSettings
    val extra = LocalMiruExtraColors.current
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val matches = { code: String ->
        q.isEmpty() || uiLanguageName(code).contains(q, ignoreCase = true) ||
            code.contains(q, ignoreCase = true)
    }
    val languages = SettingsLanguages.translation.filter(matches)
    val favorites = s.translationFavorites.toList()
    val favoriteLanguages = languages.filter { it in favorites }
    val recentPairs = s.translationRecentPairs.toList()

    AppBottomSheet(
        title = title,
        description = "",
        onClose = onClose,
    ) {
        MiruSearchBar(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.st_pt_lang_search),
            onClear = { query = "" },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MiruSpace.S))
        MiruPickerHeader(
            stringResource(R.string.st_pt_recents),
            modifier = Modifier.padding(top = MiruSpace.S, bottom = MiruSpace.Xxs),
        )
        if (recentPairs.isEmpty()) {
            Text(
                text = stringResource(R.string.st_pt_recents_empty),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(vertical = MiruSpace.Xs),
            )
        }
        recentPairs.forEach { pair ->
            RecentPairRow(
                from = uiLanguageName(pair.source),
                to = uiLanguageName(pair.target),
                onApply = { onApplyPair(pair) },
                onRemove = { s.removeTranslationPair(pair) },
            )
            Spacer(Modifier.height(MiruSpace.Xs))
        }
        HorizontalDivider(Modifier.padding(vertical = MiruSpace.S))
        if (favoriteLanguages.isNotEmpty()) {
            MiruPickerHeader(
                stringResource(R.string.st_pt_favorites),
                modifier = Modifier.padding(top = MiruSpace.S, bottom = MiruSpace.Xxs),
            )
            favoriteLanguages.forEach { code ->
                LanguageRow(
                    code = code,
                    selected = code == selected,
                    favorite = true,
                    onClick = { onPick(code) },
                    onToggleFavorite = { s.toggleTranslationFavorite(code) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = MiruSpace.S))
        }
        MiruPickerHeader(
            stringResource(R.string.st_pt_all_languages),
            modifier = Modifier.padding(top = MiruSpace.S, bottom = MiruSpace.Xxs),
        )
        languages.filter { it !in favorites }.forEach { code ->
            LanguageRow(
                code = code,
                selected = code == selected,
                favorite = false,
                onClick = { onPick(code) },
                onToggleFavorite = { s.toggleTranslationFavorite(code) },
            )
        }
        if (languages.isEmpty()) {
            Text(
                text = stringResource(R.string.st_pt_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.padding(vertical = MiruSpace.M),
            )
        }
    }
}

// A recent pair is a tonal card with an explicit swap icon and "Apply" label:
// the whole card applies the pair, the trash deletes it.
@Composable
private fun RecentPairRow(
    from: String,
    to: String,
    onApply: () -> Unit,
    onRemove: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(MiruRadius.Chip))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onApply)
            .padding(start = MiruSpace.M),
    ) {
        Icon(
            imageVector = Icons.Filled.SwapHoriz,
            contentDescription = null,
            tint = extra.accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(MiruSpace.S))
        Text(
            text = "$from → $to",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.st_apply),
            style = MaterialTheme.typography.labelLarge,
            color = extra.accent,
            modifier = Modifier.padding(start = MiruSpace.S),
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.st_delete),
                tint = extra.muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LanguageRow(
    code: String,
    selected: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    MiruPickerRow(
        title = "${uiLanguageName(code)} ($code)",
        selected = selected,
        onClick = onClick,
        trailing = {
            MiruIconButton(
                icon = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(
                    if (favorite) R.string.st_pt_fav_remove else R.string.st_pt_fav_add,
                ),
                onClick = onToggleFavorite,
                tint = if (favorite) extra.accent else extra.muted,
            )
        },
    )
}
