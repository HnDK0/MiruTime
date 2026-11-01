package dev.mirutime.app.features.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.SheetSection
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.app.features.library.domain.Shelf

// Top action bar shown while the library is in selection mode. The first row
// is close/count/select-all/clear; the second carries the four bulk actions.
// Busy replaces the close button with a spinner and disables every control, so
// a double submit cannot start a second transaction.
@Composable
fun LibrarySelectionBar(
    selectedCount: Int,
    visibleCount: Int,
    busy: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onMove: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allSelected = visibleCount > 0 && selectedCount >= visibleCount
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = MiruSpace.S, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.lib_selection_close),
                        )
                    }
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.plural_selected_books,
                        selectedCount,
                        selectedCount,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onSelectAll,
                    enabled = !busy && !allSelected && visibleCount > 0,
                ) {
                    Text(text = stringResource(R.string.lib_select_all))
                }
                TextButton(
                    onClick = onClear,
                    enabled = !busy && selectedCount > 0,
                ) {
                    Text(text = stringResource(R.string.lib_clear_selection))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BulkAction(
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    description = stringResource(R.string.lib_bulk_move),
                    enabled = !busy && selectedCount > 0,
                    onClick = onMove,
                )
                BulkAction(
                    icon = Icons.Filled.CheckCircle,
                    description = stringResource(R.string.lib_bulk_mark_read),
                    enabled = !busy && selectedCount > 0,
                    onClick = onMarkRead,
                )
                BulkAction(
                    icon = Icons.Filled.RemoveCircleOutline,
                    description = stringResource(R.string.lib_bulk_mark_unread),
                    enabled = !busy && selectedCount > 0,
                    onClick = onMarkUnread,
                )
                BulkAction(
                    icon = Icons.Filled.Delete,
                    description = stringResource(R.string.lib_bulk_delete),
                    enabled = !busy && selectedCount > 0,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun BulkAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                LocalMiruExtraColors.current.muted
            },
        )
    }
}

// Delete confirmation: the destructive semantics are explicit (app data and
// downloaded files go, the source document stays).
@Composable
fun BulkDeleteSheet(
    selectedCount: Int,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppBottomSheet(
        title = stringResource(R.string.lib_bulk_delete_title),
        description = pluralStringResource(
            R.plurals.plural_bulk_delete_desc,
            selectedCount,
            selectedCount,
        ),
        onClose = onClose,
        closeLabel = stringResource(R.string.lib_cancel),
        doneLabel = stringResource(R.string.lib_bulk_delete),
        doneDanger = true,
        onDone = onConfirm,
    ) {
        // The description carries the whole warning; keep the sheet glanceable.
    }
}

// Shelf picker for a bulk move. Every shelf stays available, including the
// system Reading/Completed rows; nothing here can delete a shelf.
@Composable
fun BulkMoveShelfSheet(
    shelves: List<Shelf>,
    selectedCount: Int,
    onClose: () -> Unit,
    onPick: (String) -> Unit,
) {
    AppBottomSheet(
        title = stringResource(R.string.lib_move_title),
        description = pluralStringResource(
            R.plurals.plural_bulk_move_desc,
            selectedCount,
            selectedCount,
        ),
        onClose = onClose,
        closeLabel = stringResource(R.string.lib_cancel),
    ) {
        SheetSection(title = stringResource(R.string.lib_move_title)) {
            shelves.forEach { shelf ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(shelf.id) }
                        .padding(vertical = MiruSpace.M),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = shelf.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (shelf.isSystemShelf()) {
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.lib_move_system),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalMiruExtraColors.current.muted,
                        )
                    }
                }
            }
        }
    }
}
