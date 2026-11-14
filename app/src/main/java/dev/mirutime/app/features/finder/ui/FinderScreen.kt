@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.mirutime.app.features.finder.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSearchDecoration
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.domain.BrowseFilters
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.finder.domain.OpdsSources
import dev.mirutime.app.features.finder.domain.applyBrowse
import dev.mirutime.app.features.finder.domain.supportsPluginTranslation
import dev.mirutime.app.features.finder.domain.withPin
import dev.mirutime.app.features.library.data.ImportSummary
import dev.mirutime.app.features.library.data.PickedImportFile
import dev.mirutime.app.features.library.ui.ImportSheet
import dev.mirutime.app.features.library.ui.importFailureText
import dev.mirutime.app.features.opds.domain.OpdsController
import dev.mirutime.app.features.opds.ui.OpdsAddCatalogSheet
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.ui.PluginTranslationSheet
import dev.mirutime.core.log.MiruLog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

// The floating tab panel overlays the scroll: content ends above it.
private val BottomFloatClearance = 48.dp

// Finder. Order, top to bottom:
//   global-search entry (the screen header already names the tab, so there is
//   no second eyebrow line on the photo)
//   two big buttons: Add by URL · Import local books
//   BROWSE card: "Sources & catalogues" + Manage extensions (separate
//     screen), four filter buttons (type, language, sort, pins — each opens
//     a bottom sheet), then the sources: icon left, name, "language · N in
//     library · type", translation shortcut on the right. Pinned always
//     first; a pinned LOCAL source (EPUB/FB2, CBZ) stays above every other
//     pinned source.
@Composable
fun FinderScreen(
    contentPadding: PaddingValues,
    provider: FinderDataProvider,
    // Adding a catalog is the only OPDS surface: saved catalogs are ordinary
    // sources in the list below, so there is no separate OPDS screen.
    opdsController: OpdsController,
    onSourceOpen: (String) -> Unit = {},
    onLocalOpen: (kind: String) -> Unit = {},
    onSearchOpen: () -> Unit = {},
    onManageExtensions: () -> Unit = {},
    onLibraryOpen: () -> Unit = {},
    // Bumped by the shell after a library change/restore: the source rows
    // (local and plugin counts) are a one-shot snapshot, so reload them.
    refreshKey: Int = 0,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<CatalogSource>?>(null) }
    var filters by remember { mutableStateOf(BrowseFilters()) }
    var sheet by remember { mutableStateOf<BrowseSheetKind?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    var linkOpen by remember { mutableStateOf(false) }
    var opdsAddOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CatalogSource?>(null) }
    var translationFor by remember { mutableStateOf<CatalogSource?>(null) }
    var pinningSourceId by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        sources = provider.sources()
    }
    LaunchedEffect(provider, refreshKey) { reload() }

    val all = sources
    val visible = remember(all, filters) { all.orEmpty().applyBrowse(filters) }
    val languages = remember(all) {
        all.orEmpty().filter { !it.local }.map { it.language }.distinct().sorted()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MiruSize.Gap),
        contentPadding = PaddingValues(
            start = MiruSize.ScreenPad,
            end = MiruSize.ScreenPad,
            top = contentPadding.calculateTopPadding() + MiruSpace.Xs,
            bottom = contentPadding.calculateBottomPadding() + BottomFloatClearance,
        ),
    ) {
        item(key = "global-search") {
            GlobalSearchEntry(onClick = onSearchOpen)
        }
        item(key = "big-buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
                BigActionButton(
                    icon = Icons.Filled.Link,
                    label = stringResource(R.string.finder_big_add_url),
                    onClick = { linkOpen = true },
                    modifier = Modifier.weight(1f),
                )
                BigActionButton(
                    icon = Icons.Filled.UploadFile,
                    label = stringResource(R.string.finder_big_import),
                    onClick = { importOpen = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item(key = "opds") {
            BigActionButton(
                icon = Icons.Filled.Language,
                label = stringResource(R.string.opds_entry),
                onClick = { opdsAddOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "browse") {
            MiruSectionCard(
                title = stringResource(R.string.finder_sources_catalogues),
                description = stringResource(R.string.finder_sources_desc),
                trailing = {
                    TextButton(onClick = onManageExtensions) {
                        Icon(
                            imageVector = Icons.Filled.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.finder_manage_extensions),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.padding(start = MiruSpace.Xs),
                        )
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                // Four filter buttons, each a bottom sheet. Highlighted when
                // the filter is not at its default.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                    contentPadding = PaddingValues(
                        horizontal = MiruSpace.L,
                        vertical = MiruSpace.S,
                    ),
                ) {
                    item {
                        MiruFilterChip(
                            selected = filters.types.isNotEmpty(),
                            onClick = { sheet = BrowseSheetKind.Type },
                            label = stringResource(R.string.finder_btn_type),
                            leadingIcon = Icons.Filled.Category,
                        )
                    }
                    item {
                        MiruFilterChip(
                            selected = filters.languages.isNotEmpty(),
                            onClick = { sheet = BrowseSheetKind.Language },
                            label = stringResource(R.string.finder_btn_language),
                            leadingIcon = Icons.Filled.Language,
                        )
                    }
                    item {
                        MiruFilterChip(
                            selected = filters.sortChanged,
                            onClick = { sheet = BrowseSheetKind.Sort },
                            label = stringResource(R.string.finder_btn_sort),
                            leadingIcon = Icons.AutoMirrored.Filled.Sort,
                        )
                    }
                    item {
                        MiruFilterChip(
                            selected = false,
                            onClick = { sheet = BrowseSheetKind.Pins },
                            label = stringResource(R.string.finder_btn_pins),
                            leadingIcon = Icons.Filled.PushPin,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                when {
                    all == null -> Column(
                        modifier = Modifier.padding(MiruSpace.L),
                        verticalArrangement = Arrangement.spacedBy(MiruSpace.M),
                    ) {
                        repeat(3) { SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 40.dp) }
                    }
                    visible.isEmpty() -> Text(
                        text = stringResource(R.string.finder_sources_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalMiruExtraColors.current.muted,
                        modifier = Modifier.padding(MiruSpace.L),
                    )
                    else -> visible.forEachIndexed { index, source ->
                        if (index > 0) MiruRowDivider()
                        SourceRow(
                            source = source,
                            onOpen = {
                                if (source.local) {
                                    onLocalOpen(LocalSources.kindOf(source.id))
                                } else {
                                    onSourceOpen(source.id)
                                }
                            },
                            // An OPDS row is a saved catalog: long-press forgets it.
                            onLongClick = if (OpdsSources.catalogIdOf(source.id) != null) {
                                { pendingDelete = source }
                            } else {
                                null
                            },
                            onTranslation = { translationFor = source },
                        )
                    }
                }
            }
        }
    }

    sheet?.let { kind ->
        BrowseFilterSheet(
            kind = kind,
            filters = filters,
            sources = all.orEmpty(),
            languages = languages,
            onApply = { filters = it },
            onTogglePin = { id ->
                val current = sources?.firstOrNull { it.id == id }
                if (current != null && pinningSourceId == null) {
                    val desired = !current.pinned
                    sources = sources?.withPin(id, desired)
                    pinningSourceId = id
                    scope.launch {
                        val written = try {
                            provider.togglePin(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            false
                        }
                        if (!written) {
                            sources = sources?.withPin(id, !desired)
                            Toast.makeText(
                                context,
                                resources.getString(R.string.finder_pin_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        pinningSourceId = null
                    }
                }
            },
            onClose = { sheet = null },
            pinningId = pinningSourceId,
        )
    }
    translationFor?.let { source ->
        PluginTranslationSheet(
            pluginId = source.id,
            name = source.name,
            onClose = { translationFor = null },
        )
    }
    if (opdsAddOpen) {
        OpdsAddCatalogSheet(
            controller = opdsController,
            onClose = { opdsAddOpen = false },
            // The catalog is now a source row: show it where the user landed.
            onAdded = {
                opdsAddOpen = false
                scope.launch { reload() }
            },
        )
    }
    pendingDelete?.let { source ->
        AppBottomSheet(
            title = stringResource(R.string.opds_delete_title),
            description = stringResource(R.string.opds_delete_desc_fmt, source.name),
            onClose = { pendingDelete = null },
            closeLabel = stringResource(R.string.st_cancel),
            doneLabel = stringResource(R.string.opds_delete_confirm),
            doneDanger = true,
            onDone = {
                pendingDelete = null
                scope.launch {
                    try {
                        provider.removeOpdsCatalog(source.id)
                        reload()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MiruLog.w(
                            "opds",
                            "catalog remove failed source=${MiruLog.idHash(source.id)} " +
                                "error=${e.javaClass.simpleName}",
                        )
                    }
                }
            },
        ) {
            // No body: the title names the catalog and the description says
            // what happens to it; a body line would just repeat the name.
        }
    }
    if (linkOpen) {
        AddByLinkSheet(
            provider = provider,
            onClose = { linkOpen = false },
            // Added books live in the library: that is where the user lands.
            onAdded = {
                linkOpen = false
                onLibraryOpen()
            },
        )
    }
    if (importOpen) {
        ImportSheet(
            onClose = { importOpen = false },
            onImport = { file ->
                importOpen = false
                scope.launch {
                    val summary = importAll(provider, listOf(file))
                    reload()
                    toastImported(context, resources, summary)
                }
            },
            onImportUris = { files ->
                importOpen = false
                scope.launch {
                    val summary = importAll(provider, files)
                    reload()
                    toastImported(context, resources, summary)
                }
            },
        )
    }
}

private suspend fun importAll(
    provider: FinderDataProvider,
    files: List<PickedImportFile>,
) = provider.importLocal(files)

internal fun toastImported(
    context: android.content.Context,
    resources: android.content.res.Resources,
    summary: ImportSummary,
) {
    val reason = summary.firstFailureReason
    val count = summary.accepted
    val message = when {
        reason != null -> importFailureText(resources, reason)
        count > 0 -> resources.getQuantityString(R.plurals.plural_local_books, count, count)
        else -> resources.getString(R.string.toast_import_failed)
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

// Source row: icon, name (+ small pin glyph), "EN · 3 in library · Novels"
// (local: "N files · EPUB, FB2"), translation shortcut on the right (plugins only).
// An OPDS row also reacts to a long press (forget the saved catalog).
@Composable
private fun SourceRow(
    source: CatalogSource,
    onOpen: () -> Unit,
    onTranslation: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val extra = LocalMiruExtraColors.current
    val translating = AppSettings.pluginTranslation[source.id]?.enabled == true
    val canTranslate = !source.local &&
        OpdsSources.catalogIdOf(source.id) == null &&
        supportsPluginTranslation(source.type)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onLongClick)
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(
                start = MiruSpace.L,
                end = if (canTranslate) MiruSpace.Xs else MiruSpace.L,
                top = MiruSpace.S,
                bottom = MiruSpace.S,
            ),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        SourceIconTile(source)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sourceDisplayName(source),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (source.pinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.finder_unpin_cd),
                        tint = extra.accent,
                        modifier = Modifier
                            .padding(start = MiruSpace.Xs)
                            .size(14.dp),
                    )
                }
            }
            Text(
                text = if (source.local) {
                    stringResource(
                        if (source.id == LocalSources.Manga) {
                            R.string.finder_local_meta_manga_fmt
                        } else {
                            R.string.finder_local_meta_books_fmt
                        },
                        source.libraryCount,
                    )
                } else {
                    stringResource(
                        R.string.finder_source_row_fmt,
                        source.language,
                        source.libraryCount,
                        typeLabel(source.type),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canTranslate) {
            IconButton(onClick = onTranslation, modifier = Modifier.size(MiruSize.Touch)) {
                Icon(
                    imageVector = Icons.Rounded.Translate,
                    contentDescription = stringResource(R.string.finder_cd_plugin_translation),
                    tint = if (translating) extra.accent else extra.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// Big action button: icon + up to two lines of label on a card plate.
@Composable
private fun BigActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = LocalMiruExtraColors.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        modifier = modifier.defaultMinSize(minHeight = MiruSize.ButtonPrimary),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = MiruSpace.M, vertical = MiruSpace.S),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = extra.accent,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// Entry to the global search screen: looks like a field, is a button.
@Composable
private fun GlobalSearchEntry(onClick: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    Box(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        MiruSearchDecoration(
            innerTextField = {},
            placeholder = {
                Text(
                    text = stringResource(R.string.finder_gs_entry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                    maxLines = 1,
                )
            },
            showPlaceholder = true,
        )
    }
}
