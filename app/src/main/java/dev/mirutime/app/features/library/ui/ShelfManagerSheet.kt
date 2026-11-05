package dev.mirutime.app.features.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.features.library.data.LibraryShelfSummary
import dev.mirutime.app.features.library.domain.Shelf

// System shelves are pinned: no rename, no delete, no reorder.
fun Shelf.isSystemShelf(): Boolean = id == "reading" || id == "completed"

private enum class ShelfManagerMode { List, Nested, Thumbnail }

// Shelf manager overlay (08-library.md): long-press a tab or the manage icon
// opens this. Three display modes (List two-column + counter, Nested tree by
// "/" in the name, Thumbnail cover grid); Create/Rename/Delete/Reorder live
// inside. Dedup names, anti-blank, confirm on delete.
@Composable
fun ShelfManagerSheet(
    shelves: List<Shelf>,
    summaries: List<LibraryShelfSummary>,
    onClose: () -> Unit,
    onSelectShelf: (String) -> Unit,
    onCreateShelf: (name: String, skipUpdates: Boolean) -> Unit,
    onRenameShelf: (shelf: Shelf, name: String, skipUpdates: Boolean) -> Unit,
    onDeleteShelf: (Shelf) -> Unit,
    onMoveShelf: (Shelf, Int) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(ShelfManagerMode.List) }
    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Shelf?>(null) }
    var deleteTarget by remember { mutableStateOf<Shelf?>(null) }

    val counts = remember(summaries) { summaries.associate { it.shelfId to it.bookCount } }
    val coverFor = remember(summaries) { summaries.associateBy { it.shelfId } }

    AppBottomSheet(
        title = stringResource(R.string.shelf_title),
        description = stringResource(R.string.shelf_desc),
        onClose = onClose,
        doneLabel = stringResource(R.string.lib_done),
        onDone = onClose,
    ) {
        SheetSection(title = stringResource(R.string.shelf_section_view)) {
            ModeSwitcher(mode = mode, onMode = { mode = it })
            Spacer(modifier = Modifier.height(12.dp))
            when (mode) {
                ShelfManagerMode.List -> ShelfListView(
                    shelves = shelves,
                    counts = counts,
                    onPick = onSelectShelf,
                )
                ShelfManagerMode.Nested -> ShelfNestedView(
                    shelves = shelves,
                    counts = counts,
                    onPick = onSelectShelf,
                )
                ShelfManagerMode.Thumbnail -> ShelfThumbnailView(
                    shelves = shelves,
                    counts = counts,
                    coverFor = coverFor,
                    onPick = onSelectShelf,
                )
            }
        }
        SheetSection(title = stringResource(R.string.shelf_section_manage)) {
            TextButton(onClick = { createOpen = true }) {
                Text(text = stringResource(R.string.shelf_new_btn))
            }
            shelves.forEach { shelf ->
                ManageShelfRow(
                    shelf = shelf,
                    bookCount = counts[shelf.id] ?: 0,
                    canMoveUp = shelves.canMove(shelf, -1),
                    canMoveDown = shelves.canMove(shelf, 1),
                    onMove = { dir -> onMoveShelf(shelf, dir) },
                    onRename = { renameTarget = shelf },
                    onDelete = { deleteTarget = shelf },
                )
            }
        }
    }

    if (createOpen) {
        ShelfEditSheet(
            title = stringResource(R.string.shelf_new_title),
            description = stringResource(R.string.shelf_new_desc),
            confirmLabel = stringResource(R.string.shelf_create),
            initial = "",
            initialSkipUpdates = false,
            shelves = shelves,
            exceptId = null,
            onClose = { createOpen = false },
            onConfirm = { name, skip ->
                onCreateShelf(name, skip)
                createOpen = false
            },
        )
    }
    renameTarget?.let { target ->
        ShelfEditSheet(
            title = stringResource(R.string.shelf_edit_title),
            description = stringResource(R.string.shelf_edit_desc),
            confirmLabel = stringResource(R.string.shelf_save),
            initial = target.label,
            initialSkipUpdates = target.excludeFromUpdates,
            shelves = shelves,
            exceptId = target.id,
            onClose = { renameTarget = null },
            onConfirm = { name, skip ->
                onRenameShelf(target, name, skip)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { target ->
        DeleteShelfSheet(
            shelf = target,
            bookCount = counts[target.id] ?: 0,
            onClose = { deleteTarget = null },
            onConfirm = {
                onDeleteShelf(target)
                deleteTarget = null
            },
        )
    }
}

// Segmented mode switch in Paper tokens (M3 SegmentedButton paints the stock
// purple here, so the switch is drawn by hand: one bordered rail, the active
// segment on the accent-soft fill).
@Composable
private fun ModeSwitcher(
    mode: ShelfManagerMode,
    onMode: (ShelfManagerMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiruRadius.Large))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(MiruRadius.Large),
            ),
    ) {
        ShelfManagerMode.entries.forEach { entry ->
            val selected = mode == entry
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable { onMode(entry) }
                    .padding(vertical = MiruSpace.M),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when (entry) {
                            ShelfManagerMode.List -> R.string.shelf_mode_list
                            ShelfManagerMode.Nested -> R.string.shelf_mode_nested
                            ShelfManagerMode.Thumbnail -> R.string.shelf_mode_covers
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalMiruExtraColors.current.muted
                    },
                )
            }
        }
    }
}
@Composable
private fun ShelfListView(
    shelves: List<Shelf>,
    counts: Map<String, Int>,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shelves.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { shelf ->
                    Surface(
                        shape = RoundedCornerShape(MiruRadius.Chip),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(shelf.id) },
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = MiruSpace.M)) {
                            Text(
                                text = shelf.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = pluralStringResource(
                                    R.plurals.plural_books,
                                    counts[shelf.id] ?: 0,
                                    counts[shelf.id] ?: 0,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalMiruExtraColors.current.muted,
                            )
                        }
                    }
                }
                repeat(2 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// Collapsible tree by "/" in the name. Tap a leaf opens the shelf.
@Composable
private fun ShelfNestedView(
    shelves: List<Shelf>,
    counts: Map<String, Int>,
    onPick: (String) -> Unit,
) {
    val grouped = remember(shelves) {
        shelves.filter { "/" in it.label }.groupBy { it.label.substringBefore("/") }
    }
    val flat = remember(shelves) { shelves.filter { "/" !in it.label } }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        flat.forEach { shelf ->
            NestedLeaf(
                label = shelf.label,
                count = counts[shelf.id] ?: 0,
                indented = false,
                onPick = { onPick(shelf.id) },
            )
        }
        grouped.forEach { (group, children) ->
            val open = group in expanded
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = if (open) expanded - group else expanded + group
                    }
                    .padding(vertical = MiruSpace.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = LocalMiruExtraColors.current.muted,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = group,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = children.size.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                )
            }
            if (open) {
                children.forEach { shelf ->
                    NestedLeaf(
                        label = shelf.label.substringAfter("/"),
                        count = counts[shelf.id] ?: 0,
                        indented = true,
                        onPick = { onPick(shelf.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NestedLeaf(
    label: String,
    count: Int,
    indented: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(
                start = if (indented) 32.dp else 8.dp,
                top = 10.dp,
                bottom = 10.dp,
                end = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pluralStringResource(R.plurals.plural_books, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
        )
    }
}

// Cover grid: first book's cover per shelf. Tap opens the shelf.
@Composable
private fun ShelfThumbnailView(
    shelves: List<Shelf>,
    counts: Map<String, Int>,
    coverFor: Map<String, LibraryShelfSummary>,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shelves.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { shelf ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPick(shelf.id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BookCover(
                            title = coverFor[shelf.id]?.coverTitle ?: shelf.label,
                            coverUrl = coverFor[shelf.id]?.coverUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1 / 1.45f),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = shelf.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${counts[shelf.id] ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalMiruExtraColors.current.muted,
                        )
                    }
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ManageShelfRow(
    shelf: Shelf,
    bookCount: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shelf.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    if (shelf.isSystemShelf()) append(stringResource(R.string.shelf_system))
                    append(
                        pluralStringResource(
                            R.plurals.plural_books,
                            bookCount,
                            bookCount,
                        ),
                    )
                    if (shelf.excludeFromUpdates) append(stringResource(R.string.shelf_updates_off))
                },
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
            )
        }
        if (!shelf.isSystemShelf()) {
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.shelf_cd_up),
                )
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.shelf_cd_down),
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.shelf_cd_edit),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.shelf_cd_delete),
                )
            }
        }
    }
}

// Reorder runs inside the non-system tail; system shelves stay pinned on top.
private fun List<Shelf>.canMove(shelf: Shelf, dir: Int): Boolean {
    if (shelf.isSystemShelf()) return false
    val tail = filterNot { it.isSystemShelf() }
    val index = tail.indexOf(shelf)
    if (index < 0) return false
    val target = index + dir
    return target in tail.indices
}
