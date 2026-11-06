package dev.mirutime.app.features.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.uikit.MiruTextField

// Name entry for create + edit, built on the bottom-sheet standard: title +
// description, roomy field, the skip-updates switch, and the sticky footer
// (Close outlined + filled Create/Save, raised above gesture nav, 70% cap).
// Anti-blank, case-insensitive dedup (own name excluded when editing). The
// guard is inline text, not just a disabled button: a muted hint while
// pristine, a real error once typed-and-cleared or on a duplicate.
@Composable
fun ShelfEditSheet(
    title: String,
    description: String,
    confirmLabel: String,
    initial: String,
    initialSkipUpdates: Boolean,
    shelves: List<Shelf>,
    exceptId: String?,
    onClose: () -> Unit,
    onConfirm: (name: String, skipUpdates: Boolean) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    var skipUpdates by remember(initialSkipUpdates) { mutableStateOf(initialSkipUpdates) }
    var dirty by remember(initial) { mutableStateOf(false) }
    val trimmed = name.trim()
    val duplicate = trimmed.isNotEmpty() &&
        shelves.any { it.id != exceptId && it.label.equals(trimmed, ignoreCase = true) }
    val unchanged = exceptId != null && trimmed ==
        shelves.firstOrNull { it.id == exceptId }?.label
    val blank = trimmed.isEmpty()
    val showBlankError = blank && (dirty || initial.isNotEmpty())

    AppBottomSheet(
        title = title,
        description = description,
        onClose = onClose,
        doneLabel = confirmLabel,
        doneEnabled = !blank && !duplicate && !unchanged,
        onDone = { onConfirm(trimmed, skipUpdates) },
    ) {
        MiruTextField(
            value = name,
            onValueChange = {
                name = it
                if (it.isNotEmpty()) dirty = true
            },
            placeholder = stringResource(R.string.shelf_name_hint),
            singleLine = true,
            isError = duplicate || showBlankError,
            supportingText = when {
                duplicate -> stringResource(R.string.shelf_dup_error)
                showBlankError -> stringResource(R.string.shelf_blank_error)
                else -> stringResource(R.string.shelf_blank_hint)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        SkipUpdatesRow(
            checked = skipUpdates,
            onChecked = { skipUpdates = it },
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

// Delete flow on the same bottom-sheet standard (AlertDialog sat flat
// against the bottom edge): title + exactly what happens (books move to
// Reading), footer Close outlined + red filled Delete, 70% cap and padding
// above gesture nav inherited from the sheet.
@Composable
fun DeleteShelfSheet(
    shelf: Shelf,
    bookCount: Int,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppBottomSheet(
        title = stringResource(R.string.shelf_delete_title),
        description = if (bookCount > 0) {
            stringResource(
                R.string.shelf_delete_nonempty_fmt,
                shelf.label,
                pluralStringResource(R.plurals.plural_books, bookCount, bookCount),
            )
        } else {
            stringResource(R.string.shelf_delete_empty_fmt, shelf.label)
        },
        onClose = onClose,
        doneLabel = stringResource(R.string.shelf_delete),
        doneDanger = true,
        onDone = onConfirm,
    ) {
        // No extra content: the description carries the whole warning, so the
        // sheet stays a fixed, glanceable size at 360dp.
    }
}

// Single-boolean row: label + why on the left, a Switch on the right.
// The real update worker reads Shelf.excludeFromUpdates once the data layer
// owns categories; until then the toggle only rides the in-memory shelf.
@Composable
fun SkipUpdatesRow(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.shelf_skip_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.shelf_skip_desc),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
        )
    }
}
