package dev.mirutime.app.features.finder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.CoverInset
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruMembershipBadge
import dev.mirutime.uikit.MiruMembershipBookmark
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.app.design.components.toPreset
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.uikit.MiruBookCard
import dev.mirutime.uikit.MiruCardType
import dev.mirutime.uikit.MiruContentKind
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.data.LOCAL_CATALOG_SCHEME
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.LocalCatalogFolder
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.library.ui.importFailureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Local catalogue taps always open directly. `local-file://` reads the SAF
// document in place; "Import all" is the only action that copies files.
internal enum class LocalCatalogTap { Open }

internal fun localCatalogTap(book: CatalogBook): LocalCatalogTap? = when {
    book.bookUrl.startsWith("$LOCAL_CATALOG_SCHEME://", ignoreCase = true) -> LocalCatalogTap.Open
    book.bookUrl.startsWith("local://", ignoreCase = true) -> LocalCatalogTap.Open
    else -> null
}

// Honest format filter values for a local source: only the formats its scan
// actually produces. Plugin genres are never offered for a local catalogue.
internal fun localCatalogFormats(sourceId: String): List<String> =
    if (LocalSources.kindOf(sourceId) == "manga") {
        listOf("CBZ", "IMAGES")
    } else {
        listOf("EPUB", "FB2", "TXT")
    }

// One scanned file as a catalogue tile. Imported entries use their real local
// cover and chapter count; unimported entries keep the neutral placeholder and
// say so quietly (plus the format badge on the cover).
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocalCatalogCard(
    book: CatalogBook,
    importing: Boolean,
    error: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    inLibrary: Boolean = false,
    kind: MiruContentKind = MiruContentKind.Novel,
) {
    val extra = LocalMiruExtraColors.current
    val format = book.localFormat.orEmpty()
    val importingLabel = stringResource(R.string.finder_local_importing)
    val membership = if (inLibrary) {
        MiruMembershipBadge(sameSource = true, sourceCount = 1)
    } else {
        null
    }
    MiruBookCard(
        preset = AppSettings.cardStyle.toPreset(),
        title = book.title,
        source = localCardMeta(book, importing),
        rating = null,
        kind = kind,
        membership = membership,
        onClick = onClick,
        onLongClick = onLongClick,
        membershipDescription = stringResource(R.string.lib_membership_same),
        contentTypeDescription = "",
        updateDescription = "",
        selectionDescription = "",
        modifier = Modifier.fillMaxWidth(),
        footer = if (error != null) {
            {
                Text(
                    text = error,
                    style = MiruCardType.Meta,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        } else {
            null
        },
        cover = {
            Box(modifier = Modifier.fillMaxSize()) {
                BookCover(
                    title = book.title,
                    coverUrl = book.coverUrl,
                    badge = format.ifBlank { null },
                    badgeAlignment = Alignment.TopEnd,
                    corner = MiruRadius.Cover,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(CoverInset),
                )
                if (importing) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(CoverInset)
                            .clip(RoundedCornerShape(MiruRadius.Cover))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = extra.accent,
                            modifier = Modifier
                                .size(28.dp)
                                .semantics { contentDescription = importingLabel },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun localCardMeta(book: CatalogBook, importing: Boolean): String = when {
    importing -> stringResource(R.string.finder_local_importing)
    book.notImported -> stringResource(R.string.finder_local_not_imported)
    book.chapters <= 0 -> book.localFormat.orEmpty()
    book.localFormat.equals("CBZ", ignoreCase = true) ->
        stringResource(R.string.finder_local_pages_short_fmt, book.chapters)
    else -> stringResource(R.string.finder_chapters_short_fmt, book.chapters)
}

// Empty local catalogue: no scan results and no filters. Points at the folder
// sheet instead of the plugin-style "Nothing found".
@Composable
internal fun LocalCatalogEmptyState(onManageFolders: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    MiruCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.finder_local_catalog_empty),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.finder_local_catalog_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = extra.muted,
            modifier = Modifier.padding(top = MiruSpace.Xs),
        )
        Spacer(modifier = Modifier.height(MiruSpace.M))
        Button(
            onClick = onManageFolders,
            modifier = Modifier.height(MiruSize.Button),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
            ),
        ) {
            Text(text = stringResource(R.string.finder_local_manage))
        }
    }
}

// Folder management for one local source. Historically a separate screen;
// it now lives in this sheet so the live catalogue stays one tap away.
@Composable
internal fun LocalFoldersSheet(
    kind: String,
    provider: FinderDataProvider,
    onClose: () -> Unit,
    onCatalogChanged: () -> Unit,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val extra = LocalMiruExtraColors.current
    var folders by remember(kind) { mutableStateOf<List<LocalCatalogFolder>?>(null) }
    var loadError by remember(kind) { mutableStateOf<String?>(null) }
    var busyUri by remember(kind) { mutableStateOf<String?>(null) }
    var removingUri by remember(kind) { mutableStateOf<String?>(null) }
    var addingFolder by remember(kind) { mutableStateOf(false) }
    var messages by remember(kind) { mutableStateOf<Map<String, FolderMessage>>(emptyMap()) }
    val formats = stringResource(
        if (kind == "manga") R.string.finder_local_folder_manga else R.string.finder_local_folder_books,
    )
    val metaFormat = if (kind == "manga") {
        R.string.finder_local_folder_meta_manga_fmt
    } else {
        R.string.finder_local_folder_meta_books_fmt
    }

    suspend fun reload() {
        try {
            folders = provider.localCatalogFolders(kind)
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: e.toString()
        }
    }

    fun importFolder(folder: LocalCatalogFolder) {
        if (folder.fileCount == 0 || busyUri != null || removingUri != null) return
        busyUri = folder.uri
        messages = messages - folder.uri
        scope.launch {
            try {
                val summary = provider.importLocalCatalogFolder(kind, folder.uri)
                val reason = summary.firstFailureReason
                val message = when {
                    reason != null -> importFailureText(resources, reason)
                    summary.failures > 0 -> resources.getString(
                        R.string.finder_local_import_failed_count_fmt,
                        summary.failures,
                    )
                    summary.accepted > 0 -> resources.getString(
                        R.string.finder_local_import_done_fmt,
                        summary.accepted,
                    )
                    else -> resources.getString(R.string.finder_local_folder_empty_fmt, formats)
                }
                messages = messages + (
                    folder.uri to FolderMessage(
                        text = message,
                        isError = summary.failures > 0 || summary.accepted == 0,
                    )
                )
                onCatalogChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages = messages + (
                    folder.uri to FolderMessage(
                        text = e.message ?: resources.getString(R.string.finder_local_import_failed),
                        isError = true,
                    )
                )
            } finally {
                busyUri = null
            }
        }
    }

    fun removeFolder(folder: LocalCatalogFolder) {
        if (busyUri != null || removingUri != null) return
        removingUri = folder.uri
        messages = messages - folder.uri
        scope.launch {
            try {
                provider.removeLocalCatalogFolder(kind, folder.uri)
                reload()
                onCatalogChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: resources.getString(R.string.finder_local_folder_error)
            } finally {
                removingUri = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                addingFolder = true
                try {
                    provider.addLocalCatalogFolder(kind, uri.toString())
                    reload()
                    onCatalogChanged()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loadError = e.message ?: resources.getString(R.string.finder_local_folder_error)
                } finally {
                    addingFolder = false
                }
            }
        }
    }

    LaunchedEffect(kind) { reload() }

    AppBottomSheet(
        title = stringResource(R.string.finder_local_folders),
        description = stringResource(R.string.finder_local_folders_sheet_desc),
        onClose = onClose,
    ) {
        if (kind == "manga") {
            Text(
                text = stringResource(R.string.finder_local_manga_hint),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                modifier = Modifier.padding(bottom = MiruSpace.S),
            )
        }
        val list = folders
        when {
            loadError != null -> Text(
                text = loadError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = MiruSpace.M),
            )
            list == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MiruSpace.L),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = extra.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            list.isEmpty() -> Text(
                text = stringResource(R.string.finder_local_no_folders),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.padding(vertical = MiruSpace.M),
            )
            else -> list.forEachIndexed { index, folder ->
                FolderRow(
                    folder = folder,
                    formats = formats,
                    metaFormat = metaFormat,
                    busy = busyUri == folder.uri,
                    removing = removingUri == folder.uri,
                    actionsEnabled = busyUri == null && removingUri == null,
                    message = messages[folder.uri],
                    onImport = { importFolder(folder) },
                    onRemove = { removeFolder(folder) },
                )
                if (index != list.lastIndex) MiruRowDivider()
            }
        }
        Spacer(modifier = Modifier.height(MiruSpace.S))
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.FolderOpen,
            title = stringResource(R.string.finder_local_add_folder),
            subtitle = if (addingFolder) {
                stringResource(R.string.loading_adding_folder)
            } else {
                formats
            },
            onClick = { if (!addingFolder) picker.launch(null) },
            enabled = folders != null && busyUri == null && removingUri == null && !addingFolder,
            trailing = if (addingFolder) {
                {
                    val addingLabel = stringResource(R.string.loading_adding_folder)
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = extra.accent,
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = addingLabel },
                    )
                }
            } else {
                null
            },
        )
    }
}

private data class FolderMessage(val text: String, val isError: Boolean)

@Composable
private fun FolderRow(
    folder: LocalCatalogFolder,
    formats: String,
    metaFormat: Int,
    busy: Boolean,
    removing: Boolean,
    actionsEnabled: Boolean,
    message: FolderMessage?,
    onImport: () -> Unit,
    onRemove: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val importingLabel = stringResource(R.string.finder_local_importing)
    Column(modifier = Modifier.fillMaxWidth()) {
        MiruActionRow(
            icon = Icons.Filled.Folder,
            title = folder.name,
            subtitle = if (folder.fileCount == 0) {
                stringResource(R.string.finder_local_folder_empty_fmt, formats)
            } else {
                stringResource(metaFormat, folder.fileCount)
            },
            onClick = onImport,
            enabled = folder.fileCount > 0 && actionsEnabled,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onImport,
                        enabled = folder.fileCount > 0 && actionsEnabled,
                        colors = ButtonDefaults.textButtonColors(contentColor = extra.accent),
                        contentPadding = PaddingValues(horizontal = MiruSpace.S),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = extra.accent,
                                modifier = Modifier
                                    .size(16.dp)
                                    .semantics { contentDescription = importingLabel },
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.finder_local_import_all),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                    IconButton(
                        onClick = onRemove,
                        enabled = actionsEnabled,
                        modifier = Modifier.size(MiruSize.Touch),
                    ) {
                        if (removing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = extra.muted,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.finder_local_folder_remove_cd),
                                tint = extra.muted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            },
        )
        message?.let { row ->
            Text(
                text = row.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.isError) MaterialTheme.colorScheme.error else extra.muted,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(
                    start = MiruSpace.L,
                    end = MiruSpace.L,
                    bottom = MiruSpace.S,
                ),
            )
        }
    }
}
