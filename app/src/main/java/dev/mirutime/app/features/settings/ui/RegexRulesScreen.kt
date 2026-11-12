@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.settings.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.MaxRegexRulesFileChars
import dev.mirutime.app.features.settings.data.exportRegexRules
import dev.mirutime.app.features.settings.data.importRegexRules
import dev.mirutime.app.shell.LocalImeBottom
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import dev.mirutime.core.text.RegexRuleTarget
import dev.mirutime.core.text.applyRulesFor
import dev.mirutime.core.text.regexError
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruCompactButton
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruInlineField
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSearchBar
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruTextField
import dev.mirutime.uikit.SettingsPage
import dev.mirutime.uikit.compactTouchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize

// Touch box shared by every compact control in a rule line: the 24dp chip
// face plus its 8dp ring, floored at 32dp.
private val RuleControlTouch = compactTouchTarget(MiruSize.Chip)

// Regex cleanup: one compact screen for the global list and one novel's list.
// Every rule is a single line (drag, switch, pattern, replacement, [W],
// delete) and each field autosaves. Apply-to and whole words are list-level:
// apply-to is a data property of the whole list, [W] is a batch toggle over it.
@Composable
fun RegexRulesScreen(
    novelUrl: String?,
    contentPadding: PaddingValues,
) {
    val s = AppSettings
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutines = rememberCoroutineScope()
    val extra = LocalMiruExtraColors.current
    val initialSample = stringResource(R.string.st_rx_sample_text)
    val ime = LocalImeBottom.current
    val wrapToolbar = LocalDensity.current.fontScale >= 1.5f
    var sample by remember { mutableStateOf(initialSample) }
    var query by rememberSaveable { mutableStateOf("") }
    // Opened from a book, the book's own list is the initial scope.
    var bookScope by rememberSaveable { mutableStateOf(novelUrl != null) }
    var previewOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RegexRule?>(null) }
    var autoFocusId by remember { mutableStateOf<Long?>(null) }

    val scopedNovel = if (bookScope) novelUrl else null
    // Read the store directly: a fast gesture or keystroke must not write back
    // an order or text frame from an older composition.
    fun currentRules(): List<RegexRule> =
        if (scopedNovel == null) s.globalRegexRules.toList() else s.novelRegexRules[scopedNovel].orEmpty()

    fun store(list: List<RegexRule>) {
        if (scopedNovel == null) s.replaceGlobalRules(list) else s.replaceNovelRules(scopedNovel, list)
    }

    fun edit(id: Long, change: (RegexRule) -> RegexRule) {
        store(currentRules().map { if (it.id == id) change(it) else it })
    }

    fun setApplyTo(target: RegexRuleScope) {
        if (scopedNovel == null) {
            s.setGlobalRegexApplyTo(target)
        } else {
            s.setNovelRegexApplyTo(scopedNovel, target)
        }
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    val rules = currentRules()
    val applyTo = s.regexApplyTo(scopedNovel)
    val trimmedQuery = query.trim()
    fun visibleOrder(list: List<RegexRule>): List<RegexRule> =
        if (trimmedQuery.isEmpty()) {
            list
        } else {
            list.filter {
                it.pattern.contains(trimmedQuery, ignoreCase = true) ||
                    it.replacement.contains(trimmedQuery, ignoreCase = true)
            }
        }
    val shown = remember(rules, trimmedQuery) { visibleOrder(rules) }
    val duplicatePatterns = remember(rules) {
        rules
            .filter { it.pattern.isNotBlank() }
            .groupingBy { it.pattern }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }
    val enabledCount = remember(rules) { rules.count { it.enabled } }
    val scopeLabel = stringResource(if (scopedNovel == null) R.string.st_rx_global else R.string.st_rx_book)
    val applyLabel = applyToLabel(applyTo)

    // Moves one position inside the visible (search-filtered) order and swaps
    // the two neighbours in the full list, so hidden rules keep their place.
    // The order comes from the store, not from this composition: one drag can
    // cross several thresholds before the next frame.
    fun move(id: Long, direction: Int) {
        val list = currentRules().toMutableList()
        val visible = visibleOrder(list)
        val at = visible.indexOfFirst { it.id == id }
        val neighbor = visible.getOrNull(at + direction) ?: return
        val from = list.indexOfFirst { it.id == id }
        val to = list.indexOfFirst { it.id == neighbor.id }
        if (from < 0 || to < 0) return
        list.add(to, list.removeAt(from))
        store(list)
    }

    fun addRule() {
        val list = currentRules()
        val id = s.newRuleId()
        val wholeWords = list.isEmpty() || list.any { it.wholeWords }
        store(list + RegexRule(id, "", "", "", true, wholeWords, applyTo))
        query = ""
        autoFocusId = id
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val exportRules = currentRules()
        val exportApplyTo = s.regexApplyTo(scopedNovel)
        coroutines.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(exportRegexRules(exportRules, exportApplyTo).toByteArray())
                    } ?: error("No output stream")
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
            toast(resources.getString(if (ok) R.string.st_rx_exported else R.string.st_rx_export_failed))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val existingPatterns = currentRules().map { it.pattern }.toSet()
        coroutines.launch {
            val imported = withContext(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("No input stream")
                    require(bytes.size <= MaxRegexRulesFileChars) { "Rules file is too large" }
                    importRegexRules(
                        text = bytes.toString(Charsets.UTF_8),
                        existingPatterns = existingPatterns,
                        newId = s::newRuleId,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            imported.fold(
                onSuccess = { result ->
                    // Import is additive; the file's apply-to becomes the scope's.
                    if (scopedNovel == null) {
                        s.setGlobalRegexApplyTo(result.applyTo)
                    } else {
                        s.setNovelRegexApplyTo(scopedNovel, result.applyTo)
                    }
                    store(currentRules() + result.added)
                    toast(
                        resources.getString(
                            R.string.st_rx_imported_fmt,
                            result.added.size,
                            result.duplicates + result.invalid,
                        ),
                    )
                },
                onFailure = { toast(resources.getString(R.string.st_rx_import_failed)) },
            )
        }
    }

    val focus = LocalFocusManager.current
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focus.clearFocus() })
            },
    ) {
    SettingsPage(
        contentPadding,
        bottomInset = ime + MiruSize.ButtonCompact,
    ) {
        item(key = "regex") {
            MiruCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                // Scope switcher, live counter, batch IO.
                val scopeItems: @Composable () -> Unit = {
                    if (novelUrl != null) {
                        MiruFilterChip(
                            selected = !bookScope,
                            onClick = { bookScope = false },
                            label = stringResource(R.string.st_rx_global),
                            touchTarget = RuleControlTouch,
                        )
                        MiruFilterChip(
                            selected = bookScope,
                            onClick = { bookScope = true },
                            label = stringResource(R.string.st_rx_book),
                            touchTarget = RuleControlTouch,
                        )
                    } else {
                        // The global settings entry has no book scope, but the
                        // list still names its scope like the per-book screen.
                        MiruFilterChip(
                            selected = true,
                            onClick = {},
                            label = stringResource(R.string.st_rx_global),
                            touchTarget = RuleControlTouch,
                        )
                    }
                    if (rules.isNotEmpty()) {
                        Text(
                            text = "$enabledCount/${rules.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.muted,
                            modifier = Modifier.padding(horizontal = MiruSpace.Xxs),
                        )
                    }
                }
                val ioItems: @Composable () -> Unit = {
                    MiruFilterChip(
                        selected = false,
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                        label = stringResource(R.string.st_rx_import),
                        touchTarget = RuleControlTouch,
                    )
                    MiruFilterChip(
                        selected = false,
                        onClick = { exportLauncher.launch("regex-rules.json") },
                        label = stringResource(R.string.st_rx_export),
                        touchTarget = RuleControlTouch,
                    )
                    MiruFilterChip(
                        selected = false,
                        onClick = { clearOpen = true },
                        label = stringResource(R.string.st_rx_clear),
                        touchTarget = RuleControlTouch,
                    )
                }
                if (wrapToolbar) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                        verticalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
                    ) {
                        scopeItems()
                        ioItems()
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
                    ) {
                        scopeItems()
                        Spacer(Modifier.weight(1f))
                        ioItems()
                    }
                }
                RuleDivider()
                // Search + on-demand preview.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
                ) {
                    MiruSearchBar(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.st_rx_search),
                        onClear = { query = "" },
                        modifier = Modifier.weight(1f),
                        plate = false,
                    )
                    MiruFilterChip(
                        selected = false,
                        onClick = { previewOpen = true },
                        label = stringResource(R.string.st_rx_preview),
                        touchTarget = RuleControlTouch,
                    )
                }
                RuleDivider()
                // List-level settings: where the rules apply and the [W] batch.
                val applyItems: @Composable () -> Unit = {
                    Text(
                        text = stringResource(R.string.st_rx_scope),
                        style = MaterialTheme.typography.labelSmall,
                        color = extra.muted,
                    )
                    listOf(RegexRuleScope.Both, RegexRuleScope.Original, RegexRuleScope.Translation)
                        .forEach { target ->
                            MiruFilterChip(
                                selected = applyTo == target,
                                onClick = { setApplyTo(target) },
                                label = applyToChipLabel(target),
                                touchTarget = RuleControlTouch,
                            )
                        }
                }
                val wholeWordsMaster: @Composable () -> Unit = {
                    if (rules.isNotEmpty()) {
                        WholeWordsMaster(
                            active = rules.any { it.wholeWords },
                            onClick = {
                                val next = !rules.all { it.wholeWords }
                                store(currentRules().map { it.copy(wholeWords = next) })
                            },
                        )
                    }
                }
                if (wrapToolbar) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                        verticalArrangement = Arrangement.spacedBy(MiruSpace.Xxs),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
                    ) {
                        applyItems()
                        wholeWordsMaster()
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
                    ) {
                        applyItems()
                        Spacer(Modifier.weight(1f))
                        wholeWordsMaster()
                    }
                }
                RuleDivider()
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (rules.isEmpty()) R.string.st_rx_empty else R.string.st_rx_no_match,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = extra.muted,
                        modifier = Modifier.padding(
                            horizontal = MiruSpace.S,
                            vertical = MiruSpace.L,
                        ),
                    )
                } else {
                    Column(modifier = Modifier.padding(vertical = MiruSpace.Xxs)) {
                        shown.forEach { rule ->
                            key(rule.id) {
                                RegexRuleLine(
                                    rule = rule,
                                    autoFocus = autoFocusId == rule.id,
                                    onAutoFocusConsumed = { autoFocusId = null },
                                    onMove = { direction -> move(rule.id, direction) },
                                    onToggleEnabled = { edit(rule.id) { it.copy(enabled = !it.enabled) } },
                                    onPatternChange = { text -> edit(rule.id) { it.copy(pattern = text) } },
                                    onReplacementChange = { text -> edit(rule.id) { it.copy(replacement = text) } },
                                    onToggleWholeWords = {
                                        edit(rule.id) { it.copy(wholeWords = !it.wholeWords) }
                                    },
                                    patternError = rule.pattern.isNotBlank() && (
                                        regexError(rule.pattern) != null ||
                                            rule.pattern in duplicatePatterns
                                        ),
                                    onDelete = { pendingDelete = rule },
                                )
                            }
                        }
                    }
                }
                RuleDivider()
            }
        }
    }
    RegexBottomBar(
        addLabel = stringResource(R.string.st_rx_add),
        onAdd = { addRule() },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(
                start = MiruSize.ScreenPad,
                end = MiruSize.ScreenPad,
                bottom = contentPadding.calculateBottomPadding() + ime,
            )
            // With the keyboard open the bar would land between the search
            // field and Apply; drop it one compact row so it covers Apply
            // cleanly instead of half-covering the search row.
            .offset(y = if (ime > 0.dp) MiruSpace.Xl else 0.dp),
    )
    }

    if (previewOpen) {
        // The preview shows what the reader will actually get: global rules,
        // then the book's own, each already projected with its apply-to.
        val effective = s.effectiveRules(novelUrl)
        val effectiveEnabled = effective.count { it.enabled }
        val original = applyRulesFor(effective, sample, RegexRuleTarget.Original)
        val translation = applyRulesFor(effective, sample, RegexRuleTarget.Translation)
        AppBottomSheet(
            title = stringResource(R.string.st_rx_preview),
            description = stringResource(R.string.st_rx_preview_desc),
            onClose = { previewOpen = false },
        ) {
            Text(
                text = stringResource(R.string.st_rx_preview_count_fmt, effectiveEnabled, applyLabel),
                style = MaterialTheme.typography.labelSmall,
                color = extra.accent,
            )
            MiruTextField(
                value = sample,
                onValueChange = { sample = it },
                label = stringResource(R.string.st_rx_sample),
                singleLine = false,
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.S),
                plate = false,
            )
            if (original == sample && translation == sample) {
                Text(
                    text = stringResource(R.string.st_rx_no_changes),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(top = MiruSpace.M),
                )
            } else {
                RegexResultBlock(stringResource(R.string.st_rx_scope_original), original)
                RegexResultBlock(stringResource(R.string.st_rx_scope_translation), translation)
            }
        }
    }

    if (clearOpen) {
        AppBottomSheet(
            title = stringResource(R.string.st_rx_clear_title),
            description = stringResource(R.string.st_rx_clear_desc_fmt, scopeLabel),
            onClose = { clearOpen = false },
            closeLabel = stringResource(R.string.st_cancel),
            doneLabel = stringResource(R.string.st_rx_clear_all),
            doneDanger = true,
            onDone = {
                store(emptyList())
                clearOpen = false
            },
        ) {
            Text(
                text = "$enabledCount/${rules.size}",
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
            )
        }
    }

    pendingDelete?.let { rule ->
        AppBottomSheet(
            title = stringResource(R.string.st_rx_delete_rule),
            description = stringResource(
                R.string.st_rx_delete_rule_desc_fmt,
                rule.pattern.ifBlank { "—" },
            ),
            onClose = { pendingDelete = null },
            closeLabel = stringResource(R.string.st_cancel),
            doneLabel = stringResource(R.string.st_delete),
            doneDanger = true,
            onDone = {
                store(currentRules().filter { it.id != rule.id })
                pendingDelete = null
            },
        ) {
            Text(
                text = rule.pattern.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RuleDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MiruSpace.S)
            .height(MiruSize.Stroke)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    )
}

@Composable
private fun applyToLabel(target: RegexRuleScope): String = stringResource(
    when (target) {
        RegexRuleScope.Both -> R.string.st_rx_scope_both
        RegexRuleScope.Original -> R.string.st_rx_scope_original
        RegexRuleScope.Translation -> R.string.st_rx_scope_translation
    },
)

@Composable
private fun applyToChipLabel(target: RegexRuleScope): String = stringResource(
    when (target) {
        RegexRuleScope.Both -> R.string.st_rx_apply_both
        RegexRuleScope.Original -> R.string.st_rx_apply_original
        RegexRuleScope.Translation -> R.string.st_rx_apply_translation
    },
)

// The sticky action bar: the one primary action pinned above the navigation
// inset, so a long rule list never hides it.
@Composable
private fun RegexBottomBar(
    addLabel: String,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = MiruSpace.Xs),
    ) {
        MiruCompactButton(
            label = addLabel,
            icon = Icons.Filled.Add,
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// One horizontal rule line: drag dots, compact switch, pattern, replacement,
// [W] and delete. Every part of the row is reachable at >= 32dp.
@Composable
private fun RegexRuleLine(
    rule: RegexRule,
    autoFocus: Boolean,
    onAutoFocusConsumed: () -> Unit,
    onMove: (Int) -> Unit,
    onToggleEnabled: () -> Unit,
    onPatternChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onToggleWholeWords: () -> Unit,
    patternError: Boolean,
    onDelete: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val patternFocus = remember { FocusRequester() }
    val requester = remember { BringIntoViewRequester() }
    if (autoFocus) {
        // Let the new row be laid out before focusing and scrolling it clear
        // of the keyboard (same two-frame wait as the rest of the app).
        LaunchedEffect(rule.id) {
            withFrameNanos { }
            withFrameNanos { }
            // Clear the intent first: the focus request triggers a recomposition
            // that disposes this effect.
            onAutoFocusConsumed()
            try {
                patternFocus.requestFocus()
                requester.bringIntoView()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                // The row left composition mid-layout; nothing to focus.
            }
        }
    }
    val ink = if (rule.enabled) MaterialTheme.colorScheme.onSurface else extra.muted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .defaultMinSize(minHeight = MiruSize.RowDense)
            .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
    ) {
        DragHandle(
            onMove = onMove,
            contentDescription = stringResource(R.string.st_rx_reorder),
        )
        MiniSwitch(
            checked = rule.enabled,
            contentDescription = stringResource(R.string.st_rx_enabled),
            onCheckedChange = { onToggleEnabled() },
        )
        RuleField(
            value = rule.pattern,
            onValueChange = onPatternChange,
            placeholder = stringResource(R.string.st_rx_pattern),
            description = stringResource(R.string.st_rx_pattern),
            ink = ink,
            isError = patternError,
            modifier = Modifier.weight(1f),
            focusRequester = patternFocus,
        )
        RuleField(
            value = rule.replacement,
            onValueChange = onReplacementChange,
            placeholder = stringResource(R.string.st_rx_replacement_empty),
            description = stringResource(R.string.st_rx_replacement),
            ink = ink,
            modifier = Modifier.weight(1f),
        )
        WholeWordsBadge(
            active = rule.wholeWords,
            contentDescription = stringResource(R.string.st_rx_whole_words),
            onClick = onToggleWholeWords,
        )
        DeleteRuleButton(
            contentDescription = stringResource(R.string.st_delete),
            onClick = onDelete,
        )
    }
}

// Autosaving single-line regex field: the shared inline field keeps the row
// at one compact line while the caller owns the save policy.
@Composable
private fun RuleField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String,
    ink: Color,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    MiruInlineField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        contentDescription = description,
        modifier = modifier,
        ink = ink,
        isError = isError,
        focusRequester = focusRequester,
    )
}

// 28x16 track, 12dp thumb: the enabled state of a rule in one small control.
@Composable
private fun MiniSwitch(
    checked: Boolean,
    contentDescription: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val density = LocalDensity.current
    val trackWidth = MiruSize.ControlCompact
    val trackHeight = MiruSize.Chip - MiruSpace.S
    val thumbSize = MiruSize.Chip / 2
    val travel = trackWidth - thumbSize - MiruSpace.Xxs * 2
    val offset by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        label = "rule-switch",
    )
    Box(
        modifier = Modifier
            .size(RuleControlTouch)
            .clip(CircleShape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(trackWidth, trackHeight)
                .clip(CircleShape)
                .background(if (checked) extra.accent else extra.muted.copy(alpha = 0.35f)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = MiruSpace.Xxs)
                    .offset { IntOffset(with(density) { offset.roundToPx() }, 0) }
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(if (checked) extra.onAccent else extra.muted),
            )
        }
    }
}

// The [W] legend made interactive: whole-word matching for one rule.
@Composable
private fun WholeWordsBadge(
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(MiruRadius.Badge)
    Box(
        modifier = Modifier
            .size(RuleControlTouch)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(MiruSize.Chip)
                .clip(shape)
                .background(if (active) extra.accent.copy(alpha = 0.16f) else Color.Transparent)
                .border(
                    MiruSize.Stroke,
                    if (active) extra.accent else MaterialTheme.colorScheme.outline,
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "W",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (active) extra.accent else extra.muted,
            )
        }
    }
}

// Same [W] face in the settings bar, with its label. Tapping flips every rule
// in the list; the active state means at least one rule matches whole words.
@Composable
private fun WholeWordsMaster(active: Boolean, onClick: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    val shape = RoundedCornerShape(MiruRadius.Chip)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides RuleControlTouch) {
        Box(
            modifier = Modifier
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClick)
                .minimumInteractiveComponentSize(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) extra.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .border(
                        MiruSize.Stroke,
                        if (active) extra.accent.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outline,
                        shape,
                    )
                    .padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
            ) {
                Text(
                    text = "W",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (active) extra.onAccent else extra.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(MiruRadius.Badge))
                        .background(
                            if (active) {
                                extra.accent
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        )
                        .padding(horizontal = MiruSpace.Xs, vertical = MiruSpace.Xxs),
                )
                Text(
                    text = stringResource(R.string.st_rx_whole_words),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onSurface else extra.muted,
                )
            }
        }
    }
}

// Drag handle: a vertical drag of half a row moves the rule one position. The
// handle is its own node, so it never takes focus from the fields.
@Composable
private fun DragHandle(onMove: (Int) -> Unit, contentDescription: String) {
    val extra = LocalMiruExtraColors.current
    val current = rememberUpdatedState(onMove)
    val step = with(LocalDensity.current) { MiruSize.RowDense.toPx() / 2f }
    Box(
        modifier = Modifier
            .size(RuleControlTouch)
            .clip(CircleShape)
            .pointerInput(Unit) {
                var accumulated = 0f
                detectDragGestures(
                    onDragEnd = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f },
                ) { change, drag ->
                    change.consume()
                    accumulated += drag.y
                    while (accumulated >= step) {
                        accumulated -= step
                        current.value(1)
                    }
                    while (accumulated <= -step) {
                        accumulated += step
                        current.value(-1)
                    }
                }
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(MiruSpace.M, MiruSize.Chip)) {
            val radius = MiruSpace.Xxs.toPx()
            listOf(0.22f, 0.5f, 0.78f).forEach { fy ->
                listOf(0.3f, 0.7f).forEach { fx ->
                    drawCircle(
                        color = extra.muted,
                        radius = radius,
                        center = Offset(size.width * fx, size.height * fy),
                    )
                }
            }
        }
    }
}

// A minus in a circle, as the spec's compact delete.
@Composable
private fun DeleteRuleButton(contentDescription: String, onClick: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    Box(
        modifier = Modifier
            .size(RuleControlTouch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(MiruSize.Chip)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                .border(MiruSize.Stroke, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(MiruSpace.M)
                    .height(MiruSize.Stroke)
                    .background(extra.muted),
            )
        }
    }
}

@Composable
private fun RegexResultBlock(label: String, text: String) {
    val extra = LocalMiruExtraColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.M),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = extra.muted,
        )
        Text(
            text = text.ifEmpty { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = MiruSpace.Xxs),
        )
    }
}
