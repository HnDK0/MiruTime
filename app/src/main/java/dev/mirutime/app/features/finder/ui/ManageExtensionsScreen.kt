@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.mirutime.app.features.finder.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.ChipRail
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.R as UiKitR
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruCompactButton
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruIconButton
import dev.mirutime.uikit.MiruIconButtonStyle
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruRoundIconButton
import dev.mirutime.uikit.MiruSearchBar
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.uikit.MiruSelectionPlate
import dev.mirutime.uikit.compactTouchTarget
import dev.mirutime.uikit.isLargeText
import dev.mirutime.uikit.miruCardBorder
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.app.di.InstallReport
import dev.mirutime.app.design.RangeSelection
import dev.mirutime.app.design.components.PluginAvatar
import dev.mirutime.app.design.logRangeSelect
import dev.mirutime.app.design.rememberPersistedState
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.data.importPluginFile
import dev.mirutime.app.features.finder.data.installFailureDetails
import dev.mirutime.app.features.finder.domain.ExtensionFilters
import dev.mirutime.app.features.finder.domain.ExtensionStatus
import dev.mirutime.app.features.finder.domain.PluginExtension
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.finder.domain.PluginFileInstall
import dev.mirutime.app.features.finder.domain.PluginFileRejection
import dev.mirutime.app.features.finder.domain.PluginSetting
import dev.mirutime.app.features.finder.domain.applyFilters
import dev.mirutime.app.features.finder.domain.supportsPluginTranslation
import dev.mirutime.app.features.settings.ui.PluginTranslationSheet
import dev.mirutime.core.api.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dev.mirutime.uikit.MiruTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions

// Manage extensions, a separate screen opened from Finder.
// Pinned search/filter/refresh bar above Bento Repository / Add extension tiles,
// then Installed (N) (with Update all in header + quick status rail under it,
// matching Finder) and Repository (M). Long-press an installed row to
// multi-select and uninstall in bulk.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ManageExtensionsScreen(
    contentPadding: PaddingValues,
    provider: FinderDataProvider,
    // Both a base-URL change and a restart invalidate the plugin's loaded handle inside the provider;
    // cached catalogue lists still hold pages built against the old handle/host, so the owner of the
    // session cache drops them.
    onSourceReset: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var plugins by remember { mutableStateOf<List<PluginExtension>?>(null) }
    var busyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var repoBusy by remember { mutableStateOf(false) }
    var filters by rememberPersistedState<ExtensionFilters>(
        key = "extensions.filters",
        default = ExtensionFilters(),
        encode = { f ->
            listOf(
                f.types.joinToString(",") { it.name },
                f.languages.joinToString(","),
                f.status.name,
            ).joinToString(";")
        },
        decode = { raw ->
            val parts = raw.split(";")
            if (parts.size < 3) return@rememberPersistedState null
            val types = if (parts[0].isEmpty()) emptySet() else parts[0].split(",").mapNotNull { name ->
                runCatching { ContentType.valueOf(name) }.getOrNull()
            }.toSet()
            val langs = if (parts[1].isEmpty()) emptySet() else parts[1].split(",").toSet()
            val status = runCatching { ExtensionStatus.valueOf(parts[2]) }.getOrDefault(ExtensionStatus.Any)
            ExtensionFilters(query = "", types = types, languages = langs, status = status)
        },
    )
    var filterOpen by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<PluginExtension?>(null) }
    var settingsFor by remember { mutableStateOf<PluginExtension?>(null) }
    var translationFor by remember { mutableStateOf<PluginExtension?>(null) }
    var pendingUninstall by remember { mutableStateOf<PluginExtension?>(null) }
    var selection by remember { mutableStateOf(RangeSelection<String>()) }
    val selected = selection.selected
    val selectionMode = selection.active
    var confirmBulk by remember { mutableStateOf(false) }
    var repoText by remember { mutableStateOf(provider.repoUrl()) }
    var repoSheetOpen by remember { mutableStateOf(false) }
    var installBusy by remember { mutableStateOf(false) }
    var fileResult by remember { mutableStateOf<PluginFileInstall?>(null) }
    var healthFor by remember { mutableStateOf<PluginExtension?>(null) }
    var sourceFor by remember { mutableStateOf<PluginExtension?>(null) }
    var updateAllBusy by remember { mutableStateOf(false) }

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
    LaunchedEffect(plugins, installed) {
        if (plugins != null) {
            selection = selection.prune(
                validKeys = installed.map { it.id }.toSet(),
                autoDeactivateWhenEmpty = true,
            )
        }
    }

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

    // Repository check: downloads the index, keeps the current rows visible
    // while it runs, and reports per-source failures. Returns false when a
    // source failed, so a "check for updates" cannot claim it is up to date.
    suspend fun refreshRepository(): Boolean {
        // A second tap must not start a parallel index download.
        if (repoBusy) return false
        repoBusy = true
        try {
            val refreshed = provider.refreshExtensions()
            plugins = refreshed.extensions
            showFailure(InstallReport(emptyList(), refreshed.failed))
            return refreshed.failed.isEmpty()
        } finally {
            repoBusy = false
        }
    }

    // Two phases: paint what is already local/on disk, then refresh the
    // repository in the background while the old rows stay visible.
    LaunchedEffect(provider) {
        reload()
        refreshRepository()
    }

    val listState = rememberLazyListState()
    val largeText = isLargeText()
    val extra = LocalMiruExtraColors.current

    fun runUpdateAll() {
        if (updateAllBusy) return
        val ids = installed.filter { it.updateAvailable }.map { it.id }.toSet()
        updateAllBusy = true
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
                    updateAllBusy = false
                }
            }
        }
    }

    val selectionBarBottom = contentPadding.calculateBottomPadding() + MiruSpace.S
    // Measured, never a constant: the plate grows with the font scale, and the
    // list must still scroll its last row clear of it.
    var selectionBarHeightPx by remember { mutableStateOf(0) }
    val selectionBarInset = if (selectionMode) {
        selectionBarBottom + with(LocalDensity.current) { selectionBarHeightPx.toDp() } + MiruSpace.S
    } else {
        contentPadding.calculateBottomPadding() + MiruSpace.L
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = MiruSize.ScreenPad,
                        end = MiruSize.ScreenPad,
                        top = MiruSpace.Xs,
                        bottom = MiruSpace.Xxs,
                    ),
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
                    badgeCount = filters.types.size + filters.languages.size,
                )
                MiruRoundIconButton(
                    icon = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.finder_repo_refresh),
                    enabled = !repoBusy,
                    busy = repoBusy,
                    busyDescription = stringResource(R.string.finder_repo_refreshing),
                    onClick = { scope.launch { refreshRepository() } },
                )
            }
            val compactActions = largeText || LocalDensity.current.fontScale > 1.15f
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MiruSize.Gap),
                contentPadding = PaddingValues(
                    start = MiruSize.ScreenPad,
                    end = MiruSize.ScreenPad,
                    top = MiruSpace.Xs,
                    bottom = selectionBarInset,
                ),
            ) {
                item(key = "quick-actions") {
                    val repoLabel = stringResource(R.string.finder_repo_title)
                    val addLabel = stringResource(R.string.finder_add_extension)
                    val onOpenRepo: () -> Unit = {
                        repoText = provider.repoUrl()
                        repoSheetOpen = true
                    }
                    val onAddExtension: () -> Unit = { installLauncher.launch(arrayOf("*/*")) }
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val stackTiles = compactActions || maxWidth < MiruSize.NarrowContent
                        if (stackTiles) {
                            Column(verticalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                                ExtensionActionTile(
                                    icon = Icons.Filled.Code,
                                    label = repoLabel,
                                    onClick = onOpenRepo,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ExtensionActionTile(
                                    icon = Icons.Filled.Add,
                                    label = addLabel,
                                    enabled = !installBusy,
                                    busy = installBusy,
                                    onClick = onAddExtension,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Max),
                            ) {
                                ExtensionActionTile(
                                    icon = Icons.Filled.Code,
                                    label = repoLabel,
                                    onClick = onOpenRepo,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                                ExtensionActionTile(
                                    icon = Icons.Filled.Add,
                                    label = addLabel,
                                    enabled = !installBusy,
                                    busy = installBusy,
                                    onClick = onAddExtension,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
                // A real spinner only while there is nothing to show; once any row is
                // known, the background repository refresh stays inside the overview card.
                if (plugins == null || (all.isEmpty() && repoBusy)) {
                    item(key = "loading") {
                        MiruCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(MiruSpace.Xl),
                        ) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = extra.accent)
                            }
                        }
                    }
                } else {
                    item(key = "installed") {
                        MiruSectionCard(
                            title = stringResource(
                                R.string.finder_tab_installed_fmt,
                                installedShown.size,
                            ),
                            trailing = if (updates >= 1) {
                                {
                                    val updateAllLabel = stringResource(R.string.finder_update_all)
                                    val fullUpdateAllLabel = "$updateAllLabel ($updates)"
                                    if (compactActions) {
                                        MiruIconButton(
                                            icon = Icons.Filled.Refresh,
                                            contentDescription = fullUpdateAllLabel,
                                            selected = true,
                                            onClick = ::runUpdateAll,
                                            enabled = !updateAllBusy,
                                            busy = updateAllBusy,
                                            busyDescription = updateAllLabel,
                                            style = MiruIconButtonStyle.Plate,
                                        )
                                    } else {
                                        Box(modifier = Modifier.width(IntrinsicSize.Max)) {
                                            MiruCompactButton(
                                                icon = Icons.Filled.Refresh,
                                                label = fullUpdateAllLabel,
                                                selected = true,
                                                enabled = !updateAllBusy,
                                                busy = updateAllBusy,
                                                onClick = ::runUpdateAll,
                                            )
                                        }
                                    }
                                }
                            } else {
                                null
                            },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            if (installed.isNotEmpty() || filters.status != ExtensionStatus.Any) {
                                ChipRail(
                                    contentPadding = PaddingValues(
                                        horizontal = MiruSpace.L,
                                        vertical = MiruSpace.S,
                                    ),
                                ) {
                                    items(ExtensionStatus.entries, key = { it.name }) { status ->
                                        MiruFilterChip(
                                            selected = filters.status == status,
                                            onClick = { filters = filters.copy(status = status) },
                                            label = stringResource(
                                                when (status) {
                                                    ExtensionStatus.Any -> R.string.finder_filter_all
                                                    ExtensionStatus.Enabled -> R.string.finder_ext_status_enabled
                                                    ExtensionStatus.Disabled -> R.string.finder_ext_status_disabled
                                                    ExtensionStatus.Updates -> R.string.finder_ext_status_updates
                                                },
                                            ),
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
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
                                                    selectionMode -> selection = selection.toggle(
                                                        plugin.id,
                                                        autoDeactivateWhenEmpty = true,
                                                    )
                                                    plugin.error != null -> healthFor = plugin
                                                    else -> actionsFor = plugin
                                                }
                                            },
                                            onLongClick = {
                                                val next = selection.longPress(
                                                    plugin.id,
                                                    installedShown.map { it.id },
                                                )
                                                logRangeSelect("extensions", selection.count, next.count)
                                                selection = next
                                            },
                                            onToggle = { enabled ->
                                                scope.launch {
                                                    provider.setEnabled(plugin.id, enabled)
                                                    reload()
                                                }
                                            },
                                            onUpdate = { runFor(plugin.id) { provider.update(plugin.id) } },
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
        }
    if (selectionMode) {
        // Bottom float, same plate as the library: pinned to the list it
        // scrolled away and Uninstall needed a scroll-up.
        MiruSelectionPlate(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = MiruSize.ScreenPad,
                    end = MiruSize.ScreenPad,
                    bottom = selectionBarBottom,
                )
                .onSizeChanged { selectionBarHeightPx = it.height },
            header = {
                MiruIconButton(
                    icon = Icons.Filled.Close,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    iconSize = MiruSize.IconCompact,
                    onClick = { selection = selection.close() },
                    contentDescription = stringResource(R.string.finder_cd_cancel_selection),
                )
                Text(
                    text = stringResource(R.string.finder_selected_fmt, selected.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            },
            actions = {
                // Header keeps only the count: at 200% the buttons next to it
                // pushed the number into the ellipsis.
                MiruCompactButton(
                    icon = Icons.Filled.Check,
                    label = stringResource(R.string.finder_select_all),
                    enabled = selected.size < installedShown.size,
                    onClick = {
                        selection = selection.selectAll(
                            installedShown.map { it.id },
                            additive = true,
                        )
                    },
                )
                MiruCompactButton(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.finder_uninstall),
                    ink = MaterialTheme.colorScheme.error,
                    onClick = { confirmBulk = true },
                )
            },
        )
    }
    }

    val hasOtherSheetOpen = actionsFor != null ||
        settingsFor != null ||
        translationFor != null ||
        fileResult != null ||
        healthFor != null ||
        sourceFor != null ||
        pendingUninstall != null ||
        confirmBulk
    if (repoSheetOpen && !filterOpen && !hasOtherSheetOpen) {
        AppBottomSheet(
            title = stringResource(R.string.finder_repo_title),
            description = stringResource(R.string.finder_repo_desc),
            onClose = { repoSheetOpen = false },
            doneLabel = stringResource(R.string.finder_repo_refresh),
            doneBusy = repoBusy,
            doneEnabled = !repoBusy,
            onDone = {
                scope.launch {
                    repoBusy = true
                    try {
                        val report = provider.refreshRepo(repoText)
                        showFailure(report)
                        reload()
                        if (report.failed.isEmpty()) {
                            repoText = provider.repoUrl()
                            repoSheetOpen = false
                        }
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
                        repoBusy = false
                    }
                }
            },
            headerAction = {
                MiruButton(
                    onClick = { repoText = provider.defaultRepoUrl() },
                    style = MiruButtonStyle.Secondary,
                    enabled = !repoBusy,
                ) {
                    Text(text = stringResource(R.string.finder_repo_reset))
                }
            },
        ) {
            MiruTextField(
                value = repoText,
                onValueChange = { repoText = it },
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
                plate = false,
            )
        }
    }
    if (filterOpen && !repoSheetOpen && !hasOtherSheetOpen) {
        ExtensionFilterSheet(
            applied = filters,
            languages = languages,
            showStatus = false,
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
                    scope.launch {
                        // A failed refresh already reported itself; claiming
                        // "up to date" from the stale rows would be a lie.
                        if (!refreshRepository()) return@launch
                        // Read the refreshed row, not the snapshot the sheet
                        // opened with: the check may find a new version.
                        val current = plugins.orEmpty().firstOrNull { it.id == plugin.id }
                        val version = current?.availableVersion
                        if (current?.updateAvailable == true && version != null) {
                            toast(resources.getString(R.string.finder_update_available_fmt, version))
                        } else {
                            toast(resources.getString(R.string.finder_up_to_date_fmt, plugin.name))
                        }
                    }
                },
                onSettings = {
                    // Keep actionsFor: closing settings returns to the actions
                    // sheet while only one AppBottomSheet is mounted at a time.
                    settingsFor = plugin
                },
                onRestart = {
                    actionsFor = null
                    scope.launch {
                        provider.restartPlugin(plugin.id)
                        onSourceReset(plugin.id)
                        toast(resources.getString(R.string.finder_plugin_restarted_fmt, plugin.name))
                    }
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
        // The plugin's own settings come from a Lua call, so they arrive after
        // the sheet opens: the base URL is editable at once and the plugin's
        // settings appear below it as soon as the plugin answers. A failure is
        // shown with Retry, never as "this plugin has no settings". The error
        // is not cleared while a retry runs (the sheet shows progress in its
        // place), so the group is never blank for the whole Lua timeout.
        var pluginSettings by remember(plugin.id) { mutableStateOf(emptyList<PluginSetting>()) }
        var pluginSettingsError by remember(plugin.id) { mutableStateOf<String?>(null) }
        var pluginSettingsLoading by remember(plugin.id) { mutableStateOf(true) }
        var settingsAttempt by remember(plugin.id) { mutableIntStateOf(0) }
        LaunchedEffect(plugin.id, settingsAttempt) {
            pluginSettingsLoading = true
            try {
                when (val result = provider.settingsSchema(plugin.id)) {
                    is Outcome.Ok -> {
                        pluginSettings = result.value
                        pluginSettingsError = null
                    }
                    is Outcome.Err -> {
                        pluginSettings = emptyList()
                        pluginSettingsError = pluginSchemaErrorMessage(
                            error = result.error,
                            sourceId = plugin.id,
                            sourceName = plugin.name,
                            operation = "settings",
                        )
                    }
                }
            } finally {
                // A provider that throws must not leave the sheet stuck on the
                // loading branch with no error to show.
                pluginSettingsLoading = false
            }
        }
        // A new plugin must never inherit the previous one's sheet state.
        key(plugin.id) {
            PluginSettingsSheet(
                name = plugin.name,
                description = stringResource(
                    R.string.finder_source_meta_fmt,
                    plugin.language,
                    typeLabel(plugin.type),
                    plugin.installedVersion.orEmpty(),
                ),
                baseUrl = provider.baseUrl(plugin.id),
                settings = pluginSettings,
                settingsError = pluginSettingsError,
                settingsLoading = pluginSettingsLoading,
                onRetrySettings = { if (!pluginSettingsLoading) settingsAttempt++ },
                onSave = { url ->
                    scope.launch {
                        provider.setBaseUrl(plugin.id, url)
                        onSourceReset(plugin.id)
                    }
                },
                onClose = { settingsFor = null },
            )
        }
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
                val ids = selection.selected
                confirmBulk = false
                selection = selection.close()
                busyIds = busyIds + ids
                scope.launch {
                    try {
                        provider.uninstallMany(ids)
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
    onUpdate: () -> Unit,
    onMore: () -> Unit,
) {
    val extra = LocalMiruExtraColors.current
    val largeText = isLargeText()
    val metaLine = if (plugin.updateAvailable) {
        val versionSpan = stringResource(
            R.string.finder_version_update_fmt,
            plugin.installedVersion.orEmpty(),
            plugin.availableVersion,
        )
        "${plugin.language} · $versionSpan · ${typeLabel(plugin.type)}"
    } else {
        stringResource(
            R.string.finder_ext_row_fmt,
            plugin.language,
            plugin.installedVersion.orEmpty(),
            typeLabel(plugin.type),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { if (selectionMode) semanticsSelected = selected }
            .combinedClickable(
                enabled = !busy,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(start = MiruSpace.L, end = MiruSpace.Xs, top = MiruSpace.S, bottom = MiruSpace.S),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxActionWidth = maxWidth * 0.34f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
            ) {
                ExtensionIcon(plugin)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (plugin.enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (plugin.updateAvailable) extra.accent else extra.muted,
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        plugin.error != null -> Surface(
                            shape = RoundedCornerShape(MiruRadius.Badge),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                            modifier = Modifier.padding(top = MiruSpace.Xxs),
                        ) {
                            Text(
                                text = stringResource(R.string.finder_ext_status_error),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xxs),
                            )
                        }
                        !plugin.enabled -> Text(
                            text = stringResource(R.string.finder_ext_status_disabled),
                            style = MaterialTheme.typography.labelMedium,
                            color = extra.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                    selectionMode -> Checkbox(checked = selected, onCheckedChange = null)
                    else -> {
                        if (!largeText && plugin.updateAvailable) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = maxActionWidth)
                                    .width(IntrinsicSize.Max),
                            ) {
                                MiruCompactButton(
                                    label = stringResource(R.string.finder_update),
                                    selected = true,
                                    onClick = onUpdate,
                                )
                            }
                        }
                        // A broken plugin has no working toggle: its row opens the
                        // health sheet, and the error badge already says why.
                        if (plugin.error == null) {
                            val statusCd = stringResource(
                                if (plugin.enabled) {
                                    R.string.finder_ext_status_enabled
                                } else {
                                    R.string.finder_ext_status_disabled
                                },
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MiruRadius.Control))
                                    .semantics { contentDescription = statusCd }
                                    .clickable(role = Role.Switch, onClick = { onToggle(!plugin.enabled) })
                                    .compactTouchTarget(MiruSize.ControlCompactLarge),
                                contentAlignment = Alignment.Center,
                            ) {
                                Switch(
                                    checked = plugin.enabled,
                                    onCheckedChange = null,
                                    modifier = Modifier.scale(0.82f),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = extra.onAccent,
                                        checkedTrackColor = extra.accent,
                                        checkedBorderColor = extra.accent,
                                        checkedIconColor = extra.onAccent,
                                        uncheckedThumbColor = extra.muted,
                                        uncheckedTrackColor = extra.muted.copy(alpha = 0.32f),
                                        uncheckedBorderColor = extra.muted.copy(alpha = 0.55f),
                                        uncheckedIconColor = extra.muted,
                                    ),
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
        if (largeText && !busy && !selectionMode && plugin.updateAvailable) {
            MiruButton(
                onClick = onUpdate,
                style = MiruButtonStyle.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.S, end = MiruSpace.M),
            ) {
                Text(text = stringResource(R.string.finder_update))
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
    val largeText = isLargeText()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MiruSize.Row)
            .padding(start = MiruSpace.L, end = MiruSpace.M, top = MiruSpace.S, bottom = MiruSpace.S),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            ExtensionIcon(plugin)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (largeText) 2 else 1,
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
                    maxLines = if (largeText) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                busy -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = extra.accent,
                    modifier = Modifier
                        .padding(end = MiruSpace.Xs)
                        .size(22.dp),
                )
                !largeText && plugin.updateAvailable -> Box(modifier = Modifier.width(IntrinsicSize.Max)) {
                    MiruCompactButton(
                        label = stringResource(R.string.finder_update),
                        selected = true,
                        onClick = onUpdate,
                    )
                }
                plugin.installed -> Surface(
                    shape = RoundedCornerShape(MiruRadius.Badge),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = MiruSpace.S, vertical = MiruSpace.Xs),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = extra.muted,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.finder_installed_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = extra.muted,
                            modifier = Modifier.padding(start = MiruSpace.Xs),
                            maxLines = 1,
                        )
                    }
                }
                !largeText -> Box(modifier = Modifier.width(IntrinsicSize.Max)) {
                    MiruCompactButton(
                        label = stringResource(R.string.finder_install),
                        onClick = onInstall,
                    )
                }
            }
        }
        if (largeText && !busy && (plugin.updateAvailable || !plugin.installed)) {
            MiruButton(
                onClick = if (plugin.updateAvailable) onUpdate else onInstall,
                style = if (plugin.updateAvailable) MiruButtonStyle.Primary else MiruButtonStyle.Secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MiruSpace.S),
            ) {
                Text(
                    text = stringResource(
                        if (plugin.updateAvailable) R.string.finder_update else R.string.finder_install,
                    ),
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
    onRestart: () -> Unit,
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
            icon = Icons.Filled.PlayArrow,
            title = stringResource(R.string.finder_action_restart),
            subtitle = stringResource(R.string.finder_action_restart_sub),
            onClick = onRestart,
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
            destructive = true,
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

@Composable
private fun ExtensionActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val extra = LocalMiruExtraColors.current
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        border = miruCardBorder(),
        modifier = modifier.defaultMinSize(minHeight = MiruSize.Row),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .semantics(mergeDescendants = true) {
                    if (busy) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = MiruSpace.M, vertical = MiruSpace.S),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(MiruRadius.Badge))
                    .background(extra.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = extra.accent,
                        modifier = Modifier.size(MiruSize.Indicator),
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) extra.accent else extra.muted,
                        modifier = Modifier.size(MiruSize.IconCompact),
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else extra.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
