@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.mirutime.app.features.finder.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.core.catalogBookKey
import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.core.PluginRequestException
import dev.mirutime.uikit.CoverInset
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.app.design.components.BookCover
import dev.mirutime.app.design.components.PluginErrorState
import dev.mirutime.app.design.components.toMiruBadge
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruContentKind
import dev.mirutime.uikit.MiruRoundIconButton
import dev.mirutime.uikit.MiruSearchBar
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.finder.data.CatalogCacheKey
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.data.FinderSearchCache
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.dedupeCatalogBooks
import dev.mirutime.app.features.finder.domain.setMembership
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.shouldShowSearchLanguageHint
import dev.mirutime.app.features.finder.domain.isWebUrl
import dev.mirutime.app.features.finder.domain.pluginActiveFilterCount
import dev.mirutime.app.features.finder.domain.SearchHistoryRules
import dev.mirutime.app.di.ReaderGraph
import dev.mirutime.app.features.library.domain.CardStyle
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.library.domain.LibraryBook
import dev.mirutime.app.features.library.ui.LibraryCard
import dev.mirutime.app.features.translation.data.TitleTranslationJob
import dev.mirutime.app.features.translation.data.metadataConfigFor
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

// Catalogue of ONE source: a 2-column grid with
// auto-pagination, a search bar on top that opens the single search+filters
// sheet, and the three footers (spinner / no results / no more) plus the
// error state (Reload / Copy). Long-press on a tile adds or removes the book
// from the library with a toast; the cover badge shows what is already there.
@Composable
fun SourceCatalogScreen(
    sourceId: String,
    contentPadding: PaddingValues,
    provider: FinderDataProvider,
    readerGraph: ReaderGraph,
    // Installed plugin display name for the report; null means id only.
    sourceName: String? = null,
    onBookOpen: (String) -> Unit = {},
    // Opens the installed plugin's website in the in-app browser.
    onSiteOpen: (String) -> Unit = {},
    // Fired once per committed membership write so the shell can drop cached
    // snapshots and bump its data revision (library counts/badges).
    onLibraryChanged: () -> Unit = {},
    // Query carried over from the global search ("open this catalogue").
    initialQuery: String = "",
    // Solve a CF challenge for the request that failed, then retry. Supplied
    // by the shell (it owns the solver); null hides the action.
    onSolveCf: (suspend (PluginError.CfChallengeRequired) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val isLocal = LocalSources.isLocal(sourceId)
    val siteUrl = if (isLocal) "" else provider.baseUrl(sourceId).trim()
    // Plugin genres are meaningless for a folder scan: a local source offers
    // its real formats only. Plugin sources have no app-owned genre list at
    // all — their filter chips come from the plugin schema.
    val genreOptions = remember(sourceId) {
        if (isLocal) localCatalogFormats(sourceId) else emptyList()
    }

    // The text field stays instant (`input`); `filters.query` is the APPLIED
    // query and only moves after the debounce window.
    // Applied filters survive the book round-trip (rememberSaveable is restored
    // per back-stack entry), so returning keeps the filtered results instead of
    // resetting to the default listing (FR-077).
    var filters by rememberSaveable(sourceId, initialQuery, stateSaver = CatalogFiltersSaver) {
        mutableStateOf(CatalogFilters(query = initialQuery))
    }
    // The field follows the applied/restored query, so returning from a book
    // shows the typed query that produced the results, not just the route query.
    var input by rememberSaveable(sourceId, initialQuery) { mutableStateOf(filters.query) }
    val debouncer = remember(sourceId, initialQuery) {
        CatalogQueryDebouncer(
            scope = scope,
            onApply = { text ->
                if (filters.query != text) filters = filters.copy(query = text)
            },
        )
    }
    // Leaving the screen drops a pending typed query: no late provider call.
    DisposableEffect(debouncer) {
        onDispose { debouncer.cancel() }
    }
    // A local catalogue is a live folder scan, never a cached answer: rebuilding
    // the screen (return from a book) or an explicit refresh rescans the tree.
    // Plugin catalogues keep the snapshot so backing out of a book is instant.
    val cacheKey = remember(sourceId, filters) { CatalogCacheKey(sourceId, filters) }
    val cached = remember(cacheKey) {
        if (LocalSources.isLocal(sourceId)) null else FinderSearchCache.catalog(cacheKey)
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = cached?.scrollIndex ?: 0,
        initialFirstVisibleItemScrollOffset = cached?.scrollOffset ?: 0,
    )
    var books by remember(sourceId) { mutableStateOf(cached?.books ?: emptyList()) }
    var pagesLoaded by remember(sourceId) { mutableIntStateOf(cached?.pagesLoaded ?: 0) }
    var hasMore by remember(sourceId) { mutableStateOf(cached?.hasMore ?: true) }
    var loading by remember(sourceId) { mutableStateOf(cached == null) }
    var error by remember(sourceId) { mutableStateOf<String?>(null) }
    var pluginFailure by remember(sourceId) { mutableStateOf<PluginRequestException?>(null) }
    var reloadTick by remember(sourceId) { mutableIntStateOf(0) }
    // Bumped on every restart so a late page of the OLD query is dropped.
    var generation by remember(sourceId) { mutableIntStateOf(0) }
    var sheetOpen by remember(sourceId) { mutableStateOf(false) }
    var foldersOpen by remember(sourceId) { mutableStateOf(false) }
    var history by remember(sourceId) { mutableStateOf(AppSettings.searchHistoryFor(sourceId)) }
    var presets by remember(sourceId) { mutableStateOf(provider.presets(sourceId)) }
    // Plugin-declared filters are loaded ONCE per source (the provider caches
    // and invalidates on install/reset); a source change resets all of this.
    var schema by remember(sourceId) { mutableStateOf(emptyList<PluginFilter>()) }
    var schemaLoading by remember(sourceId) { mutableStateOf(!isLocal) }
    var schemaError by remember(sourceId) { mutableStateOf<String?>(null) }
    var schemaTick by remember(sourceId) { mutableIntStateOf(0) }
    // Plugin-declared search_filters capability + language for the sheet hint.
    var searchFiltersSupported by remember(sourceId) { mutableStateOf(false) }
    var sourceLanguage by remember(sourceId) { mutableStateOf<String?>(null) }
    // Per-URL in-flight guard: a second long-press while the write runs would
    // race the same row, so it is ignored until the first one settles.
    var membershipPending by remember(sourceId) { mutableStateOf<Set<String>>(emptySet()) }
    // Catalogue rows are not persisted: a repaired cover URL lives here so a
    // disposed tile reopens with the fixed art instead of the failed one.
    val coverFixes = remember(sourceId) { mutableStateMapOf<String, String>() }
    // Persist the snapshot (incl. scroll) when the screen leaves composition.
    DisposableEffect(cacheKey) {
        onDispose {
            if (!LocalSources.isLocal(sourceId) && books.isNotEmpty()) {
                FinderSearchCache.putCatalog(
                    cacheKey,
                    FinderSearchCache.CatalogSnapshot(
                        books = books,
                        pagesLoaded = pagesLoaded,
                        hasMore = hasMore,
                        scrollIndex = gridState.firstVisibleItemIndex,
                        scrollOffset = gridState.firstVisibleItemScrollOffset,
                        storedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    LaunchedEffect(sourceId) {
        searchFiltersSupported = provider.supportsSearchFilters(sourceId)
        sourceLanguage = provider.sourceLanguage(sourceId)
    }

    LaunchedEffect(sourceId, schemaTick) {
        if (isLocal) {
            schema = emptyList()
            schemaLoading = false
            schemaError = null
            return@LaunchedEffect
        }
        schemaLoading = true
        schemaError = null
        try {
            when (val result = provider.filterSchema(sourceId)) {
                is Outcome.Ok -> {
                    schema = result.value
                    // The schema is known now: re-read presets so a stale key
                    // is dropped and the summary counts plugin filters honestly.
                    presets = provider.presets(sourceId)
                }
                is Outcome.Err -> schemaError = filterSchemaErrorMessage(
                    result.error,
                    sourceId,
                    sourceName,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            schemaError = e.message ?: e.toString()
        }
        schemaLoading = false
    }

    // First page: a new query/filters/reload restarts the list; a cache hit
    // (back from a book) keeps the seeded page and skips the fetch. Local
    // sources always take the fetch branch.
    LaunchedEffect(sourceId, filters, reloadTick) {
        if (!LocalSources.isLocal(sourceId) &&
            reloadTick == 0 &&
            FinderSearchCache.catalog(cacheKey)?.books?.isNotEmpty() == true
        ) {
            // Restored from cache (back from a book): the list is already on
            // screen. Clear the flag too — a superseded loadMore may have left
            // it set, which made the next filter request spin forever.
            loading = false
            return@LaunchedEffect
        }
        generation++
        loading = true
        error = null
        pluginFailure = null
        books = emptyList()
        hasMore = true
        pagesLoaded = 0
        try {
            val result = provider.page(sourceId, filters, 1)
            books = dedupeCatalogBooks(result.books)
            hasMore = result.hasMore
            pagesLoaded = 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: PluginRequestException) {
            pluginFailure = e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        loading = false
    }

    // Lazy backfill for entries the listing returned without any cover, the
    // NoveLA pattern: one source call per item, two at a time and staggered,
    // with the result landing in the reactive map read inside the tile. The
    // workers belong to the query generation, not to the list object: paging
    // and membership flips must not cancel them.
    val coverDone = remember(sourceId, filters, reloadTick) { mutableSetOf<String>() }
    val coverInflight = remember(sourceId, filters, reloadTick) { mutableSetOf<String>() }
    LaunchedEffect(sourceId, filters, reloadTick) {
        if (isLocal) return@LaunchedEffect
        val gate = Semaphore(2)
        snapshotFlow { books }.collect { list ->
            val urls = list.mapTo(HashSet()) { it.bookUrl }
            // A query change must forget both the repairs and the "already
            // asked" marks of URLs that left the result set.
            coverDone.retainAll(urls)
            coverFixes.keys.retainAll(urls)
            list.forEach { book ->
                val url = book.bookUrl
                if (!book.coverUrl.isNullOrBlank()) return@forEach
                if (url in coverDone || !coverInflight.add(url)) return@forEach
                launch {
                    try {
                        gate.withPermit {
                            delay(Random.nextLong(300L, 501L))
                            provider.refreshCatalogCover(sourceId, url)?.let { fresh ->
                                coverFixes[url] = fresh
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A source hiccup is not a reason to kill the batch.
                    } finally {
                        coverInflight.remove(url)
                        // Success and failure both count as done: a URL that
                        // yields no cover must not be re-fetched on every page.
                        coverDone.add(url)
                    }
                }
            }
        }
    }

    // Explicit target, optimistic flag, no page reload: the card flips now and
    // is reverted only when the data layer reports a real failure.
    fun toggleMembership(book: CatalogBook) {
        val key = book.bookKey
        if (key in membershipPending) return
        val target = !book.inLibrary
        membershipPending = membershipPending + key
        books = books.setMembership(key, target)
        scope.launch {
            val result = try {
                provider.setInLibrary(key, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MembershipResult(success = false)
            }
            membershipPending = membershipPending - key
            if (result.success) {
                books = books.map {
                    if (it.bookKey != key) {
                        it
                    } else {
                        // The optimistic setMembership already carries the
                        // committed inLibrary/badge; a local add only clears
                        // the "not imported" mark.
                        it.copy(notImported = if (isLocal && target) false else it.notImported)
                    }
                }
                val message = when {
                    result.fileCleanupFailed -> R.string.book_library_cleanup_failed
                    target -> R.string.book_added_toast
                    else -> R.string.book_removed_toast
                }
                onLibraryChanged()
                Toast.makeText(context, resources.getString(message), Toast.LENGTH_SHORT).show()
            } else {
                books = books.setMembership(key, !target)
                Toast.makeText(
                    context,
                    resources.getString(R.string.book_library_update_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val loadMore: () -> Unit = {
        if (!loading && error == null && pluginFailure == null && hasMore && pagesLoaded > 0) {
            val mine = generation
            val next = pagesLoaded + 1
            loading = true
            scope.launch {
                try {
                    val result = provider.page(sourceId, filters, next)
                    if (mine == generation) {
                        books = dedupeCatalogBooks(books + result.books)
                        hasMore = result.hasMore
                        pagesLoaded = next
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PluginRequestException) {
                    if (mine == generation) pluginFailure = e
                } catch (e: Exception) {
                    if (mine == generation) error = e.message ?: e.toString()
                }
                if (mine == generation) loading = false
            }
        }
    }

    // One retry path for both a typed plugin failure and a generic error.
    fun retryList() {
        error = null
        pluginFailure = null
        debouncer.cancel()
        if (input != filters.query) {
            // Flush a typed query: restart the list on it.
            filters = filters.copy(query = input)
        } else if (books.isEmpty()) {
            reloadTick++
        } else {
            loadMore()
        }
    }

    // Auto-pagination: close to the end of the grid -> next page.
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, books.size, loading, error, pluginFailure) {
        if (nearEnd && books.isNotEmpty()) loadMore()
    }

    // One shared card style with the library/dashboard; C is list-only.
    val catalogStyle = AppSettings.cardStyle
        .takeIf { it != CardStyle.Horizontal } ?: CardStyle.Mat
    // Translated titles: the catalogue is where a NEW book is judged, so it
    // warms the same metadata cache as the library instead of leaving every
    // card in the source language. Same batch-per-config contract.
    val metadata by readerGraph.metadataTranslations.state.collectAsState()
    val metadataJobs = remember(
        books,
        AppSettings.novelTranslation.toMap(),
        AppSettings.pluginTranslation.toMap(),
        AppSettings.translationGlobalMode,
        AppSettings.globalSource,
        AppSettings.globalTarget,
        AppSettings.provider,
        AppSettings.activePrompt,
    ) {
        books.mapNotNull { book ->
            metadataConfigFor(book.bookUrl)?.let { config ->
                TitleTranslationJob(book.bookUrl, book.title, config)
            }
        }
    }
    LaunchedEffect(metadataJobs) {
        readerGraph.metadataTranslations.hydrateTitles(metadataJobs)
        readerGraph.metadataTranslations.submitTitles(metadataJobs)
    }
    val metadataConfigs = remember(metadataJobs) {
        metadataJobs.associate { it.bookUrl to it.config }
    }
    val translatedTitles = remember(books, metadata, metadataConfigs) {
        books.mapNotNull { book ->
            metadata[book.bookUrl]
                ?.takeIf { it.config == metadataConfigs[book.bookUrl] }
                ?.title
                ?.let { book.bookUrl to it }
        }.toMap()
    }
    val fontScale = LocalDensity.current.fontScale
    val columns = if (fontScale >= 1.3f) 2 else AppSettings.gridColumns.coerceIn(2, 6)
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MiruSize.ScreenPad,
            end = MiruSize.ScreenPad,
            top = contentPadding.calculateTopPadding() + MiruSpace.Xs,
            bottom = contentPadding.calculateBottomPadding() + MiruSpace.L,
        ),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
        verticalArrangement = Arrangement.spacedBy(MiruSpace.CardGap),
    ) {
        item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MiruSpace.S),
                ) {
                    MiruSearchBar(
                        value = input,
                        onValueChange = { text ->
                            if (text.isEmpty()) {
                                // Clear button (and backspace-to-empty): apply now.
                                input = ""
                                debouncer.applyNow("")
                            } else {
                                input = text
                                debouncer.onInput(text, filters.query)
                            }
                        },
                        onClear = {
                            input = ""
                            debouncer.applyNow("")
                        },
                        placeholder = stringResource(R.string.finder_search_hint),
                        // IME Search is an explicit submit: apply now, drop timer.
                        onSearch = { debouncer.applyNow(input) },
                        modifier = Modifier.weight(1f),
                    )
                    MiruRoundIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.finder_cd_filters),
                        onClick = { sheetOpen = true },
                        badgeCount = if (isLocal) {
                            filters.activeFilterCount
                        } else {
                            filters.pluginActiveFilterCount(schema)
                        },
                    )
                    // Local: refresh the live scan or open folder management.
                    if (isLocal) {
                        MiruRoundIconButton(
                            icon = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.finder_local_refresh_cd),
                            onClick = { reloadTick++ },
                        )
                        MiruRoundIconButton(
                            icon = Icons.Filled.Folder,
                            contentDescription = stringResource(R.string.finder_local_manage),
                            onClick = { foldersOpen = true },
                        )
                    } else if (siteUrl.isNotBlank()) {
                        MiruRoundIconButton(
                            icon = Icons.Outlined.Public,
                            contentDescription = stringResource(R.string.webview_open_site),
                            onClick = { onSiteOpen(siteUrl) },
                        )
                    }
                }
                // 69shuba searches GBK: the user must type Chinese.
                if (shouldShowSearchLanguageHint(sourceLanguage, input)) {
                    Text(
                        text = stringResource(R.string.finder_search_language_hint_zh),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMiruExtraColors.current.muted,
                        modifier = Modifier.padding(top = MiruSpace.Xs),
                    )
                }
            }
        }
        items(books, key = { catalogBookKey(it.bookUrl) }) { book ->
            val shown = coverFixes[book.bookUrl]?.let { book.copy(coverUrl = it) } ?: book
            if (isLocal) {
                LocalCatalogCard(
                    book = shown,
                    importing = false,
                    error = null,
                    onClick = { onBookOpen(book.bookKey) },
                    onLongClick = { toggleMembership(book) },
                    inLibrary = book.inLibrary,
                    kind = if (LocalSources.kindOf(sourceId) == "manga") {
                        MiruContentKind.Manga
                    } else {
                        MiruContentKind.Novel
                    },
                )
            } else {
                LibraryCard(
                    book = shown.toLibraryBook(
                        source = sourceId,
                        translatedTitle = translatedTitles[book.bookUrl],
                    ),
                    style = catalogStyle,
                    compactInfo = true,
                    showTypeChip = false,
                    onOpen = { onBookOpen(book.bookKey) },
                    onLongPress = { toggleMembership(book) },
                    membership = book.badge?.toMiruBadge(),
                    onRetry = {
                        provider.refreshCatalogCover(sourceId, book.bookUrl)?.also { fresh ->
                            coverFixes[book.bookUrl] = fresh
                        }
                    },
                    reloadKey = reloadTick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
            val failure = pluginFailure
            val message = error
            when {
                failure != null -> {
                    val report = failure.toReport(identity = siteUrl.ifBlank { sourceId })
                    val challenge = failure.error as? PluginError.CfChallengeRequired
                    val solve = onSolveCf
                    PluginErrorState(
                        title = report.title ?: stringResource(R.string.finder_error_title),
                        message = report.message.orEmpty(),
                        detailText = report.detailText,
                        reportText = report.reportText,
                        authUrl = report.authUrl,
                        authLabel = report.text.authHostPath,
                        onRetry = ::retryList,
                        onOpenAuth = report.authUrl
                            ?.takeIf(::isWebUrl)
                            ?.let { url -> { _: String -> onSiteOpen(url) } },
                        onSolveCf = if (challenge != null && solve != null) {
                            {
                                scope.launch {
                                    if (solve(challenge)) retryList()
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
                message != null -> ErrorCard(
                    message = message,
                    onReload = ::retryList,
                    onCopy = {
                        clipboard.setText(AnnotatedString(message))
                        Toast.makeText(
                            context,
                            resources.getString(R.string.finder_error_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (books.isEmpty()) 240.dp else 56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = LocalMiruExtraColors.current.accent,
                        modifier = Modifier.size(28.dp),
                    )
                }
                books.isEmpty() -> if (isLocal && filters == CatalogFilters() && input.isBlank()) {
                    LocalCatalogEmptyState(onManageFolders = { foldersOpen = true })
                } else {
                    EmptyState(
                        canReset = filters != CatalogFilters(),
                        onReset = {
                            debouncer.cancel()
                            input = ""
                            filters = CatalogFilters()
                        },
                    )
                }
                !hasMore -> Text(
                    text = stringResource(R.string.finder_no_more),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MiruSpace.L),
                )
            }
        }
    }

    if (sheetOpen) {
        CatalogFilterSheet(
            // Seed the draft with the live text, not the not-yet-applied query,
            // so opening the sheet inside the debounce window keeps the typing.
            applied = filters.copy(query = input),
            // Local files have no plugin genres: offer the real formats only.
            genres = genreOptions,
            local = isLocal,
            schema = schema,
            schemaLoading = schemaLoading,
            schemaError = schemaError,
            searchFiltersSupported = searchFiltersSupported,
            language = sourceLanguage,
            onRetrySchema = { schemaTick++ },
            history = history,
            onRemoveHistory = { entry ->
                history = SearchHistoryRules.remove(history, entry)
                AppSettings.setSearchHistory(sourceId, history)
            },
            onClearHistory = {
                history = emptyList()
                AppSettings.setSearchHistory(sourceId, history)
            },
            presets = presets,
            onSavePreset = { preset ->
                provider.savePreset(sourceId, preset)
                presets = provider.presets(sourceId)
            },
            onDeletePreset = { name ->
                provider.deletePreset(sourceId, name)
                presets = provider.presets(sourceId)
            },
            onApply = { draft ->
                // The sheet stays open; Close dismisses it (immediate filters).
                // A local source only understands format values; a preset or
                // an old plugin genre must not survive into an empty result.
                val applied = if (isLocal) {
                    draft.copy(genres = draft.genres.filter { it in genreOptions }.toSet())
                } else {
                    // Plugin sources have no fixed app genres/status; the sheet
                    // already wrote only schema-declared plugin values. A
                    // schema that finished loading empty clears stale preset
                    // values; while it is still loading they are preserved.
                    draft.copy(
                        genres = emptySet(),
                        ongoingOnly = false,
                        pluginValues = if (!schemaLoading && schemaError == null && schema.isEmpty()) {
                            emptyMap()
                        } else {
                            draft.pluginValues
                        },
                    )
                }
                if (applied.query != filters.query) {
                    history = SearchHistoryRules.push(history, applied.query)
                    AppSettings.setSearchHistory(sourceId, history)
                }
                // Apply is explicit: no debounce, even for the sheet's query.
                debouncer.cancel()
                input = applied.query
                if (filters != applied) filters = applied
            },
            onClose = { sheetOpen = false },
        )
    }

    if (isLocal && foldersOpen) {
        LocalFoldersSheet(
            kind = LocalSources.kindOf(sourceId),
            provider = provider,
            onClose = { foldersOpen = false },
            // Every folder change is a scan change: reload the live catalogue.
            onCatalogChanged = { reloadTick++ },
        )
    }
}

// Catalogue tile: 2:3 cover with an "In library" badge, 2-line title, one
// meta line (gold star + rating when the plugin has one, chapter count).
// Catalog entries carry only the listing fields; the shared library card
// takes the rest as neutral defaults (shelfId = "catalog", no reading state).
private fun CatalogBook.toLibraryBook(
    source: String,
    translatedTitle: String? = null,
): LibraryBook = LibraryBook(
    bookUrl = bookUrl,
    title = title,
    translatedTitle = translatedTitle,
    source = source,
    author = source,
    rating = rating,
    contentType = ContentType.Novel,
    totalChapters = chapters,
    readChapters = 0,
    hasUpdate = false,
    shelfId = "catalog",
    genres = genres,
    lastReadRank = 0,
    lastUpdateRank = 0,
    addedRank = 0,
    lastReadLabel = "",
    continueLabel = "",
    coverUrl = coverUrl,
)

@Composable
private fun EmptyState(canReset: Boolean, onReset: () -> Unit) {
    MiruCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.finder_no_results),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (canReset) {
            Spacer(modifier = Modifier.height(MiruSpace.M))
            Button(
                onClick = onReset,
                modifier = Modifier.height(MiruSize.Button),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            ) {
                Text(text = stringResource(R.string.finder_reset_filters))
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onReload: () -> Unit, onCopy: () -> Unit) {
    MiruCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.finder_error_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = LocalMiruExtraColors.current.muted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MiruSpace.Xs),
        )
        Spacer(modifier = Modifier.height(MiruSpace.M))
        Row(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
            val colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
            )
            Button(
                onClick = onReload,
                colors = colors,
                modifier = Modifier
                    .weight(1f)
                    .height(MiruSize.Button),
            ) {
                Text(text = stringResource(R.string.finder_error_reload), maxLines = 1)
            }
            Button(
                onClick = onCopy,
                colors = colors,
                modifier = Modifier
                    .weight(1f)
                    .height(MiruSize.Button),
            ) {
                Text(text = stringResource(R.string.finder_error_copy), maxLines = 1)
            }
        }
    }
}

// Filter-schema failures are technical diagnostics shown inside the sheet
// under the localized "could not load filters" title; the shared builder names
// the source, the operation and scheme+host+path, never the query.
private fun filterSchemaErrorMessage(
    error: MiruError,
    sourceId: String,
    sourceName: String? = null,
): String =
    buildPluginErrorReport(
        error = error as? PluginError,
        sourceId = sourceId,
        sourceName = sourceName,
        operation = "filters",
    ).detailText
