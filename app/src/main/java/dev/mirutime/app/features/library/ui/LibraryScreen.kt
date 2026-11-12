package dev.mirutime.app.features.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mirutime.uikit.CoverInset
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruIconButton
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.LocalMiruOnImage
import dev.mirutime.uikit.OnImageFrost
import dev.mirutime.uikit.miruPhotoMuted
import dev.mirutime.uikit.miruCardElevation
import dev.mirutime.uikit.miruCardSurface
import dev.mirutime.uikit.withImageShadow
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.app.design.components.CardStylePicker
import dev.mirutime.uikit.MiruSearchBar
import dev.mirutime.uikit.CardProgressBar
import dev.mirutime.uikit.ChipRail
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruButton
import dev.mirutime.uikit.MiruButtonStyle
import dev.mirutime.uikit.MiruRoundIconButton
import dev.mirutime.uikit.MiruSlider
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.uikit.SkeletonBox
import dev.mirutime.uikit.SheetHeaderAction
import dev.mirutime.uikit.SheetSection
import dev.mirutime.app.core.libraryListKey
import dev.mirutime.app.core.libraryRowKey
import dev.mirutime.data.library.libraryBookKey
import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.di.ReaderGraph
import dev.mirutime.app.features.library.data.BulkActionResult
import dev.mirutime.app.features.library.data.LibraryDataProvider
import dev.mirutime.app.features.library.data.LibraryFacets
import dev.mirutime.app.features.library.data.LibraryPageCursor
import dev.mirutime.app.features.library.data.LibraryPageFilters
import dev.mirutime.app.features.library.data.LibraryPageSize
import dev.mirutime.app.features.library.data.LibraryShelfSummary
import dev.mirutime.app.features.library.data.sortAppliesToQuery
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.app.features.library.domain.LibrarySort
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.translation.data.TitleTranslationJob
import dev.mirutime.app.features.translation.data.TitleSurface
import dev.mirutime.app.features.translation.data.metadataConfigFor
import dev.mirutime.app.features.translation.data.translationPresentationFor
import dev.mirutime.app.features.library.domain.Shelf
import dev.mirutime.app.features.library.domain.SortDirection
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import dev.mirutime.uikit.MiruSearchDebounceMillis

private enum class LayoutMode { Grid, List }

private enum class BulkAction { Delete, Move, Read, Unread }

// Returning from a book/bulk action refreshes only the pages the user had
// actually scrolled through, capped so a deep list never refetches all rows.
private const val MaxReloadPages = 5

// Library order: search first, shelf chips, last-read
// block, mini-filters, then the grid. Full filter state lives here so the big
// sheet and the mini-filters stay in two-way sync.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    readerGraph: ReaderGraph,
    provider: LibraryDataProvider,
    refreshKey: Int = 0,
    onBookOpen: (String) -> Unit = {},
) {
    // Shelves are one ordered list: Reading + Completed pinned on top,
    // everything else is manageable (08-library.md). Deleting a shelf is
    // safe: its books are reparented to Reading, never dropped.
    var shelves by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    var lastRead by remember { mutableStateOf<LibraryBook?>(null) }
    // A repaired cover URL outlives a disposed card; keep it keyed by book so
    // scrolling away never brings the broken art back.
    val coverFixes = remember { mutableStateMapOf<String, String>() }
    // Facets and shelf counters come from SQL, not from every loaded page.
    var facets by remember { mutableStateOf(LibraryFacets(emptyList(), emptyList())) }
    var shelfSummaries by remember { mutableStateOf<List<LibraryShelfSummary>>(emptyList()) }
    var metaLoaded by remember { mutableStateOf(false) }
    // The list is a keyset/bm25 pager: only visible pages live in memory.
    var pager by remember { mutableStateOf(LibraryPagerState()) }
    var appliedQuery by remember { mutableStateOf("") }
    var selectAllBusy by remember { mutableStateOf(false) }

    var query by rememberSaveable { mutableStateOf("") }
    var shelfId by rememberSaveable { mutableStateOf("reading") }
    // Empty set = all sources (the All chip). Sets are not saveable-safe,
    // so this one stays in-memory like genres.
    var selSources by remember { mutableStateOf(emptySet<String>()) }
    var type by rememberSaveable { mutableStateOf<ContentType?>(null) }
    var genres by remember { mutableStateOf(emptySet<String>()) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.LastRead) }
    var sortDir by rememberSaveable { mutableStateOf(SortDirection.Desc) }
    // Layout and columns are shared with the catalogue grid and Settings ->
    // Library (BOOKS_LIST_LAYOUT_MODE / BOOKS_GRID_COLUMNS in AppSettings).
    val layout = if (AppSettings.listLayout == "list") LayoutMode.List else LayoutMode.Grid
    // At 200 % three columns leave 6–8 characters per line; the approved
    // rule is two columns from 1.3× up, while the stored setting stays.
    val fontScale = LocalDensity.current.fontScale
    val columns = if (fontScale >= 1.3f) 2 else AppSettings.gridColumns.coerceIn(2, 6)
    // One shared style (LIBRARY_SOURCE_STRIP_POSITION): setting it here also
    // drives the dashboard rails and the catalog grid. List layout always
    // renders C (the file's horizontal variant).
    val cardStyle = AppSettings.cardStyle

    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var sourceSheetOpen by rememberSaveable { mutableStateOf(false) }
    var genresSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sortSheetOpen by rememberSaveable { mutableStateOf(false) }
    var shelfManagerOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    // True from the picker handoff until importFiles + the library reload
    // finish; the sheet stays open and shows a blocking progress state.
    var importing by remember { mutableStateOf(false) }
    // Selection is UI-only and keyed by the stable book URL. Cleared after a
    // fully successful bulk action; failed URLs stay selected for a retry.
    var selection by remember { mutableStateOf(LibrarySelection()) }
    var bulkBusy by remember { mutableStateOf(false) }
    var bulkDeleteOpen by remember { mutableStateOf(false) }
    var bulkMoveOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val ioScope = rememberCoroutineScope()

    val listState = rememberLazyListState()
    // The button appears once the user is more than one viewport into the
    // list; the first pass keeps a restored position instead of snapping up.
    val showScrollToTop by remember(listState) {
        derivedStateOf {
            // firstVisibleItemIndex ignores the pinned sticky header; a scan of
            // visibleItemsInfo would keep reporting index 0 and never show it.
            val first = listState.firstVisibleItemIndex
            shouldShowScrollToTop(
                firstVisibleItemIndex = first,
                visibleItemCount = scrollingItemCount(
                    visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index },
                    firstVisibleItemIndex = first,
                ),
            )
        }
    }
    var scrollResetPrimed by remember { mutableStateOf(false) }
    fun currentFilters() = LibraryPageFilters(
        shelfId = shelfId,
        sources = selSources,
        contentType = type,
        genres = genres,
    )
    val filters = remember(shelfId, selSources, type, genres) { currentFilters() }

    // One page-fetch loop for first load, retry and the post-mutation refresh;
    // [generation] makes a stale response a no-op.
    suspend fun loadPages(
        generation: Int,
        targetPages: Int,
        pageFilters: LibraryPageFilters,
    ) {
        var cursor: LibraryPageCursor? = null
        var loaded = 0
        var first = true
        while (loaded < targetPages) {
            val slice = try {
                provider.libraryPage(
                    query = appliedQuery,
                    cursor = cursor,
                    limit = LibraryPageSize,
                    filters = pageFilters,
                    sort = sort,
                    direction = sortDir,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MiruLog.w("library", "Library page failed", e)
                if (generation == pager.generation) {
                    pager = if (first) pager.failFirst(generation) else pager.failMore(generation)
                }
                return
            }
            if (generation != pager.generation) return
            if (first) {
                pager = pager.applyFirst(generation, slice)
                first = false
                MiruLog.event(
                    "library",
                    "page",
                    "queryLen" to appliedQuery.length,
                    "loaded" to pager.books.size,
                    "hasMore" to pager.hasMore,
                    "total" to (pager.total ?: -1),
                )
            } else {
                pager = pager.beginMore()
                pager = pager.applyMore(generation, slice)
            }
            loaded++
            cursor = slice.nextCursor ?: break
            if (!slice.hasMore) break
        }
    }

    fun requestFirstPage() {
        pager = pager.beginFirst()
        val generation = pager.generation
        val pageFilters = currentFilters()
        ioScope.launch { loadPages(generation, 1, pageFilters) }
    }

    fun requestMore() {
        if (!pager.hasMore || pager.loadMoreBlocked) return
        val cursor = pager.cursor ?: return
        pager = pager.beginMore()
        val generation = pager.generation
        val pageFilters = currentFilters()
        ioScope.launch {
            val slice = try {
                provider.libraryPage(
                    query = appliedQuery,
                    cursor = cursor,
                    limit = LibraryPageSize,
                    filters = pageFilters,
                    sort = sort,
                    direction = sortDir,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MiruLog.w("library", "Library next page failed", e)
                pager = pager.failMore(generation)
                return@launch
            }
            pager = pager.applyMore(generation, slice)
        }
    }

    fun retryMore() {
        pager = pager.retryMore()
        requestMore()
    }

    suspend fun reloadMeta() {
        shelves = runCatching { provider.shelves() }
            .onFailure { MiruLog.w("library", "Shelf reload failed", it) }
            .getOrDefault(shelves)
        lastRead = runCatching { provider.lastRead() }
            .onFailure { MiruLog.w("library", "Resume reload failed", it) }
            .getOrNull()
        facets = runCatching { provider.libraryFacets() }
            .onFailure { MiruLog.w("library", "Facet reload failed", it) }
            .getOrDefault(facets)
        shelfSummaries = runCatching { provider.shelfSummaries() }
            .onFailure { MiruLog.w("library", "Shelf summary reload failed", it) }
            .getOrDefault(shelfSummaries)
    }

    // Refresh the pages the user actually loaded, capped. A page-count change
    // or filter change in flight is handled by the generation guard.
    suspend fun refreshPages() {
        val target = pager.pagesLoaded.coerceIn(1, MaxReloadPages)
        pager = pager.beginFirst()
        val generation = pager.generation
        loadPages(generation, target, currentFilters())
    }

    // Meta reads are independent of paging. A live restore (refreshKey) also
    // refreshes the pages already on screen instead of only page one.
    var revisionHandled by remember { mutableStateOf(false) }
    LaunchedEffect(provider, refreshKey) {
        val refresh = revisionHandled
        val targetPages = if (refresh) pager.pagesLoaded.coerceIn(1, MaxReloadPages) else 0
        if (!refresh) metaLoaded = false
        shelves = runCatching { provider.shelves() }
            .onFailure { MiruLog.w("library", "Shelf load failed", it) }
            .getOrDefault(emptyList())
        lastRead = runCatching { provider.lastRead() }
            .onFailure { MiruLog.w("library", "Resume load failed", it) }
            .getOrNull()
        facets = runCatching { provider.libraryFacets() }
            .onFailure { MiruLog.w("library", "Facet load failed", it) }
            .getOrDefault(LibraryFacets(emptyList(), emptyList()))
        shelfSummaries = runCatching { provider.shelfSummaries() }
            .onFailure { MiruLog.w("library", "Shelf summary load failed", it) }
            .getOrDefault(emptyList())
        metaLoaded = true
        revisionHandled = true
        if (targetPages > 0) {
            pager = pager.beginFirst()
            val generation = pager.generation
            loadPages(generation, targetPages, filters)
        }
    }

    // Filter state is user-visible config, not content: safe to log as counts.
    LaunchedEffect(shelfId, selSources, type, genres, sort, sortDir) {
        MiruLog.event(
            "library",
            "filters",
            "shelf" to shelfId,
            "sources" to selSources.size,
            "type" to (type?.name ?: "all"),
            "genres" to genres.size,
            "sort" to sort.name,
            "dir" to sortDir.name,
        )
    }

    val sources = facets.sources
    val allGenres = facets.genres
    // The 300 ms debounce is still a real pending state, not an empty result.
    val searchPending = query.trim() != appliedQuery
    // Search is bm25 relevance; global sort is intentionally not applied while
    // a query is active, so its controls must not look live.
    val sortEnabled = sortAppliesToQuery(query)

    // Search is debounced, then the pager reloads from offset zero for the
    // current term; filters/sort change under it stay SQL-side.
    LaunchedEffect(provider, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            appliedQuery = ""
        } else {
            delay(MiruSearchDebounceMillis)
            appliedQuery = trimmed
        }
        MiruLog.event("library", "search", "queryLen" to trimmed.length)
    }

    LaunchedEffect(
        provider,
        shelfId,
        selSources,
        type,
        genres,
        sort,
        sortDir,
        appliedQuery,
    ) {
        // New filter/shelf/query = new list: start it at the top. The very
        // first pass is a restored screen, so it keeps its saved position.
        if (scrollResetPrimed) {
            listState.scrollToItem(0)
        } else {
            scrollResetPrimed = true
        }
        pager = pager.beginFirst()
        val generation = pager.generation
        loadPages(generation, 1, filters)
    }

    // FULL-mode titles: one batch per identical config, warmed before a card
    // leaves the original title. The state map is shared with the book page.
    val metadata by readerGraph.metadataTranslations.state.collectAsState()
    val metadataJobs by remember(pager.books) {
        derivedStateOf {
            pager.books.mapNotNull { book ->
                if (!translationPresentationFor(book.bookKey).showsTitle(TitleSurface.Library)) {
                    return@mapNotNull null
                }
                metadataConfigFor(book.bookKey)?.let { config ->
                    TitleTranslationJob(book.bookKey, book.title, config)
                }
            }
        }
    }
    LaunchedEffect(metadataJobs) {
        readerGraph.metadataTranslations.hydrateTitles(metadataJobs)
        readerGraph.metadataTranslations.submitTitles(metadataJobs)
    }
    val configs = remember(metadataJobs) { metadataJobs.associate { it.bookUrl to it.config } }
    // Pages are already deduped by the pager; the overlay is page-local.
    val fixCover: (LibraryBook) -> LibraryBook = { book ->
        coverFixes[book.bookKey]?.let { book.copy(coverUrl = it) } ?: book
    }
    val shown = remember(pager.books, metadata, configs) {
        pager.books.map { book ->
            val translated = metadata[book.bookKey]
                ?.takeIf { it.config == configs[book.bookKey] }
                ?.title
            if (translated != null) book.copy(translatedTitle = translated) else book
        }
    }
    // Near-end prefetch: one page ahead of the last visible row.
    LaunchedEffect(
        listState,
        pager.hasMore,
        pager.loadingFirst,
        pager.loadingMore,
        pager.moreError,
        shown.size,
    ) {
        if (!pager.hasMore || pager.loadMoreBlocked) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (last, total) ->
                if (shouldLoadMore(last, total, pager.hasMore, pager.loadMoreBlocked)) {
                    requestMore()
                }
            }
    }

    fun toggleSelection(bookKey: String) {
        selection = reduceLibrarySelection(
            selection,
            LibrarySelectionAction.Toggle(bookKey),
        )
    }

    fun openOrToggle(bookKey: String) {
        if (selection.active) {
            toggleSelection(bookKey)
        } else {
            onBookOpen(bookKey)
        }
    }

    fun longPressSelection(bookKey: String) {
        selection = reduceLibrarySelection(
            selection,
            LibrarySelectionAction.LongPress(bookKey),
        )
    }

    fun bulkMessage(action: BulkAction, result: BulkActionResult): String {
        val done = result.succeeded.size
        val failed = result.failed.size
        if (done == 0) return resources.getString(R.string.lib_bulk_none_fmt, failed)
        val doneText = when (action) {
            BulkAction.Delete -> resources.getQuantityString(
                R.plurals.plural_bulk_deleted,
                done,
                done,
            )
            BulkAction.Move -> resources.getQuantityString(R.plurals.plural_bulk_moved, done, done)
            BulkAction.Read -> resources.getQuantityString(R.plurals.plural_bulk_read, done, done)
            BulkAction.Unread -> resources.getQuantityString(
                R.plurals.plural_bulk_unread,
                done,
                done,
            )
        }
        return if (failed == 0) {
            doneText
        } else {
            resources.getString(R.string.lib_bulk_partial_fmt, doneText, failed)
        }
    }

    fun runBulk(
        action: BulkAction,
        block: suspend (Set<String>) -> BulkActionResult,
    ) {
        if (bulkBusy || selection.isEmpty) return
        val urls = selection.selected
        // Flip immediately, before the coroutine starts: a second tap on the
        // same frame sees the pending flag and does nothing.
        bulkBusy = true
        ioScope.launch {
            MiruLog.event("library", "bulk-start", "action" to action.name, "books" to urls.size)
            val result = try {
                block(urls)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MiruLog.w("library", "Bulk $action failed", e)
                BulkActionResult(emptySet(), urls)
            }
            MiruLog.event(
                "library",
                "bulk-result",
                "action" to action.name,
                "ok" to result.succeeded.size,
                "failed" to result.failed.size,
            )
            // Refresh metadata and the pages already on screen with the
            // filters active at completion time.
            reloadMeta()
            refreshPages()
            selection = if (result.failed.isEmpty()) {
                LibrarySelection()
            } else {
                LibrarySelection(active = true, selected = result.failed)
            }
            bulkBusy = false
            android.widget.Toast.makeText(
                context,
                bulkMessage(action, result),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val gridRows = remember(shown, columns) { shown.chunked(columns) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MiruSize.ScreenPad,
                end = MiruSize.ScreenPad,
                top = contentPadding.calculateTopPadding() + MiruSpace.Xs,
                bottom = contentPadding.calculateBottomPadding() + MiruSpace.Xl,
            ),
        ) {
            // Pinned top action bar: it stays visible while the grid scrolls.
            stickyHeader(key = "library-selection") {
                if (selection.active) {
                    LibrarySelectionBar(
                        selectedCount = selection.count,
                        // SQL total when known; search has no count query.
                        visibleCount = pager.total ?: shown.size,
                        busy = bulkBusy || selectAllBusy,
                        onClose = { selection = LibrarySelection() },
                        onSelectAll = {
                            if (!selectAllBusy) {
                                selectAllBusy = true
                                ioScope.launch {
                                    var failed = false
                                    val sources = try {
                                        provider.matchingLibrarySources(appliedQuery, filters)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        MiruLog.w("library", "Select-all sources failed", e)
                                        failed = true
                                        emptyMap()
                                    } finally {
                                        selectAllBusy = false
                                    }
                                    val keys = if (failed) {
                                        emptySet()
                                    } else if (sources.isNotEmpty()) {
                                        sources.flatMap { (url, installs) ->
                                            if (installs.isEmpty()) {
                                                listOf(url)
                                            } else {
                                                installs.map { libraryBookKey(it, url) }
                                            }
                                        }.toSet()
                                    } else {
                                        try {
                                            val urls = provider
                                                .matchingLibraryUrls(appliedQuery, filters)
                                                .toSet()
                                            shown.filter { it.bookUrl in urls }
                                                .map { it.bookKey }
                                                .toSet()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            MiruLog.w("library", "Select-all failed", e)
                                            failed = true
                                            emptySet()
                                        }
                                    }
                                    if (failed) {
                                        android.widget.Toast.makeText(
                                            context,
                                            R.string.lib_select_all_failed,
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        selection = reduceLibrarySelection(
                                            selection,
                                            LibrarySelectionAction.SelectAll(keys),
                                        )
                                    }
                                }
                            }
                        },
                        onClear = {
                            selection = reduceLibrarySelection(selection, LibrarySelectionAction.Clear)
                        },
                        onMove = { bulkMoveOpen = true },
                        onMarkRead = {
                            runBulk(BulkAction.Read) { provider.markBooksRead(it, read = true) }
                        },
                        onMarkUnread = {
                            runBulk(BulkAction.Unread) { provider.markBooksRead(it, read = false) }
                        },
                        onDelete = { bulkDeleteOpen = true },
                    )
                }
            }
            item {
                // The exact Finder / Global Search field (one shared component):
                // 40dp, filled, clear button. The filter button stays its own
                // sibling control.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                ) {
                    val focusManager = LocalFocusManager.current
                    MiruSearchBar(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.lib_search_hint),
                        onClear = { query = "" },
                        onSearch = { focusManager.clearFocus() },
                        modifier = Modifier.weight(1f),
                    )
                    MiruRoundIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.lib_cd_filters),
                        onClick = { filterOpen = true },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(MiruSpace.S))
            }
            item {
                ShelfTabsRow(
                    shelves = shelves,
                    selectedId = shelfId,
                    onSelect = { shelfId = it },
                    onManage = { shelfManagerOpen = true },
                    onTabLongPress = { shelfManagerOpen = true },
                    modifier = Modifier,
                )
            }

            if (
                shown.isEmpty() &&
                !pager.firstError &&
                (searchPending || !metaLoaded || pager.pagesLoaded == 0)
            ) {
                // Skeleton in the final slots: same resume bar + cover grid
                // geometry, so first paint never jumps when data lands.
                item {
                    LibrarySkeleton(columns = columns)
                }
            } else if (pager.firstError && shown.isEmpty()) {
                item(key = "library-first-error") {
                    LibraryPageError(
                        message = stringResource(R.string.lib_page_error),
                        onRetry = { requestFirstPage() },
                    )
                }
            } else {
                if (lastRead != null && shelfId == "reading") {
                    item {
                        lastRead?.let { book ->
                            ResumeCard(
                                book = fixCover(book),
                                onOpen = { onBookOpen(book.bookKey) },
                                onRetry = {
                                    provider.refreshCover(book.bookKey).getOrNull()?.also { fresh ->
                                        coverFixes[book.bookKey] = fresh
                                    }
                                },
                                reloadKey = pager.generation,
                            )
                        }
                    }
                }

                item {
                    MiniFilters(
                        sources = selSources,
                        genres = genres,
                        layout = layout,
                        sort = sort,
                        sortDir = sortDir,
                        sortEnabled = sortEnabled,
                        onSource = { sourceSheetOpen = true },
                        onGenres = { genresSheetOpen = true },
                        onLayout = {
                            AppSettings.listLayout =
                                if (layout == LayoutMode.Grid) "list" else "grid"
                        },
                        onSort = { sortSheetOpen = true },
                    )
                }

                // Shelf caption (prototype language): live count on the left, the
                // local-import entry on the right.
            item {
                // Counter row: hug-content mini-pill + Import pill, space-between.
                // Shared size grid: 28dp pills, 12sp labels — identical in every
                // mode; backing + ink come from the adaptive top strip zone
                // (checked at decode), classic keeps theme colors.
                val onImage = LocalMiruOnImage.current
                val zone = LocalMiruExtraColors.current.photoTop
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MiruSpace.Xs),
                ) {
                    OnImageFrost(
                        backingColor = if (onImage) zone.pillBackdrop else null,
                        blur = 10.dp,
                    ) {
                        val count = pager.total ?: shown.size
                        Text(
                            text = pluralStringResource(
                                R.plurals.plural_books,
                                count,
                                count,
                            ),
                            style = MaterialTheme.typography.bodySmall.withImageShadow(),
                            color = if (onImage) zone.pillInk else miruPhotoMuted(),
                            // EXACTLY 32dp tall in BOTH modes: the height lives on
                            // the text, not on the photo-only frost padding — that
                            // skip was the actual-size collapse source (16–20dp).
                            modifier = Modifier
                                .heightIn(min = MiruSize.RowCompact)
                                .wrapContentHeight(Alignment.CenterVertically)
                                .padding(horizontal = MiruSpace.M),
                        )
                    }
                    MiruButton(
                        onClick = { importOpen = true },
                        style = MiruButtonStyle.Secondary,
                    ) {
                        Text(text = stringResource(R.string.lib_import))
                    }
                }
            }

                // Stale-while-revalidate: the previous page stays visible and a
                // slim spinner says the next page/query is still running.
                if (pager.refreshing || (searchPending && shown.isNotEmpty())) {
                    item(key = "library-page-progress") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = MiruSpace.S),
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = LocalMiruExtraColors.current.accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(MiruSpace.S))
                            Text(
                                text = stringResource(
                                    if (appliedQuery.isBlank()) {
                                        R.string.loading_value
                                    } else {
                                        R.string.loading_searching
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalMiruExtraColors.current.muted,
                            )
                        }
                    }
                }
                if (pager.firstError && shown.isNotEmpty()) {
                    item(key = "library-refresh-error") {
                        LibraryPageError(
                            message = stringResource(R.string.lib_page_error),
                            onRetry = { requestFirstPage() },
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = miruCardSurface(),
                            shadowElevation = miruCardElevation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = MiruSpace.M),
                        ) {
                            Column(
                                modifier = Modifier.padding(MiruSpace.Xl),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.lib_empty_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(MiruSpace.Xs))
                                Text(
                                    text = stringResource(R.string.lib_empty_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalMiruExtraColors.current.muted,
                                )
                            }
                        }
                    }
                } else if (layout == LayoutMode.Grid && cardStyle != CardStyle.Horizontal) {
                    gridRows.forEach { row ->
                        item(key = libraryRowKey(row.first().bookKey)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = MiruSpace.CardGap),
                                // Minimal approved gutter: 4dp on both axes;
                                // the cover frame adds the visual separation.
                                horizontalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
                            ) {
                                row.forEach { book ->
                                    LibraryCard(
                                        book = fixCover(book),
                                        style = cardStyle,
                                        selected = selection.isSelected(book.bookKey),
                                        selectionMode = selection.active,
                                        // Tap opens normally; in selection mode it toggles.
                                        onOpen = { openOrToggle(book.bookKey) },
                                        onLongPress = { longPressSelection(book.bookKey) },
                                        onRetry = {
                                            provider.refreshCover(book.bookKey).getOrNull()?.also { fresh ->
                                                coverFixes[book.bookKey] = fresh
                                            }
                                        },
                                        reloadKey = pager.generation,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(columns - row.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    // Horizontal C cards: full-width rows in grid, and the List
                    // layout always (C is the file's horizontal variant).
                    items(shown, key = { libraryListKey(it.bookKey) }) { book ->
                        LibraryCard(
                            book = fixCover(book),
                            style = CardStyle.Horizontal,
                            selected = selection.isSelected(book.bookKey),
                            selectionMode = selection.active,
                            onOpen = { openOrToggle(book.bookKey) },
                            onLongPress = { longPressSelection(book.bookKey) },
                            onRetry = {
                                provider.refreshCover(book.bookKey).getOrNull()?.also { fresh ->
                                    coverFixes[book.bookKey] = fresh
                                }
                            },
                            reloadKey = pager.generation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = MiruSpace.CardGap),
                        )
                    }
                }
                if (showsLoadMoreFooter(pager.hasMore, pager.loadingMore, pager.moreError)) {
                    item(key = "library-pager-footer") {
                        LibraryPagerFooter(
                            loading = pager.loadingMore,
                            error = pager.moreError,
                            onRetry = { retryMore() },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showScrollToTop,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.9f, animationSpec = tween(150)),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f, animationSpec = tween(120)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = MiruSize.ScreenPad,
                    // Float above the shell's nav plate, not under it.
                    bottom = contentPadding.calculateBottomPadding() + MiruSpace.L,
                ),
        ) {
            ScrollToTopButton(
                onClick = { ioScope.launch { listState.animateScrollToItem(0) } },
            )
        }
    }

    if (filterOpen) {
        FilterSheet(
            sort = sort,
            sortDir = sortDir,
            sortEnabled = sortEnabled,
            selSources = selSources,
            sources = sources,
            genres = genres,
            allGenres = allGenres,
            type = type,
            layout = layout,
            columns = AppSettings.gridColumns.coerceIn(2, 6),
            cardStyle = cardStyle,
            onClose = { filterOpen = false },
            onSort = { newSort, newDir ->
                sort = newSort
                sortDir = newDir
            },
            onSources = { selSources = it },
            onGenres = { genres = it },
            onType = { type = it },
            onLayout = { newLayout ->
                AppSettings.listLayout =
                    if (newLayout == LayoutMode.Grid) "grid" else "list"
            },
            onColumns = { AppSettings.gridColumns = it.coerceIn(2, 6) },
            onCardStyle = { AppSettings.cardStyle = it },
        )
    }
    if (sourceSheetOpen) {
        AppBottomSheet(
            title = stringResource(R.string.lib_source_title),
            description = stringResource(R.string.lib_source_desc),
            onClose = { sourceSheetOpen = false },
        ) {
            SheetSection(title = stringResource(R.string.lib_source_title)) {
                SourcesOptions(
                    selected = selSources,
                    all = sources,
                    onToggle = {
                        selSources = if (it in selSources) selSources - it else selSources + it
                    },
                    onClearAll = { selSources = emptySet() },
                )
            }
        }
    }
    if (genresSheetOpen) {
        AppBottomSheet(
            title = stringResource(R.string.lib_genres_title),
            description = stringResource(R.string.lib_genres_desc),
            onClose = { genresSheetOpen = false },
        ) {
            SheetSection(title = stringResource(R.string.lib_genres_title)) {
                GenresOptions(selected = genres, all = allGenres) {
                    genres = if (it in genres) genres - it else genres + it
                }
            }
        }
    }
    if (sortSheetOpen && sortEnabled) {
        AppBottomSheet(
            title = stringResource(R.string.lib_sort_title),
            description = stringResource(R.string.lib_sort_desc),
            onClose = { sortSheetOpen = false },
        ) {
            SheetSection(title = stringResource(R.string.lib_section_sort)) {
                SortOptions(sort = sort, sortDir = sortDir) { option ->
                    if (option == sort) {
                        sortDir = sortDir.flip()
                    } else {
                        sort = option
                    }
                }
            }
        }
    }
    if (importOpen) {
        fun performImport(files: List<dev.mirutime.app.features.library.data.PickedImportFile>) {
            if (files.isEmpty() || importing) return
            importing = true
            MiruLog.event("library", "import-start", "files" to files.size)
            val wasReading = shelfId == "reading"
            ioScope.launch {
                try {
                    val summary = try {
                        provider.importFiles(files)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MiruLog.w("library", "Import failed", e)
                        null
                    }
                    if (summary == null) {
                        MiruLog.event("library", "import-result", "ok" to false)
                        android.widget.Toast.makeText(
                            context,
                            resources.getString(R.string.toast_import_failed),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        MiruLog.event(
                            "library",
                            "import-result",
                            "ok" to true,
                            "imported" to summary.imported,
                            "duplicates" to summary.duplicates,
                            "failures" to summary.failures,
                        )
                        lastRead = provider.lastRead()
                        shelfId = "reading"
                        reloadMeta()
                        // A shelf change already starts its own page load.
                        if (wasReading) refreshPages()
                        val count = summary.accepted
                        val message = if (count > 0) {
                            if (count == 1) {
                                resources.getString(R.string.toast_imported_one)
                            } else {
                                resources.getQuantityString(R.plurals.plural_local_books, count, count)
                            }
                        } else {
                            resources.getString(R.string.toast_import_failed)
                        }
                        android.widget.Toast.makeText(
                            context,
                            message,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                } finally {
                    importing = false
                    // Close only after the import + reload finish: the sheet
                    // owns the blocking progress state.
                    importOpen = false
                }
            }
        }
        ImportSheet(
            busy = importing,
            onClose = { if (!importing) importOpen = false },
            onImport = { file -> performImport(listOf(file)) },
            onImportUris = { files -> performImport(files) },
        )
    }
    if (shelfManagerOpen) {
        ShelfManagerSheet(
            shelves = shelves,
            summaries = shelfSummaries,
            onClose = { shelfManagerOpen = false },
            onSelectShelf = {
                shelfId = it
                shelfManagerOpen = false
            },
            onCreateShelf = { label, skip ->
                val base = label.trim().lowercase().replace(Regex("\\s+"), "-")
                var id = base
                var n = 2
                while (shelves.any { it.id == id }) {
                    id = "$base-$n"
                    n++
                }
                val shelf = Shelf(id = id, label = label.trim(), excludeFromUpdates = skip)
                shelves = shelves + shelf
                shelfId = shelf.id
                MiruLog.event("library", "shelf", "action" to "create", "shelf" to id)
                ioScope.launch {
                    provider.saveShelf(shelf)
                    reloadMeta()
                }
            },
            onRenameShelf = { shelf, label, skip ->
                val updated = shelf.copy(label = label.trim(), excludeFromUpdates = skip)
                shelves = shelves.map { if (it.id == shelf.id) updated else it }
                MiruLog.event("library", "shelf", "action" to "rename", "shelf" to shelf.id)
                ioScope.launch {
                    provider.saveShelf(updated)
                    reloadMeta()
                }
            },
            onDeleteShelf = { shelf ->
                val wasCurrent = shelfId == shelf.id
                shelves = shelves - shelf
                if (wasCurrent) shelfId = "reading"
                MiruLog.event("library", "shelf", "action" to "delete", "shelf" to shelf.id)
                ioScope.launch {
                    provider.deleteShelf(shelf.id)
                    reloadMeta()
                    if (!wasCurrent) refreshPages()
                }
            },
            onMoveShelf = { shelf, dir ->
                val head = shelves.filter { it.isSystemShelf() }
                val tail = shelves.filterNot { it.isSystemShelf() }.toMutableList()
                val from = tail.indexOf(shelf)
                val to = from + dir
                if (from >= 0 && to in tail.indices) {
                    tail.add(to, tail.removeAt(from))
                    val reordered = head + tail
                    shelves = reordered
                    ioScope.launch {
                        provider.reorderShelves(reordered)
                        reloadMeta()
                    }
                }
            },
        )
    }
    if (bulkDeleteOpen) {
        BulkDeleteSheet(
            selectedCount = selection.count,
            onClose = { bulkDeleteOpen = false },
            onConfirm = {
                bulkDeleteOpen = false
                runBulk(BulkAction.Delete) { urls ->
                    provider.deleteBooks(urls)
                }
            },
        )
    }
    if (bulkMoveOpen) {
        BulkMoveShelfSheet(
            shelves = shelves,
            selectedCount = selection.count,
            onClose = { bulkMoveOpen = false },
            onPick = { target ->
                bulkMoveOpen = false
                runBulk(BulkAction.Move) { provider.moveBooks(it, target) }
            },
        )
    }
}

// Loading skeleton in the final geometry: resume bar + cover grid slots, so
// first paint never jumps when the single data commit lands.
@Composable
private fun LibrarySkeleton(columns: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(MiruSpace.M),
            ) {
                SkeletonBox(width = 48.dp, height = 68.dp, corner = 6.dp)
                Spacer(modifier = Modifier.width(MiruSpace.M))
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBar(modifier = Modifier.fillMaxWidth(0.6f))
                    Spacer(modifier = Modifier.height(MiruSpace.S))
                    SkeletonBar(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        SkeletonCoverGrid(columns = columns)
    }
}

@Composable
private fun LibraryPageError(message: String, onRetry: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = miruCardSurface(),
        shadowElevation = miruCardElevation(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MiruSpace.M),
    ) {
        Column(
            modifier = Modifier.padding(MiruSpace.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.lib_retry))
            }
        }
    }
}

@Composable
private fun LibraryPagerFooter(loading: Boolean, error: Boolean, onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MiruSpace.M),
    ) {
        when {
            loading -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = LocalMiruExtraColors.current.accent,
                modifier = Modifier.size(18.dp),
            )
            error -> {
                Text(
                    text = stringResource(R.string.lib_page_more_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                )
                Spacer(modifier = Modifier.width(MiruSpace.S))
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.lib_retry))
                }
            }
        }
    }
}

@Composable
private fun SkeletonCoverGrid(columns: Int) {
    repeat(2) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MiruSpace.Xs),
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.Xs),
        ) {
            repeat(columns) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1 / 1.45f)
                        .clip(RoundedCornerShape(MiruRadius.Cover))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

// Resume card (prototype visuals: cover left, label ON the card, compact):
// 64x96 cover inside a 4dp frame, eyebrow + title + meta on the right, a 3dp
// progress track with "% complete", and a trailing circular Continue-to-point
// button. Roughly half the height of the old header + block + full-width
// button.
@Composable
private fun ResumeCard(
    book: LibraryBook,
    onOpen: () -> Unit,
    onRetry: (suspend () -> String?)? = null,
    reloadKey: Any? = null,
) {
    val title = book.translatedTitle ?: book.title
    // Progress counts finished chapters (the same metric as the grid cards);
    // the saved Continue position stays visible as its own meta segment.
    val total = book.totalChapters.coerceAtLeast(0)
    val read = book.readChapters.coerceIn(0, total)
    val progress = if (total > 0) read.toFloat() / total else 0f
    val percent = (progress * 100).roundToInt()
    MiruCard(
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.clickable(onClick = onOpen),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(MiruSpace.M),
        ) {
            BookCover(
                title = title,
                coverUrl = book.coverUrl,
                hasUpdateDot = book.hasUpdate,
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(1f / 1.45f)
                    .padding(CoverInset),
                corner = MiruRadius.Cover,
                onRetry = onRetry,
                reloadKey = reloadKey,
            )
            Spacer(modifier = Modifier.width(MiruSpace.M))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.lib_resume_eyebrow),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMiruExtraColors.current.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (book.progressChapter > 0) {
                        stringResource(
                            R.string.lib_resume_meta_chapter_fmt,
                            read,
                            total,
                            book.progressChapter,
                            book.sourceLabel,
                            book.lastReadLabel,
                        )
                    } else {
                        stringResource(
                            R.string.lib_resume_meta_fmt,
                            read,
                            total,
                            book.sourceLabel,
                            book.lastReadLabel,
                        )
                    },
                    // Small meta is never bare ≤14sp on the photo: it rides
                    // the TOP strip's zonal soft ink at 14sp (classic keeps
                    // the 10sp muted line verbatim).
                    style = if (LocalMiruOnImage.current) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodySmall
                    },
                    color = if (LocalMiruOnImage.current) {
                        miruPhotoMuted()
                    } else {
                        LocalMiruExtraColors.current.muted
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                CardProgressBar(
                    fraction = progress,
                    height = 3.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MiruSpace.S),
                )
                Text(
                    text = stringResource(R.string.lib_resume_complete_fmt, percent),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Spacer(modifier = Modifier.width(MiruSpace.S))
            MiruIconButton(
                icon = Icons.Filled.ArrowForward,
                contentDescription = stringResource(
                    R.string.lib_continue_desc_fmt,
                    book.continueLabel,
                ),
                tint = MaterialTheme.colorScheme.onBackground,
                onClick = onOpen,
            )
        }
    }
}

// One chip language with the shelf strip above (FilterChip everywhere).
@Composable
private fun MiniFilters(
    sources: Set<String>,
    genres: Set<String>,
    layout: LayoutMode,
    sort: LibrarySort,
    sortDir: SortDirection,
    sortEnabled: Boolean,
    onSource: () -> Unit,
    onGenres: () -> Unit,
    onLayout: () -> Unit,
    onSort: () -> Unit,
) {
    val allLabel = stringResource(R.string.lib_all)
    val sourceLabel = if (sources.isEmpty()) {
        allLabel
    } else {
        stringResource(R.string.lib_picked_fmt, sources.size)
    }
    val genresLabel = if (genres.isEmpty()) {
        allLabel
    } else {
        stringResource(R.string.lib_picked_fmt, genres.size)
    }
    val sortLabel = sort.label()
    val layoutLabel = if (layout == LayoutMode.Grid) {
        stringResource(R.string.lib_layout_grid)
    } else {
        stringResource(R.string.lib_layout_list)
    }
    val minis = listOfNotNull(
        stringResource(R.string.lib_chip_source_fmt, sourceLabel) to onSource,
        stringResource(R.string.lib_chip_genres_fmt, genresLabel) to onGenres,
        layoutLabel to onLayout,
        if (sortEnabled) {
            stringResource(
                R.string.lib_chip_sort_fmt,
                sortLabel,
                sortDir.arrow(),
            ) to onSort
        } else {
            null
        },
    )
    // Four separate pills straight on the photo — each chip draws its own
    // ghost backing, no shared box; classic chips keep their look. The row
    // itself adds NO horizontal padding: the LazyColumn item inset (16dp)
    // already puts chips on the same left grid line as the cards/resume bar,
    // giving a symmetric start→end rhythm. Side inset follows the same rule.
    ChipRail {
        items(minis, key = { it.first }) { (label, action) ->
            MiruFilterChip(
                selected = false,
                onClick = action,
                label = label,
            )
        }
    }
}

@Composable
private fun LibrarySort.label(): String = stringResource(
    when (this) {
        LibrarySort.LastRead -> R.string.lib_sort_last_read
        LibrarySort.Title -> R.string.lib_sort_name
        LibrarySort.Unread -> R.string.lib_sort_unread
        LibrarySort.LastUpdate -> R.string.lib_sort_updated
        LibrarySort.Added -> R.string.lib_sort_added
    },
)

private fun SortDirection.arrow(): String = when (this) {
    SortDirection.Asc -> "↑"
    SortDirection.Desc -> "↓"
}

private fun SortDirection.flip(): SortDirection = when (this) {
    SortDirection.Asc -> SortDirection.Desc
    SortDirection.Desc -> SortDirection.Asc
}

// Big filter sheet. Filters (sort, sources, genres, type) stay a draft until
// Apply; appearance settings (layout, columns, card style) apply immediately
// (FR-035 / 6.3). Close discards the filter draft.
@Composable
private fun FilterSheet(
    sort: LibrarySort,
    sortDir: SortDirection,
    sortEnabled: Boolean,
    selSources: Set<String>,
    sources: List<String>,
    genres: Set<String>,
    allGenres: List<String>,
    type: ContentType?,
    layout: LayoutMode,
    columns: Int,
    cardStyle: CardStyle,
    onClose: () -> Unit,
    onSort: (LibrarySort, SortDirection) -> Unit,
    onSources: (Set<String>) -> Unit,
    onGenres: (Set<String>) -> Unit,
    onType: (ContentType?) -> Unit,
    onLayout: (LayoutMode) -> Unit,
    onColumns: (Int) -> Unit,
    onCardStyle: (CardStyle) -> Unit,
) {
    var draftSort by remember { mutableStateOf(sort) }
    var draftSortDir by remember { mutableStateOf(sortDir) }
    var draftSources by remember { mutableStateOf(selSources) }
    var draftGenres by remember { mutableStateOf(genres) }
    var draftType by remember { mutableStateOf(type) }
    AppBottomSheet(
        title = stringResource(R.string.lib_filter_title),
        description = stringResource(R.string.lib_filter_desc),
        onClose = onClose,
        headerAction = {
            SheetHeaderAction(
                label = stringResource(R.string.lib_filter_reset),
                enabled = draftSources.isNotEmpty() || draftGenres.isNotEmpty() || draftType != null,
                onClick = {
                    draftSources = emptySet()
                    draftGenres = emptySet()
                    draftType = null
                },
            )
        },
        doneLabel = stringResource(R.string.finder_apply),
        onDone = {
            onSort(draftSort, draftSortDir)
            onSources(draftSources)
            onGenres(draftGenres)
            onType(draftType)
            onClose()
        },
        // 55% body keeps the visible surface (handle + margins + 200% footer)
        // at/under 70%; content still scrolls.
        maxHeightFraction = 0.55f,
    ) {
        if (sortEnabled) {
            SheetSection(title = stringResource(R.string.lib_section_sort)) {
                SortOptions(sort = draftSort, sortDir = draftSortDir) { option ->
                    if (option == draftSort) {
                        draftSortDir = draftSortDir.flip()
                    } else {
                        draftSort = option
                    }
                }
            }
        }
        SheetSection(title = stringResource(R.string.lib_section_source), initiallyExpanded = false) {
            SourcesOptions(
                selected = draftSources,
                all = sources,
                onToggle = {
                    draftSources = if (it in draftSources) draftSources - it else draftSources + it
                },
                onClearAll = { draftSources = emptySet() },
            )
        }
        SheetSection(title = stringResource(R.string.lib_section_genres), initiallyExpanded = false) {
            GenresOptions(selected = draftGenres, all = allGenres) {
                draftGenres = if (it in draftGenres) draftGenres - it else draftGenres + it
            }
        }
        SheetSection(title = stringResource(R.string.lib_section_type), initiallyExpanded = false) {
            TypeOptions(type = draftType) { draftType = it }
        }
        SheetSection(title = stringResource(R.string.lib_section_grid), initiallyExpanded = false) {
            Text(
                text = stringResource(R.string.lib_columns_fmt, columns),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MiruSlider(
                value = columns.toFloat(),
                onValueChange = { onColumns(it.roundToInt().coerceIn(2, 6)) },
                valueRange = 2f..6f,
                steps = 3,
                contentDescription = stringResource(R.string.lib_columns_fmt, columns),
            )
            LayoutOptions(layout = layout) {
                onLayout(it)
                // C lives in list mode only: entering List pins C, leaving
                // List restores a grid card (never C as a grid card).
                onCardStyle(
                    if (it == LayoutMode.List) {
                        CardStyle.Horizontal
                    } else if (cardStyle == CardStyle.Horizontal) {
                        CardStyle.Mat
                    } else {
                        cardStyle
                    },
                )
            }
        }
        SheetSection(title = stringResource(R.string.lib_section_cards), initiallyExpanded = false) {
            if (layout == LayoutMode.Grid) {
                CardStylePicker(
                    current = if (cardStyle == CardStyle.Horizontal) CardStyle.Mat else cardStyle,
                    onSelect = onCardStyle,
                )
            } else {
                Text(
                    text = stringResource(R.string.lib_list_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalMiruExtraColors.current.muted,
                )
            }
        }
    }
}

// Sort options use the same selected chip language as Grid/CARDS: the
// chosen chip is accent-filled and carries the direction arrow; tapping it
// again flips direction. No separate check mark or lookalike rows.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortOptions(
    sort: LibrarySort,
    sortDir: SortDirection,
    onSort: (LibrarySort) -> Unit,
) {
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
            verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
        ) {
            LibrarySort.entries.forEach { option ->
                val selected = sort == option
                MiruFilterChip(
                    selected = selected,
                    onClick = { onSort(option) },
                    label = option.label() + if (selected) " ${sortDir.arrow()}" else "",
                )
            }
        }
        Text(
            text = stringResource(R.string.lib_tap_flip),
            style = MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
            modifier = Modifier.padding(top = MiruSpace.S),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenresOptions(
    selected: Set<String>,
    all: List<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
    ) {
        all.forEach { genre ->
            MiruFilterChip(
                selected = genre in selected,
                onClick = { onToggle(genre) },
                label = genre,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesOptions(
    selected: Set<String>,
    all: List<String>,
    onToggle: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
    ) {
        MiruFilterChip(
            selected = selected.isEmpty(),
            onClick = onClearAll,
            label = stringResource(R.string.lib_all),
        )
        all.forEach { source ->
            MiruFilterChip(
                selected = source in selected,
                onClick = { onToggle(source) },
                label = source,
            )
        }
    }
}

// Single-select sets speak chips (the app's one chip language), not boxes.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeOptions(type: ContentType?, onType: (ContentType?) -> Unit) {
    val options = listOf(null) + ContentType.entries
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
    ) {
        options.forEach { option ->
            MiruFilterChip(
                selected = type == option,
                onClick = { onType(option) },
                label = option?.typeLabel() ?: stringResource(R.string.lib_all_types),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutOptions(layout: LayoutMode, onLayout: (LayoutMode) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.S),
    ) {
        LayoutMode.entries.forEach { option ->
            MiruFilterChip(
                selected = layout == option,
                onClick = { onLayout(option) },
                label = option.layoutLabel(),
            )
        }
    }
}

@Composable
private fun ContentType.typeLabel(): String = stringResource(
    when (this) {
        ContentType.Novel -> R.string.type_novel
        ContentType.Manga -> R.string.type_manga
        ContentType.Video -> R.string.type_video
    },
)

@Composable
private fun LayoutMode.layoutLabel(): String = stringResource(
    when (this) {
        LayoutMode.Grid -> R.string.lib_layout_grid
        LayoutMode.List -> R.string.lib_layout_list
    },
)

// Floating "back to top" control: the shared 48dp hit box / 40dp plate.
@Composable
private fun ScrollToTopButton(onClick: () -> Unit) {
    MiruRoundIconButton(
        icon = Icons.Filled.KeyboardArrowUp,
        contentDescription = stringResource(R.string.lib_cd_scroll_top),
        onClick = onClick,
    )
}

// shortcut: LazyListState exposes no pixel scroll position, so "more than one
// viewport" is approximated by the number of items that fit on screen.
internal fun shouldShowScrollToTop(
    firstVisibleItemIndex: Int,
    visibleItemCount: Int,
): Boolean = visibleItemCount > 0 && firstVisibleItemIndex >= visibleItemCount

// A pinned sticky header is prepended to visibleItemsInfo. Count only rows
// from firstVisibleItemIndex on, otherwise the header row inflates the
// viewport size and the threshold is never reached.
internal fun scrollingItemCount(
    visibleIndices: List<Int>,
    firstVisibleItemIndex: Int,
): Int = visibleIndices.count { it >= firstVisibleItemIndex }

