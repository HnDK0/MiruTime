@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mirutime.app.features.book.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.app.features.library.ui.isSystemShelf
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.app.features.settings.ui.TranslationConfigContent
import dev.mirutime.app.features.settings.ui.isTranslationSaveEnabled
import dev.mirutime.uikit.MiruTextField

// === All book-page bottom sheets (one AppBottomSheet language) ===========

// Shelf move: single-select rows over the real library shelves, tap moves the
// book and closes. The caller loads them from LibraryDataProvider.
@Composable
fun ShelfMoveSheet(
    shelves: List<Shelf>,
    currentId: String,
    onSelect: (Shelf) -> Unit,
    onClose: () -> Unit,
) {
    val current = shelves.firstOrNull { it.id == currentId }
    AppBottomSheet(
        title = stringResource(R.string.book_move_shelf),
        description = stringResource(
            R.string.book_move_shelf_desc,
            current?.label ?: currentId,
        ),
        onClose = onClose,
    ) {
        if (shelves.isEmpty()) {
            Text(
                text = stringResource(R.string.book_move_shelf_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalMiruExtraColors.current.muted,
            )
        }
        shelves.forEach { shelf ->
            val selected = shelf.id == currentId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = { onSelect(shelf) })
                    .padding(vertical = MiruSpace.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (shelf.isSystemShelf()) {
                        stringResource(R.string.shelf_system) + shelf.label
                    } else {
                        shelf.label
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text(
                        text = "\u2713",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

// Per-novel translator: the same editor as Settings → Novels. It stores the
// novel's translation config; the reader picks it up on the next chapter
// request. Changes are saved only on Save.
@Composable
fun TranslatorSheet(
    bookUrl: String,
    bookTitle: String,
    onClose: () -> Unit,
) {
    val s = AppSettings
    var config by remember { mutableStateOf(s.novelTranslation[bookUrl] ?: TranslationConfig()) }
    AppBottomSheet(
        title = stringResource(R.string.tool_translator),
        description = stringResource(R.string.tool_translator_desc),
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

// EPUB export: one option (translated chapters) + the export action.
@Composable
fun EpubSheet(
    onClose: () -> Unit,
    onRun: (includeTranslation: Boolean) -> Unit,
) {
    var includeTranslation by rememberSaveable { mutableStateOf(true) }
    AppBottomSheet(
        title = stringResource(R.string.tool_epub),
        description = stringResource(R.string.tool_epub_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.tool_epub_export),
        onDone = { onRun(includeTranslation) },
    ) {
        MiruFilterChip(
            selected = includeTranslation,
            onClick = { includeTranslation = !includeTranslation },
            label = stringResource(R.string.tool_epub_include_translation),
        )
        if (includeTranslation) {
            Text(
                text = stringResource(R.string.tool_epub_translation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
            )
        }
    }
}

// The range sheet owns the same bulk actions as the long-press selection
// bar. It returns the chosen action; the book screen executes it.
enum class ChapterRangeAction {
    MarkRead,
    MarkUnread,
    Translate,
    Download,
    DeleteTranslations,
    DeleteDownloads,
}

// Select chapters: range from–to + bulk action, applied on Done. Range errors
// show inline the moment they matter.
@Composable
fun SelectChaptersSheet(
    book: BookDetails,
    onClose: () -> Unit,
    // Audio has nothing to download; the range sheet must not offer it.
    showDownload: Boolean = true,
    onApply: (IntRange, ChapterRangeAction) -> Unit,
) {
    val total = book.totalChapters
    var fromText by rememberSaveable { mutableStateOf("1") }
    var toText by rememberSaveable { mutableStateOf(total.toString()) }
    var action by rememberSaveable { mutableStateOf(ChapterRangeAction.MarkRead) }

    val from = fromText.toIntOrNull()
    val to = toText.toIntOrNull()
    val validRange = from != null && to != null &&
        from in 1..total && to >= from && to <= total

    AppBottomSheet(
        title = stringResource(R.string.book_select_title),
        description = stringResource(R.string.book_select_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.book_select_apply),
        doneEnabled = validRange,
        onDone = {
            if (from != null && to != null && validRange) {
                onApply(from..to, action)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiruTextField(
                value = fromText,
                onValueChange = { text -> fromText = text.filter { it.isDigit() } },
                label = stringResource(R.string.book_select_from),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            MiruTextField(
                value = toText,
                onValueChange = { text -> toText = text.filter { it.isDigit() } },
                label = stringResource(R.string.book_select_to),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        if (!validRange && (from != null || to != null)) {
            Text(
                text = stringResource(R.string.book_select_empty_range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = MiruSpace.S),
            )
        }
        Text(
            text = stringResource(R.string.book_select_action),
            style = MaterialTheme.typography.labelSmall,
            color = LocalMiruExtraColors.current.muted,
            modifier = Modifier.padding(top = MiruSpace.L, bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
        ) {
            RangeActionChip(
                action = ChapterRangeAction.Translate,
                selected = action,
                onSelect = { action = it },
                label = stringResource(R.string.book_sel_action_translate),
            )
            RangeActionChip(
                action = ChapterRangeAction.DeleteTranslations,
                selected = action,
                onSelect = { action = it },
                label = stringResource(R.string.book_sel_action_delete_translations),
            )
            if (showDownload) {
                RangeActionChip(
                    action = ChapterRangeAction.Download,
                    selected = action,
                    onSelect = { action = it },
                    label = stringResource(R.string.book_sel_action_download),
                )
                RangeActionChip(
                    action = ChapterRangeAction.DeleteDownloads,
                    selected = action,
                    onSelect = { action = it },
                    label = stringResource(R.string.book_sel_action_delete_downloads),
                )
            }
            RangeActionChip(
                action = ChapterRangeAction.MarkRead,
                selected = action,
                onSelect = { action = it },
                label = stringResource(R.string.book_select_read),
            )
            RangeActionChip(
                action = ChapterRangeAction.MarkUnread,
                selected = action,
                onSelect = { action = it },
                label = stringResource(R.string.book_select_unread),
            )
        }
    }
}

@Composable
private fun RangeActionChip(
    action: ChapterRangeAction,
    selected: ChapterRangeAction,
    onSelect: (ChapterRangeAction) -> Unit,
    label: String,
) {
    MiruFilterChip(
        selected = selected == action,
        onClick = { onSelect(action) },
        label = label,
    )
}

// Destructive confirm on the sheet language (the old AlertDialog sat flat
// against the bottom edge). Close reads "Cancel" because this is a decision,
// not a browsing sheet; the filled Done button goes error-red.
@Composable
fun StopDownloadsSheet(
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppBottomSheet(
        title = stringResource(R.string.book_dl_stop_title),
        description = stringResource(R.string.book_dl_stop_desc),
        onClose = onClose,
        closeLabel = stringResource(R.string.book_sel_cancel),
        doneLabel = stringResource(R.string.book_dl_stop_confirm),
        doneDanger = true,
        onDone = onConfirm,
    ) {
        // The description carries the destructive consequence.
    }
}

@Composable
fun DeleteTranslationsSheet(
    count: Int,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppBottomSheet(
        title = stringResource(R.string.book_sel_delete_translations_title),
        description = pluralStringResource(
            R.plurals.book_sel_delete_translations_desc_plural,
            count,
            count,
        ),
        onClose = onClose,
        closeLabel = stringResource(R.string.book_sel_cancel),
        doneLabel = stringResource(R.string.book_sel_delete_translations_confirm),
        doneDanger = true,
        onDone = onConfirm,
    ) {
        // No extra content: the description carries the whole warning.
    }
}
