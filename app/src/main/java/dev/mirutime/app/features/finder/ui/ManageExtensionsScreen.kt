@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.mirutime.app.features.finder.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.ToggleOff
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.R as UiKitR
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruRoundIconButton
import dev.mirutime.uikit.MiruSearchBar
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.app.di.InstallReport
import dev.mirutime.app.design.components.PluginAvatar
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.data.importPluginFile
import dev.mirutime.app.features.finder.data.installFailureDetails
import dev.mirutime.app.features.finder.domain.ExtensionFilters
import dev.mirutime.app.features.finder.domain.PluginExtension
import dev.mirutime.app.features.finder.domain.PluginFileInstall
import dev.mirutime.app.features.finder.domain.PluginFileRejection
import dev.mirutime.app.features.finder.domain.applyFilters
import dev.mirutime.app.features.finder.domain.supportsPluginTranslation
import dev.mirutime.app.features.settings.ui.PluginTranslationSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.mirutime.uikit.MiruTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions

// Manage extensions, a separate
// screen opened from Finder. Top to bottom:
//   REPOSITORY card — URL field, Reset to default, Refresh repository,
//                     "+ Add extension" (a plugin file, stub)
//   SOURCE MANAGEMENT section card — description, "N installed · M updates"
//     (+ Update all when two or more plugins wait for an update)
//   search field + filters bar (inline query; the sheet edits type, language
//     and status)
//   two section cards: Installed (N) and Repository (M). Search/filters apply
//     to both; an installed plugin lives only in Installed, updates stay on
//     its row. Toggle icon + three dots per installed row (sheet: check
//     update, plugin settings, edit plugin, translator, uninstall).
// Long-press an installed row to multi-select and uninstall in bulk.

// Repo card (0), management card (1), sticky search (2); results follow.
private const val ExtensionSearchItemIndex = 2

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ManageExtensionsScreen(
    contentPadding: PaddingValues,
    provider: FinderDataProvider,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var plugins by remember { mutableStateOf<List<PluginExtension>?>(null) }
    var busyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var repoBusy by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(ExtensionFilters()) }
    var filterOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<PluginExtension?>(null) }
    var settingsFor by remember { mutableStateOf<PluginExtension?>(null) }
    var translationFor by remember { mutableStateOf<PluginExtension?>(null) }
    var pendingUninstall by remember { mutableStateOf<PluginExtension?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBulk by remember { mutableStateOf(false) }
    var repoText by remember { mutableStateOf(provider.repoUrl()) }
    var installBusy by remember { mutableStateOf(false) }
    var fileResult by remember { mutableStateOf<PluginFileInstall?>(null) }
    var healthFor by remember { mutableStateOf<PluginExtension?>(null) }
    var sourceFor by remember { mutableStateOf<PluginExtension?>(null) }

    // Cached local/disk rows only; used for the first paint and after every
    // mutation (install/update/toggle/uninstall), so no action waits on a
    // repository download.
    suspend fun reload() {
        plugins = provider.extensions()
    }
    // SAF picker: only the user-granted stream is read; nothing else on disk.
    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            installBusy = true
            scope.launch {
                try {
                    fileResult = importPluginFile(context, uri, provider)
                } finally {
                    installBusy = false
                }
                if (fileResult is PluginFileInstall.Installed) reload()
            }
        }
    }

    val all = plugins.orEmpty()
    val installed = remember(all) { all.filter { it.installed } }
    val updates = remember(installed) { installed.count { it.updateAvailable } }
    val languages = remember(all) { all.map { it.language }.distinct().sorted() }
    val installedShown = remember(all, filters) {
        all.filter { it.installed }.applyFilters(filters, installedTab = true)
    }
    val repositoryShown = remember(all, filters) {
        all.filter { !it.installed }.applyFilters(filters, installedTab = false)
    }
    // Status only describes the installed list; a status-only filter must not
    // turn the repository section into a false "no match".
    val repositoryFiltered = filters.query.isNotBlank() ||
        filters.types.isNotEmpty() ||
        filters.languages.isNotEmpty()
    val selectionMode = selected.isNotEmpty()

    fun toast(message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    // A pass that failed anywhere names the source and the reason; a failed
    // write is never hidden behind a silent list reload.
    fun showFailure(report: InstallReport) {
        val details = installFailureDetails(
            report = report,
            nameById = all.associate { it.id to it.name },
            repositoryLabel = resources.getString(R.string.finder_repo_title),
        ) ?: return
        toast(resources.getString(R.string.finder_action_failed_fmt, details))
    }
    fun runFor(id: String, action: suspend () -> InstallReport) {
        busyIds = busyIds + id
        scope.launch {
            try {
                showFailure(action())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(resources.getString(R.string.finder_action_failed_fmt, e.message ?: e.toString()))
            } finally {
                try {
                    reload()
                } finally {
                    busyIds = busyIds - id
                }
            }
        }
    }
    fun runUnitFor(id: String, action: suspend () -> Unit) {
        busyIds = busyIds + id
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(resources.getString(R.string.finder_action_failed_fmt, e.message ?: e.toString()))
            } finally {
                try {
                    reload()
                } finally {
                    busyIds = busyIds - id
                }
            }
        }
    }

    // Two phases: paint what is already local/on disk, then refresh the
    // repository in the background while the old rows stay visible.
    LaunchedEffect(provider) {
        reload()
        repoBusy = true
        try {
            val refreshed = provider.refreshExtensions()
            plugins = refreshed.extensions
            showFailure(InstallReport(emptyList(), refreshed.failed))
        } finally {
            repoBusy = false
        }
    }

    val listState = rememberLazyListState()
    // Typing must never leave the matches under the keyboard: the search bar
    // is pinned and every query change brings the results back under it.
    LaunchedEffect(filters.query) {
        if (filters.query.isNotBlank()) {
            listState.animateScrollToItem(ExtensionSearchItemIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MiruSize.Gap),
        contentPadding = PaddingValues(
            start = MiruSize.ScreenPad,
            end = MiruSize.ScreenPad,
            top = contentPadding.calculateTopPadding() + MiruSpace.Xs,
            bottom = contentPadding.calculateBottomPadding() + MiruSpace.L,
        ),
    ) {
        item(key = "repo") {
            MiruSectionCard(
                title = stringResource(R.string.finder_repo_title),
                description = stringResource(R.string.finder_repo_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column(modifier = Modifier.padding(MiruSpace.L)) {
                    MiruTextField(
                        value = repoText,
                        onValueChange = { repoText = it },
                        // Long repository URLs wrap instead of hiding the tail.
                        singleLine = false,
                        minLines = 1,
                        maxLines = 2,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        plate = false,
                    )
                    Spacer(modifier = Modifier.height(MiruSpace.M))
                    Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                        OutlinedButton(
                            onClick = { repoText = provider.defaultRepoUrl() },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = MiruSize.Button),
                        ) {
                            Text(
                                text = stringResource(R.string.finder_repo_reset),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            onClick = {
                                repoBusy = true
                                scope.launch {
                                    try {
                                        showFailure(provider.refreshRepo(repoText))
                                        repoText = provider.repoUrl()
                                        reload()
                                    } finally {
                                        repoBusy = false
                                    }
                                }
                            },
                            enabled = !repoBusy,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = MiruSize.Button),
                        ) {
                            if (repoBusy) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.finder_repo_refresh),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                MiruRowDivider()
                MiruActionRow(
                    icon = Icons.Filled.Add,
                    title = stringResource(R.string.finder_add_extension),
                    subtitle = stringResource(R.string.finder_add_extension_sub),
                    enabled = !installBusy,
                    trailing = if (installBusy) {
                        {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                                color = LocalMiruExtraColors.current.accent,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = { installLauncher.launch(arrayOf("*/*")) },
                )
            }
        }
        item(key = "management") {
            // One plate for the section header and the installed/updates
            // banner: no bare eyebrow on the photo, no duplicate "Extensions".
            MiruSectionCard(
                title = stringResource(R.string.finder_source_management),
                description = stringResource(R.string.finder_extensions_desc),
                contentPadding = PaddingValues(0.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(
                        horizontal = MiruSpace.L,
                        vertical = MiruSpace.M,
                    ),
                ) {
                    Text(
                        text = if (updates > 0) {
                            stringResource(R.string.finder_info_updates_fmt, installed.size, updates)
                        } else {
                            stringResource(R.string.finder_info_fmt, installed.size)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    // Update all appears once several plugins are waiting.
                    if (updates >= 2) {
                        Button(
                            onClick = {
                                val ids = installed.filter { it.updateAvailable }.map { it.id }.toSet()
                                busyIds = busyIds + ids
                                scope.launch {
                                    try {
                                        showFailure(provider.updateAll())
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        toast(
                                            resources.getString(
                                                R.string.finder_action_failed_fmt,
                                                e.message ?: e.toString(),
                                            ),
                                        )
                                    } finally {
                                        try {
                                            reload()
                                        } finally {
                                            busyIds = busyIds - ids
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.heightIn(min = MiruSize.Control),
                            contentPadding = PaddingValues(horizontal = MiruSpace.M),
                        ) {
                            Text(
                                text = stringResource(R.string.finder_update_all),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        stickyHeader(key = "filter-bar") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                modifier = Modifier.padding(vertical = MiruSpace.Xs),
            ) {
                MiruSearchBar(
                    value = filters.query,
                    onValueChange = { filters = filters.copy(query = it) },
                    placeholder = stringResource(R.string.finder_ext_query_hint),
                    modifier = Modifier.weight(1f),
                )
                MiruRoundIconButton(
                    icon = Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.finder_cd_filters),
                    onClick = { filterOpen = true },
                    badgeCount = filters.activeFilterCount,
                )
            }
        }
        if (selectionMode) {
            item(key = "selection") {
                MiruCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = MiruSpace.Xs,
                        end = MiruSpace.M,
                        top = MiruSpace.Xs,
                        bottom = MiruSpace.Xs,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { selected = emptySet() },
                            modifier = Modifier.size(MiruSize.Touch),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.finder_cd_cancel_selection),
                            )
                        }
                        Text(
                            text = stringResource(R.string.finder_selected_fmt, selected.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { selected = installedShown.map { it.id }.toSet() }) {
                            Text(
                                text = stringResource(R.string.finder_select_all),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                        TextButton(
                            onClick = { confirmBulk = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.finder_uninstall),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        // A real spinner only while there is nothing to show; once any row is
        // known, the background repository refresh gets a quiet inline badge.
        if (plugins == null || (all.isEmpty() && repoBusy)) {
            item(key = "loading") {
                MiruCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(MiruSpace.Xl),
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalMiruExtraColors.current.accent)
                    }
                }
            }
        } else {
            if (repoBusy) {
                item(key = "repo-refreshing") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MiruSpace.L),
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = LocalMiruExtraColors.current.accent,
                        )
                        Spacer(modifier = Modifier.size(MiruSpace.S))
                        Text(
                            text = stringResource(R.string.finder_repo_refreshing),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalMiruExtraColors.current.muted,
                        )
                    }
                }
            }
            item(key = "installed") {
                MiruSectionCard(
                    title = stringResource(
                        R.string.finder_tab_installed_fmt,
                        installedShown.size,
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    if (installedShown.isEmpty()) {
                        SectionMessage(
                            stringResource(
                                if (filters != ExtensionFilters()) {
                                    R.string.finder_ext_no_match
                                } else {
                                    R.string.finder_installed_empty
                                },
                            ),
                        )
                    } else {
                        installedShown.forEachIndexed { index, plugin ->
                            if (index > 0) MiruRowDivider()
                            key(plugin.id) {
                                InstalledRow(
                                    plugin = plugin,
                                    busy = plugin.id in busyIds,
                                    selectionMode = selectionMode,
                                    selected = plugin.id in selected,
                                    onClick = {
                                        when {
                                            selectionMode -> selected =
                                                if (plugin.id in selected) selected - plugin.id else selected + plugin.id
                                            plugin.error != null -> healthFor = plugin
                                            else -> actionsFor = plugin
                                        }
                                    },
                                    onLongClick = { selected = selected + plugin.id },
                                    onToggle = { enabled ->
                                        scope.launch {
                                            provider.setEnabled(plugin.id, enabled)
                                            reload()
                                        }
                                    },
                                    onMore = { actionsFor = plugin },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "repository") {
                MiruSectionCard(
                    title = stringResource(
                        R.string.finder_tab_catalogue_fmt,
                        repositoryShown.size,
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    if (repositoryShown.isEmpty()) {
                        SectionMessage(
                            stringResource(
                                if (repositoryFiltered) {
                                    R.string.finder_ext_no_match
                                } else {
                                    R.string.finder_available_empty
                                },
                            ),
                        )
                    } else {
                        repositoryShown.forEachIndexed { index, plugin ->
                            if (index > 0) MiruRowDivider()
                            key(plugin.id) {
                                CatalogueRow(
                                    plugin = plugin,
                                    busy = plugin.id in busyIds,
                                    onInstall = { runFor(plugin.id) { provider.install(plugin.id) } },
                                    onUpdate = { runFor(plugin.id) { provider.update(plugin.id) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (filterOpen) {
        ExtensionFilterSheet(
            applied = filters,
            languages = languages,
            showStatus = true,
            onApply = { filters = it },
            onClose = { filterOpen = false },
        )
    }
    if (settingsFor == null && translationFor == null) {
        actionsFor?.let { plugin ->
            ExtensionActionsSheet(
                plugin = plugin,
                onCheckUpdate = {
                    actionsFor = null
                    if (plugin.updateAvailable) {
                        runFor(plugin.id) { provider.update(plugin.id) }
                    } else {
                        toast(resources.getString(R.string.finder_up_to_date_fmt, plugin.name))
                    }
                },
                onSettings = {
                    // Keep actionsFor: closing settings returns to the actions
                    // sheet while only one AppBottomSheet is mounted at a time.
                    settingsFor = plugin
                },
                onEdit = {
                    actionsFor = null
                    sourceFor = plugin
                },
                onTranslate = { translationFor = plugin },
                onUninstall = {
                    actionsFor = null
                    pendingUninstall = plugin
                },
                onClose = { actionsFor = null },
            )
        }
    }
    settingsFor?.let { plugin ->
        PluginSettingsSheet(
            name = plugin.name,
            description = stringResource(
                R.string.finder_source_meta_fmt,
                plugin.language,
                typeLabel(plugin.type),
                plugin.installedVersion.orEmpty(),
            ),
            baseUrl = provider.baseUrl(plugin.id),
            settings = provider.settingsSchema(plugin.id),
            onSave = { url -> scope.launch { provider.setBaseUrl(plugin.id, url) } },
            onClose = { settingsFor = null },
        )
    }
    translationFor?.let { plugin ->
        PluginTranslationSheet(
            pluginId = plugin.id,
            name = plugin.name,
            onClose = { translationFor = null },
        )
    }
    fileResult?.let { result ->
        val installed = result as? PluginFileInstall.Installed
        val rejected = result as? PluginFileInstall.Rejected
        AppBottomSheet(
            title = stringResource(R.string.finder_install_file_title),
            description = if (installed != null) {
                installed.name
            } else {
                stringResource(R.string.finder_install_file_desc)
            },
            onClose = { fileResult = null },
            closeLabel = stringResource(UiKitR.string.lib_close),
        ) {
            Text(
                text = if (installed != null) {
                    stringResource(R.string.finder_install_file_done_fmt, installed.name)
                } else {
                    installRejectionText(rejected?.reason, rejected?.detail.orEmpty())
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (installed != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
    healthFor?.let { plugin ->
        PluginHealthSheet(
            plugin = plugin,
            provider = provider,
            onClose = { healthFor = null },
            onChanged = { scope.launch { reload() } },
        )
    }
    sourceFor?.let { plugin ->
        PluginSourceSheet(
            pluginId = plugin.id,
            name = plugin.name,
            provider = provider,
            onClose = { sourceFor = null },
        )
    }
    pendingUninstall?.let { plugin ->
        // Destructive actions confirm first, in the same sheet standard as the
        // bulk flow: names the plugin, says what survives, red Done.
        AppBottomSheet(
            title = stringResource(R.string.finder_uninstall_title_fmt, plugin.name),
            description = stringResource(R.string.finder_uninstall_desc),
            onClose = { pendingUninstall = null },
            doneLabel = stringResource(R.string.finder_uninstall),
            doneDanger = true,
            closeLabel = stringResource(R.string.lib_cancel),
            onDone = {
                pendingUninstall = null
                runUnitFor(plugin.id) { provider.uninstall(plugin.id) }
            },
        ) {
            // No extra content: title + description carry the whole warning.
        }
    }
    if (confirmBulk) {
        val names = all.filter { it.id in selected }.joinToString(", ") { it.name }
        AppBottomSheet(
            title = stringResource(R.string.finder_bulk_title_fmt, selected.size),
            description = stringResource(R.string.finder_bulk_desc),
            onClose = { confirmBulk = false },
            doneLabel = stringResource(R.string.finder_uninstall),
            doneDanger = true,
            closeLabel = stringResource(R.string.lib_cancel),
            onDone = {
                val ids = selected
                confirmBulk = false
                selected = emptySet()
                busyIds = busyIds + ids
                scope.launch {
                    provider.uninstallMany(ids)
                    reload()
                    busyIds = busyIds - ids
                }
            },
        ) {
            Text(
                text = names,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// One avatar for every extension row: the shared PluginAvatar already falls
// back to the monogram when the plugin ships no icon.
@Composable
private fun ExtensionIcon(plugin: PluginExtension) {
    PluginAvatar(name = plugin.name, iconUrl = plugin.iconUrl)
}

// Installed row: icon, name, "EN · v1.4 · Novels" / "v1.4 → v1.5" / Disabled,
// switch, three dots. Selection mode swaps the switch for a checkbox.
@Composable
private fun InstalledRow(
    plugin: PluginExtension,
    busy: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMore: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(start = MiruSpace.L, end = MiruSpace.Xs, top = MiruSpace.S, bottom = MiruSpace.S),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        ExtensionIcon(plugin)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plugin.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (plugin.enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.finder_ext_row_fmt,
                    plugin.language,
                    plugin.installedVersion.orEmpty(),
                    typeLabel(plugin.type),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                plugin.error != null -> Text(
                    text = stringResource(R.string.finder_ext_status_error),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
                plugin.updateAvailable -> Text(
                    text = stringResource(
                        R.string.finder_update_available_fmt,
                        plugin.availableVersion,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.accent,
                    maxLines = 1,
                )
                !plugin.enabled -> Text(
                    text = stringResource(R.string.finder_ext_status_disabled),
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.muted,
                    maxLines = 1,
                )
            }
        }
        when {
            busy -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = extra.accent,
                modifier = Modifier
                    .padding(end = MiruSpace.M)
                    .size(22.dp),
            )
            selectionMode -> Checkbox(checked = selected, onCheckedChange = { onClick() })
            else -> {
                // A broken plugin has no working toggle: its row opens the
                // health sheet, and the error badge already says why.
                if (plugin.error == null) {
                    // 48dp icon toggle: accent when on, muted when off; the
                    // content description names the current state.
                    IconButton(
                        onClick = { onToggle(!plugin.enabled) },
                        modifier = Modifier.size(MiruSize.Touch),
                    ) {
                        Icon(
                            imageVector = if (plugin.enabled) {
                                Icons.Rounded.ToggleOn
                            } else {
                                Icons.Rounded.ToggleOff
                            },
                            contentDescription = stringResource(
                                if (plugin.enabled) {
                                    R.string.finder_ext_status_enabled
                                } else {
                                    R.string.finder_ext_status_disabled
                                },
                            ),
                            tint = if (plugin.enabled) extra.accent else extra.muted,
                        )
                    }
                }
                IconButton(onClick = onMore, modifier = Modifier.size(MiruSize.Touch)) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.finder_cd_more),
                        tint = extra.muted,
                    )
                }
            }
        }
    }
}

// Repository-catalogue row: Install / Update / Installed.
@Composable
private fun CatalogueRow(
    plugin: PluginExtension,
    busy: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(start = MiruSpace.L, end = MiruSpace.S, top = MiruSpace.S, bottom = MiruSpace.S),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        ExtensionIcon(plugin)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = plugin.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.finder_ext_row_fmt,
                    plugin.language,
                    plugin.availableVersion,
                    typeLabel(plugin.type),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            busy -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = extra.accent,
                modifier = Modifier
                    .padding(end = MiruSpace.M)
                    .size(22.dp),
            )
            plugin.updateAvailable -> TextButton(onClick = onUpdate) {
                Text(
                    text = stringResource(R.string.finder_update),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
            plugin.installed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = MiruSpace.M),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = extra.muted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.finder_installed_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.muted,
                    modifier = Modifier.padding(start = MiruSpace.Xs),
                    maxLines = 1,
                )
            }
            else -> TextButton(onClick = onInstall) {
                Text(
                    text = stringResource(R.string.finder_install),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

// Three-dots sheet of an installed plugin.
@Composable
private fun ExtensionActionsSheet(
    plugin: PluginExtension,
    onCheckUpdate: () -> Unit,
    onSettings: () -> Unit,
    onEdit: () -> Unit,
    onTranslate: () -> Unit,
    onUninstall: () -> Unit,
    onClose: () -> Unit,
) {
    AppBottomSheet(
        title = plugin.name,
        description = stringResource(
            R.string.finder_ext_row_fmt,
            plugin.language,
            plugin.installedVersion.orEmpty(),
            typeLabel(plugin.type),
        ),
        onClose = onClose,
    ) {
        MiruActionRow(
            icon = Icons.Filled.Refresh,
            title = stringResource(R.string.finder_action_check_update),
            subtitle = if (plugin.updateAvailable) {
                stringResource(R.string.finder_update_available_fmt, plugin.availableVersion)
            } else {
                stringResource(R.string.finder_action_check_update_sub)
            },
            onClick = onCheckUpdate,
        )
        MiruRowDivider()
        // Always available: the sheet owns the app-owned base URL even when
        // the plugin declares no settings of its own.
        MiruActionRow(
            icon = Icons.Filled.Settings,
            title = stringResource(R.string.finder_action_settings),
            subtitle = stringResource(R.string.finder_action_settings_sub),
            onClick = onSettings,
        )
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.Code,
            title = stringResource(R.string.finder_action_edit),
            subtitle = stringResource(R.string.finder_action_edit_sub),
            onClick = onEdit,
        )
        if (supportsPluginTranslation(plugin.type)) {
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Rounded.Translate,
                title = stringResource(R.string.finder_plugin_translation),
                subtitle = stringResource(R.string.st_pt_enable),
                onClick = onTranslate,
            )
        }
        MiruRowDivider()
        MiruActionRow(
            icon = Icons.Filled.Delete,
            title = stringResource(R.string.finder_plugin_uninstall),
            subtitle = stringResource(R.string.finder_action_uninstall_sub),
            onClick = onUninstall,
        )
    }
}

// Precise, localized reason for a rejected local install. Technical detail is
// appended for the cases where the exact line/id matters (syntax/id/write).
@Composable
private fun installRejectionText(reason: PluginFileRejection?, detail: String): String {
    val base = when (reason) {
        PluginFileRejection.SYNTAX -> stringResource(R.string.finder_install_err_syntax)
        PluginFileRejection.INVALID_ID -> stringResource(R.string.finder_install_err_id)
        PluginFileRejection.DUPLICATE -> stringResource(R.string.finder_install_err_duplicate)
        PluginFileRejection.TOO_LARGE -> stringResource(R.string.finder_install_err_large)
        PluginFileRejection.INVALID_UTF8 -> stringResource(R.string.finder_install_err_utf8)
        PluginFileRejection.EMPTY -> stringResource(R.string.finder_install_err_empty)
        PluginFileRejection.NOT_LUA -> stringResource(R.string.finder_install_err_not_lua)
        PluginFileRejection.UNREADABLE -> stringResource(R.string.finder_install_err_read)
        PluginFileRejection.WRITE -> stringResource(R.string.finder_install_err_write)
        null -> stringResource(R.string.finder_install_file_desc)
    }
    val showDetail = detail.isNotBlank() && reason in setOf(
        PluginFileRejection.SYNTAX,
        PluginFileRejection.INVALID_ID,
        PluginFileRejection.WRITE,
        PluginFileRejection.DUPLICATE,
    )
    return if (showDetail) "$base\n$detail" else base
}

// Short message inside a section card (loading / empty / filtered empty).
@Composable
private fun SectionMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalMiruExtraColors.current.muted,
        modifier = Modifier.padding(MiruSpace.L),
    )
}
