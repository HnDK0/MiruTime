package dev.mirutime.app.features.migration.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.di.MigrationGraph
import dev.mirutime.app.features.migration.domain.MigrationCandidateBook
import dev.mirutime.app.features.migration.domain.MigrationHistoryGroup
import dev.mirutime.app.features.migration.domain.MigrationLibrarySource
import dev.mirutime.app.features.migration.domain.UnknownMigrationSourceKey
import dev.mirutime.app.features.migration.domain.autoMigrationSearchQuery
import dev.mirutime.app.features.migration.domain.migrationBooksForSource
import dev.mirutime.app.features.migration.domain.migrationHistoryGroups
import dev.mirutime.app.features.migration.domain.migrationLibrarySources
import dev.mirutime.data.library.BookMigrationEntity
import dev.mirutime.data.library.migration.BookMigrationOptions
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.LocalMiruCardSurface
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.ChipRail
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.SectionHeader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

object MigrationRoutes {
    const val Screen = "migration"
}

private enum class MigrationTab(val key: String) {
    Single("single"),
    Mass("mass"),
    History("history"),
    ;

    companion object {
        fun from(key: String): MigrationTab = entries.firstOrNull { it.key == key } ?: Single
    }
}

/** NoveLA mechanics: source -> books -> results. Results never sit inline. */
private enum class MigrationStep(val key: String) {
    Source("source"),
    Books("books"),
    Results("results"),
    ;

    companion object {
        fun from(key: String): MigrationStep = entries.firstOrNull { it.key == key } ?: Source
    }
}

// The floating action is the only owner of the bottom gap: the list reserves
// exactly the CTA's measured height (it grows past 52dp when a long label
// wraps at 200%), and nothing at all while the CTA is hidden.
@Composable
fun MigrationScreen(
    contentPadding: PaddingValues,
    graph: MigrationGraph,
    onLibraryChanged: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenCatalogue: (String, String) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(MigrationTab.Single.key) }
    var sources by remember { mutableStateOf<List<CatalogSource>>(emptyList()) }
    var sourcesLoading by remember { mutableStateOf(true) }
    var sourcesError by remember { mutableStateOf<String?>(null) }
    var books by remember { mutableStateOf<List<MigrationCandidateBook>>(emptyList()) }
    var booksLoading by remember { mutableStateOf(true) }
    var booksError by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }

    val singleSelection = remember(graph) { MigrationSourceSelection() }
    val massSelection = remember(graph) { MigrationSourceSelection() }
    var singleStepKey by rememberSaveable { mutableStateOf(MigrationStep.Source.key) }
    var massStepKey by rememberSaveable { mutableStateOf(MigrationStep.Source.key) }
    var singleSourceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var massSourceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var singleOptions by remember { mutableStateOf(BookMigrationOptions()) }
    var massOptions by remember { mutableStateOf(BookMigrationOptions()) }

    // Single tab
    var singleQuery by rememberSaveable { mutableStateOf("") }
    var selectedUrl by rememberSaveable { mutableStateOf("") }
    // Mass tab
    var massBookQuery by rememberSaveable { mutableStateOf("") }
    val massState = remember(graph, scope) {
        MassMigrationState(graph, scope, massSelection)
    }

    // History tab
    var historyRows by remember { mutableStateOf<List<BookMigrationEntity>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var historyTick by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<MigrationHistoryGroup?>(null) }
    val selectedTab = MigrationTab.from(tab)

    LaunchedEffect(reloadTick) {
        sourcesLoading = true
        booksLoading = true
        sourcesError = null
        booksError = null
        val loadedSources = try {
            graph.search.sources()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sourcesError = e.message.orEmpty()
            emptyList()
        }
        sources = loadedSources
        sourcesLoading = false
        val loadedBooks = try {
            graph.targets.candidates(loadedSources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            booksError = e.message.orEmpty()
            emptyList()
        }
        books = loadedBooks
        booksLoading = false
    }
    LaunchedEffect(selectedTab, historyTick) {
        if (selectedTab != MigrationTab.History) return@LaunchedEffect
        historyLoading = true
        historyError = null
        historyRows = try {
            graph.targets.history()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            historyError = e.message.orEmpty()
            emptyList()
        }
        historyLoading = false
    }

    val librarySources = remember(books, sources) {
        migrationLibrarySources(books, sources)
    }
    val singleSource = librarySources.firstOrNull { it.key == singleSourceKey }
    val massSource = librarySources.firstOrNull { it.key == massSourceKey }
    val singleBooks = remember(books, singleSourceKey) {
        migrationBooksForSource(books, singleSourceKey)
    }
    val massBooks = remember(books, massSourceKey) {
        migrationBooksForSource(books, massSourceKey)
    }
    // The old source cannot be a target of its own migration. Unknown-source
    // books have no installed source to exclude.
    val singleTargetSources = remember(sources, singleSourceKey) {
        migrationTargetSources(sources, singleSourceKey)
    }
    val massTargetSources = remember(sources, massSourceKey) {
        migrationTargetSources(sources, massSourceKey)
    }
    val singleCandidate = singleBooks.firstOrNull { it.bookUrl == selectedUrl }
    val singleState = remember(singleCandidate, graph) {
        singleCandidate?.let {
            MigrationSingleState(it, graph, scope, singleSelection)
        }
    }

    LaunchedEffect(singleTargetSources) {
        singleSelection.sync(singleTargetSources)
    }
    LaunchedEffect(singleState, singleTargetSources) {
        singleState?.syncSources(singleTargetSources)
    }
    LaunchedEffect(singleState) {
        singleState?.options = singleOptions
    }
    LaunchedEffect(massTargetSources) {
        massState.syncSources(massTargetSources)
    }
    LaunchedEffect(massOptions) {
        massState.options = massOptions
    }

    // A saved step can outlive the state it pointed at (process death or a
    // removed plugin). Fall back instead of rendering an empty results step.
    val rawSingleStep = MigrationStep.from(singleStepKey)
    val singleStep = when {
        rawSingleStep != MigrationStep.Source && singleSource == null -> MigrationStep.Source
        rawSingleStep == MigrationStep.Results && singleState == null ->
            if (singleSource != null) MigrationStep.Books else MigrationStep.Source
        else -> rawSingleStep
    }
    val rawMassStep = MigrationStep.from(massStepKey)
    val massStep = when {
        rawMassStep != MigrationStep.Source && massSource == null -> MigrationStep.Source
        rawMassStep == MigrationStep.Results && massState.searchUi is MassSearchUi.Idle ->
            MigrationStep.Books
        else -> rawMassStep
    }

    fun pickSingleSource(source: MigrationLibrarySource) {
        // Re-picking the same source keeps the check; a different source is a
        // new flow with its own candidate and target selection.
        if (singleSourceKey != source.key) {
            singleSourceKey = source.key
            selectedUrl = ""
            singleSelection.reset(migrationTargetSources(sources, source.key))
        }
        singleStepKey = MigrationStep.Books.key
    }

    fun pickMassSource(source: MigrationLibrarySource) {
        if (massSourceKey != source.key) {
            massSourceKey = source.key
            massState.resetForSource()
            massSelection.reset(migrationTargetSources(sources, source.key))
        }
        massStepKey = MigrationStep.Books.key
    }

    fun startSingleSearch() {
        val state = singleState ?: return
        if (state.query.isBlank()) state.query = autoMigrationSearchQuery(state.candidate)
        if (state.selectedSources.isEmpty()) return
        state.search()
        singleStepKey = MigrationStep.Results.key
    }

    fun startMassSearch() {
        if (massState.selectedUrls.isEmpty()) return
        massState.search(massBooks, massTargetSources)
        massStepKey = MigrationStep.Results.key
    }

    val listState = rememberLazyListState()
    // A step change is a new screenful: start at its header, not at the scroll
    // offset left by the picker.
    LaunchedEffect(selectedTab, singleStep, massStep) { listState.scrollToItem(0) }

    val activeSelection: MigrationSourceSelection? = when (selectedTab) {
        MigrationTab.Single -> singleSelection
        MigrationTab.Mass -> massSelection
        MigrationTab.History -> null
    }
    val activeTargetSources = when (selectedTab) {
        MigrationTab.Single -> singleTargetSources
        MigrationTab.Mass -> massTargetSources
        MigrationTab.History -> emptyList()
    }
    val activeOptions = when (selectedTab) {
        MigrationTab.Single -> singleOptions
        MigrationTab.Mass -> massOptions
        MigrationTab.History -> null
    }
    val massMatched = (massState.searchUi as? MassSearchUi.Ready)
        ?.results
        ?.count { it.outcome is MassBookOutcome.Matched }
        ?: 0

    val cta: MigrationCta = when (selectedTab) {
        MigrationTab.Single -> when (singleStep) {
            MigrationStep.Source -> MigrationCta()
            MigrationStep.Books -> if (singleCandidate == null) {
                MigrationCta()
            } else {
                MigrationCta(
                    visible = true,
                    label = stringResource(R.string.migration_find_matches),
                    enabled = singleTargetSources.isNotEmpty(),
                    icon = Icons.Filled.Search,
                    onClick = { startSingleSearch() },
                )
            }
            MigrationStep.Results -> {
                val state = singleState
                when {
                    state == null -> MigrationCta()
                    state.runUi is MigrationRunUi.Success -> MigrationCta()
                    state.target != null && state.plan != null -> MigrationCta(
                        visible = true,
                        label = stringResource(R.string.migration_action),
                        enabled = state.canMigrate,
                        busy = state.runUi is MigrationRunUi.Running,
                        icon = Icons.Filled.DriveFileMove,
                        onClick = { state.migrate { onLibraryChanged() } },
                    )
                    state.targetLoading || state.target != null -> MigrationCta()
                    else -> MigrationCta(
                        visible = true,
                        label = stringResource(R.string.migration_search_action),
                        enabled = state.query.isNotBlank() &&
                            state.selectedSources.isNotEmpty(),
                        busy = state.searchUi is MigrationSearchUi.Searching,
                        icon = Icons.Filled.Search,
                        onClick = { state.search() },
                    )
                }
            }
        }
        MigrationTab.Mass -> when (massStep) {
            MigrationStep.Source -> MigrationCta()
            MigrationStep.Books -> if (massState.selectedUrls.isEmpty()) {
                MigrationCta()
            } else {
                MigrationCta(
                    visible = true,
                    label = stringResource(R.string.migration_find_matches),
                    enabled = massTargetSources.isNotEmpty(),
                    icon = Icons.Filled.Search,
                    onClick = { startMassSearch() },
                )
            }
            MigrationStep.Results -> when {
                massState.runUi is MassRunUi.Finished -> MigrationCta()
                massState.runUi is MassRunUi.Running -> MigrationCta(
                    visible = true,
                    label = pluralStringResource(
                        R.plurals.migration_bulk_action,
                        massMatched,
                        massMatched,
                    ),
                    busy = true,
                    icon = Icons.Filled.DriveFileMove,
                    onClick = {},
                )
                massState.searchUi is MassSearchUi.Searching -> MigrationCta(
                    visible = true,
                    label = stringResource(R.string.migration_searching_short),
                    busy = true,
                    icon = Icons.Filled.Search,
                    onClick = {},
                )
                massMatched > 0 -> MigrationCta(
                    visible = true,
                    label = pluralStringResource(
                        R.plurals.migration_bulk_action,
                        massMatched,
                        massMatched,
                    ),
                    enabled = massState.selectedSources.isNotEmpty(),
                    icon = Icons.Filled.DriveFileMove,
                    onClick = { massState.migrate { onLibraryChanged() } },
                )
                else -> MigrationCta()
            }
        }
        MigrationTab.History -> MigrationCta()
    }

    // Measured, not assumed: the plate grows taller when a long label wraps at
    // 200%, and the list must reserve exactly that while the CTA is shown.
    val density = LocalDensity.current
    var ctaHeight by remember { mutableStateOf(0.dp) }
    val ctaClearance = if (cta.visible) ctaHeight + MiruSpace.M else 0.dp

    CompositionLocalProvider(
        LocalMiruCardSurface provides MaterialTheme.colorScheme.surface,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(MiruSize.Gap),
                contentPadding = PaddingValues(
                    start = MiruSize.ScreenPad,
                    end = MiruSize.ScreenPad,
                    top = contentPadding.calculateTopPadding() + MiruSpace.Xs,
                    bottom = contentPadding.calculateBottomPadding() + ctaClearance,
                ),
            ) {
                item(key = "migration-tabs") {
                    MigrationTabs(selected = selectedTab, onSelect = { tab = it.key })
                }

                val loading = sourcesLoading || booksLoading
                if (loading) {
                    item(key = "migration-loading") {
                        MigrationStateCard(
                            title = stringResource(R.string.migration_loading),
                            loading = true,
                        )
                    }
                }
                val loadError = sourcesError ?: booksError
                if (!loading && loadError != null) {
                    item(key = "migration-load-error") {
                        MigrationErrorCard(
                            message = loadError.ifBlank {
                                stringResource(R.string.migration_unknown_error)
                            },
                            onRetry = { reloadTick++ },
                        )
                    }
                }
                if (!loading && loadError == null) {
                    when (selectedTab) {
                        MigrationTab.Single -> when (singleStep) {
                            MigrationStep.Source -> item(key = "single-source") {
                                MigrationLibrarySourceStep(
                                    sources = librarySources,
                                    onPick = ::pickSingleSource,
                                )
                            }
                            MigrationStep.Books -> {
                                val source = singleSource
                                if (source != null && singleTargetSources.isEmpty()) {
                                    item(key = "single-no-targets") {
                                        MigrationStateCard(
                                            title = stringResource(R.string.migration_no_sources_title),
                                            description = stringResource(R.string.migration_no_sources),
                                            icon = Icons.Filled.Extension,
                                        )
                                    }
                                } else if (source != null) {
                                    item(key = "single-books") {
                                        MigrationBooksStep(
                                            source = source,
                                            books = singleBooks,
                                            query = singleQuery,
                                            onQueryChange = { singleQuery = it },
                                            selectedUrls = if (selectedUrl.isBlank()) {
                                                emptySet()
                                            } else {
                                                setOf(selectedUrl)
                                            },
                                            multiSelect = false,
                                            optionsSelected = singleSelection.selected.size,
                                            optionsTotal = singleTargetSources.size,
                                            onOpenOptions = { filterSheetOpen = true },
                                            onChangeSource = {
                                                singleStepKey = MigrationStep.Source.key
                                            },
                                            onBook = { book ->
                                                selectedUrl = if (selectedUrl == book.bookUrl) {
                                                    ""
                                                } else {
                                                    book.bookUrl
                                                }
                                            },
                                            onSelectAll = {},
                                            onClearAll = {},
                                        )
                                    }
                                }
                            }
                            MigrationStep.Results -> {
                                val source = singleSource
                                val state = singleState
                                if (source != null && state != null) {
                                    item(key = "single-results") {
                                        MigrationSingleResultsStep(
                                            state = state,
                                            source = source,
                                            optionsSelected = singleSelection.selected.size,
                                            optionsTotal = singleTargetSources.size,
                                            onOpenOptions = { filterSheetOpen = true },
                                            onChangeBook = {
                                                state.clearTarget()
                                                singleStepKey = MigrationStep.Books.key
                                            },
                                            onOpenBook = onOpenBook,
                                            onOpenCatalogue = onOpenCatalogue,
                                        )
                                    }
                                }
                            }
                        }
                        MigrationTab.Mass -> when (massStep) {
                            MigrationStep.Source -> item(key = "mass-source") {
                                MigrationLibrarySourceStep(
                                    sources = librarySources,
                                    onPick = ::pickMassSource,
                                )
                            }
                            MigrationStep.Books -> {
                                val source = massSource
                                if (source != null && massTargetSources.isEmpty()) {
                                    item(key = "mass-no-targets") {
                                        MigrationStateCard(
                                            title = stringResource(R.string.migration_no_sources_title),
                                            description = stringResource(R.string.migration_no_sources),
                                            icon = Icons.Filled.Extension,
                                        )
                                    }
                                } else if (source != null) {
                                    item(key = "mass-books") {
                                        MigrationBooksStep(
                                            source = source,
                                            books = massBooks,
                                            query = massBookQuery,
                                            onQueryChange = { massBookQuery = it },
                                            selectedUrls = massState.selectedUrls,
                                            multiSelect = true,
                                            optionsSelected = massSelection.selected.size,
                                            optionsTotal = massTargetSources.size,
                                            onOpenOptions = { filterSheetOpen = true },
                                            onChangeSource = {
                                                massStepKey = MigrationStep.Source.key
                                            },
                                            onBook = { massState.toggleBook(it.bookUrl) },
                                            onSelectAll = { urls ->
                                                massState.toggleBooks(urls, select = true)
                                            },
                                            onClearAll = {
                                                massState.toggleBooks(
                                                    massState.selectedUrls,
                                                    select = false,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            MigrationStep.Results -> {
                                val source = massSource
                                if (source != null) {
                                    item(key = "mass-results") {
                                        MigrationMassResultsStep(
                                            state = massState,
                                            books = massBooks,
                                            sources = massTargetSources,
                                            sourceName = librarySourceName(source),
                                            optionsSelected = massSelection.selected.size,
                                            optionsTotal = massTargetSources.size,
                                            onOpenOptions = { filterSheetOpen = true },
                                            onChangeBooks = {
                                                massStepKey = MigrationStep.Books.key
                                            },
                                            onOpenCatalogue = onOpenCatalogue,
                                        )
                                    }
                                }
                            }
                        }
                        MigrationTab.History -> item(key = "migration-history") {
                            HistoryTabContent(
                                loading = historyLoading,
                                error = historyError,
                                groups = migrationHistoryGroups(historyRows).take(100),
                                onRetry = { historyTick++ },
                                onDelete = { deleting = it },
                                modifier = Modifier.fillParentMaxHeight(),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = cta.visible,
                enter = fadeIn(animationSpec = tween(150)) +
                    slideInVertically(animationSpec = tween(200)) { height -> height / 2 },
                exit = fadeOut(animationSpec = tween(120)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(
                        end = MiruSize.ScreenPad,
                        bottom = contentPadding.calculateBottomPadding() + MiruSpace.M,
                    ),
            ) {
                MigrationFloatingAction(
                    label = cta.label,
                    enabled = cta.enabled,
                    busy = cta.busy,
                    icon = cta.icon,
                    onClick = cta.onClick,
                    modifier = Modifier.onSizeChanged { size ->
                        ctaHeight = with(density) { size.height.toDp() }
                    },
                )
            }
        }
    }

    val sheetSelection = activeSelection
    if (filterSheetOpen && sheetSelection != null && activeOptions != null) {
        MigrationFilterSheet(
            sources = activeTargetSources,
            selected = sheetSelection.selected,
            options = activeOptions,
            onClose = { filterSheetOpen = false },
            onApply = { ids, options ->
                sheetSelection.apply(ids)
                when (selectedTab) {
                    MigrationTab.Single -> {
                        singleOptions = options
                        singleState?.options = options
                    }
                    MigrationTab.Mass -> {
                        massOptions = options
                        massState.options = options
                    }
                    MigrationTab.History -> Unit
                }
                filterSheetOpen = false
            },
        )
    }

    if (deleting != null) {
        val group = deleting!!
        AppBottomSheet(
            title = stringResource(R.string.migration_history_delete_title),
            description = stringResource(R.string.migration_history_delete_desc),
            onClose = { deleting = null },
            doneLabel = stringResource(R.string.migration_history_delete),
            doneDanger = true,
            onDone = {
                deleting = null
                val key = group.latest.batchId ?: return@AppBottomSheet
                scope.launch {
                    graph.targets.deleteHistoryGroup(key)
                    historyTick++
                }
            },
        ) {
            Text(
                text = if (group.latest.chaptersTotal > 0) {
                    stringResource(
                        R.string.migration_history_row_fmt,
                        group.latest.chaptersMatched,
                        group.latest.chaptersTotal,
                    )
                } else {
                    stringResource(R.string.migration_no_chapter_data)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.migration_history_status_fmt,
                    migrationStatusLabel(group.latest),
                    formatMigrationTime(group.latest.createdAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = LocalMiruExtraColors.current.muted,
            )
        }
    }
}

private data class MigrationCta(
    val visible: Boolean = false,
    val label: String = "",
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val icon: ImageVector? = null,
    val onClick: () -> Unit = {},
)

private fun migrationTargetSources(
    sources: List<CatalogSource>,
    librarySourceKey: String?,
): List<CatalogSource> = if (
    librarySourceKey == null || librarySourceKey == UnknownMigrationSourceKey
) {
    sources
} else {
    sources.filterNot { it.id == librarySourceKey }
}

@Composable
private fun librarySourceName(source: MigrationLibrarySource): String =
    if (source.unknown) {
        stringResource(R.string.migration_unknown_source_bucket)
    } else {
        source.name
    }

@Composable
private fun MigrationTabs(selected: MigrationTab, onSelect: (MigrationTab) -> Unit) {
    // One scrolling line at 200% font instead of a wrapping block.
    ChipRail {
        MigrationTab.entries.forEach { entry ->
            item(key = entry.key) {
                MiruFilterChip(
                    selected = selected == entry,
                    onClick = { onSelect(entry) },
                    label = stringResource(
                        when (entry) {
                            MigrationTab.Single -> R.string.migration_tab_single
                            MigrationTab.Mass -> R.string.migration_tab_mass
                            MigrationTab.History -> R.string.migration_tab_history
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun HistoryTabContent(
    loading: Boolean,
    error: String?,
    groups: List<MigrationHistoryGroup>,
    onRetry: () -> Unit,
    onDelete: (MigrationHistoryGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        SectionHeader(
            title = stringResource(R.string.migration_history_title),
            description = stringResource(R.string.migration_history_desc),
        )
        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                MigrationStateCard(
                    title = stringResource(R.string.migration_loading),
                    loading = true,
                )
            }
            error != null -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                MigrationErrorCard(
                    message = error.ifBlank { stringResource(R.string.migration_unknown_error) },
                    onRetry = onRetry,
                )
            }
            groups.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                MigrationStateCard(
                    title = stringResource(R.string.migration_history_empty),
                    description = stringResource(R.string.migration_history_empty_desc),
                    icon = Icons.Filled.History,
                )
            }
            else -> groups.forEach { group ->
                MigrationHistoryCard(group = group, onDelete = { onDelete(group) })
            }
        }
    }
}
