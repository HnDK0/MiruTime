package dev.mirutime.app.features.finder.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.app.R
import dev.mirutime.app.core.PluginErrorReport
import dev.mirutime.app.core.PluginRequestException
import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.core.globalBookKey
import dev.mirutime.app.core.globalSourceKey
import dev.mirutime.app.design.components.CatalogSearchField
import dev.mirutime.app.design.components.CatalogTile
import dev.mirutime.app.design.components.PluginErrorState
import dev.mirutime.app.design.components.toMiruBadge
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruFilterChip
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.MiruSectionCard
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.finder.data.FinderDataProvider
import dev.mirutime.app.features.finder.data.FinderSearchCache
import dev.mirutime.app.features.finder.data.SourceResult
import dev.mirutime.app.features.finder.data.dedupeSourceResults
import dev.mirutime.app.features.finder.data.resumeGlobalSearch
import dev.mirutime.app.features.finder.data.searchCacheKey
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.finder.domain.dedupeCatalogBooks
import dev.mirutime.app.features.finder.domain.SearchHistoryRules
import dev.mirutime.app.features.finder.domain.setMembership
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.library.domain.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Global search: ONE query fanned out to every installed
// + enabled source (optionally narrowed by a type chip). Answers arrive one by
// one, each source gets its own section card with a row of tiles and a
// shortcut into that source's catalogue with the same query; failed sources
// are summarised in one line, not as noise per source. History: max 5, shared
// rules with the per-source one.
@Composable
fun GlobalSearchScreen(
    contentPadding: PaddingValues,
    provider: FinderDataProvider,
    onBookOpen: (String) -> Unit = {},
    onOpenCatalogue: (sourceId: String, query: String) -> Unit = { _, _ -> },
    // Fired once per committed membership write so the shell can drop cached
    // snapshots and bump its data revision (library counts/badges).
    onLibraryChanged: () -> Unit = {},
    // Optional pre-filled query (e.g. tapping a book title searches its ORIGINAL
    // name). A non-blank value auto-starts the fan-out (FR-115/FR-116).
    initialQuery: String = "",
) {
    var field by rememberSaveable { mutableStateOf(initialQuery) }
    var submitted by rememberSaveable {
        mutableStateOf(initialQuery.takeIf { it.isNotBlank() })
    }
    var scopeType by rememberSaveable { mutableStateOf<String?>(null) }
    // Results survive navigation: the snapshot for this query/scope seeds the
    // state, so backing out of a book never re-runs the whole fan-out.
    val cacheKey = searchCacheKey(submitted, scopeType)
    val cached = remember(cacheKey) { FinderSearchCache.global(cacheKey) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = cached?.scrollIndex ?: 0,
        initialFirstVisibleItemScrollOffset = cached?.scrollOffset ?: 0,
    )
    var sources by remember { mutableStateOf<List<CatalogSource>?>(null) }
    // Keyed by cacheKey so a query/scope switch reseeds instead of inheriting
    // the previous results. A finished empty snapshot is not seeded at all.
    var results by remember(cacheKey) {
        mutableStateOf(cached?.takeUnless { it.finishedEmpty }?.results.orEmpty())
    }
    var total by remember(cacheKey) {
        mutableIntStateOf(cached?.total ?: 0)
    }
    var done by remember(cacheKey) {
        mutableIntStateOf(if (cached?.finishedEmpty == true) 0 else cached?.done ?: 0)
    }
    // Typed failures per source id: mapped through the same report builder as
    // the catalogue and rendered with PluginErrorState (never "Nothing found").
    var failures by remember(cacheKey) {
        mutableStateOf<Map<String, PluginErrorReport>>(emptyMap())
    }
    var history by remember { mutableStateOf(AppSettings.searchHistoryFor(SearchHistoryRules.GlobalKey)) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // Per-URL in-flight guard shared by every source section.
    var membershipPending by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(provider) { sources = provider.sources() }

    // Persist the snapshot (incl. scroll) when the screen leaves composition.
    // A PARTIAL snapshot is kept too, so an in-flight fan-out resumes where it
    // stopped instead of starting over. A finished empty search is never
    // cached: it would replay "Nothing found" instantly.
    DisposableEffect(cacheKey) {
        onDispose {
            val finishedEmpty = done >= total && results.none { it.books.isNotEmpty() }
            if (submitted != null && !finishedEmpty) {
                FinderSearchCache.putGlobal(
                    FinderSearchCache.GlobalSnapshot(
                        key = cacheKey,
                        results = results,
                        total = total,
                        done = done,
                        scrollIndex = listState.firstVisibleItemIndex,
                        scrollOffset = listState.firstVisibleItemScrollOffset,
                        storedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun submit(raw: String) {
        val clean = submitGlobalQuery(raw) ?: return
        field = clean
        history = SearchHistoryRules.push(history, clean)
        AppSettings.setSearchHistory(SearchHistoryRules.GlobalKey, history)
        submitted = clean
    }

    val targets = remember(sources, scopeType) {
        sources.orEmpty().filter { scopeType == null || it.type.name == scopeType }
    }

    // Fan-out: every target is asked in parallel, results land as they come.
    // Submit-only by design: the key is the explicitly
    // submitted query, never the live text field, so typing sends no requests.
    LaunchedEffect(submitted, targets) {
        val query = globalSearchRequestQuery(submitted) ?: return@LaunchedEffect
        if (sources == null) return@LaunchedEffect
        val resume = resumeGlobalSearch(cached, targets)
        // Every shown failure is re-queried below (or belongs to a source that
        // left the current scope): drop the old cards before the new answers.
        failures = emptyMap()
        if (cached == null) {
            // New query/scope: start clean, from the top.
            results = emptyList()
            total = targets.size
            done = 0
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                listState.scrollToItem(0)
            }
        } else {
            // Cache hit (e.g. back from a book, possibly mid-fan-out): keep
            // every valid answer and ask only the sources still missing.
            results = resume.results
            total = resume.total
            done = resume.done
            listState.scrollToItem(cached.scrollIndex, cached.scrollOffset)
        }
        coroutineScope {
            resume.missing.forEach { source ->
                launch {
                    var books = emptyList<CatalogBook>()
                    var failed = false
                    try {
                        books = provider.searchSource(source.id, query)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PluginRequestException) {
                        failed = true
                        globalSearchFailureReport(source, e)?.let { report ->
                            failures = failures + (source.id to report)
                        }
                    } catch (e: Exception) {
                        failed = true
                    }
                    results = results + SourceResult(source, books, failed)
                    done++
                }
            }
        }
    }

    val ordered = remember(results, targets) {
        // One section per source: a resumed/partial snapshot can carry two
        // answers for the same source id, which would duplicate the LazyList
        // section key.
        dedupeSourceResults(results)
            .sortedBy { result -> targets.indexOfFirst { it.id == result.source.id } }
    }
    val withBooks = ordered.filter { it.books.isNotEmpty() }
    val failureReports = ordered.mapNotNull { result ->
        failures[result.source.id]?.let { report -> result.source to report }
    }
    // Typed failures already render a full card; only untyped ones keep the
    // one-line summary.
    val typedIds = failureReports.mapTo(HashSet()) { it.first.id }
    val failedNames = ordered
        .filter { it.failed && it.source.id !in typedIds }
        .map { it.source.name }

    // Retry one failed source in place instead of re-running the whole fan-out.
    fun retrySource(source: CatalogSource, query: String) {
        failures = failures - source.id
        scope.launch {
            val report = try {
                val books = provider.searchSource(source.id, query)
                results = results.map {
                    if (it.source.id == source.id) SourceResult(source, books, false) else it
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results = results.map {
                    if (it.source.id == source.id) SourceResult(source, emptyList(), true) else it
                }
                globalSearchFailureReport(source, e)
            }
            if (report != null) failures = failures + (source.id to report)
        }
    }

    // Explicit target, optimistic section state: only the tapped card flips;
    // a failed write restores the previous value with an error toast.
    fun toggleMembership(book: CatalogBook) {
        val url = book.bookUrl
        if (url in membershipPending) return
        val target = !book.inLibrary
        membershipPending = membershipPending + url
        results = results.map { result ->
            result.copy(books = result.books.setMembership(url, target))
        }
        scope.launch {
            val result = try {
                provider.setInLibrary(url, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MembershipResult(success = false)
            }
            membershipPending = membershipPending - url
            if (result.success) {
                // Keep the committed value even if a late source answer carried
                // the pre-toggle flag.
                results = results.map { source ->
                    source.copy(books = source.books.setMembership(url, target))
                }
                val message = when {
                    result.fileCleanupFailed -> R.string.book_library_cleanup_failed
                    target -> R.string.book_added_toast
                    else -> R.string.book_removed_toast
                }
                onLibraryChanged()
                Toast.makeText(context, resources.getString(message), Toast.LENGTH_SHORT).show()
            } else {
                results = results.map { source ->
                    source.copy(books = source.books.setMembership(url, !target))
                }
                Toast.makeText(
                    context,
                    resources.getString(R.string.book_library_update_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
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
        item(key = "field") {
            MiruCard(contentPadding = PaddingValues(MiruSpace.S)) {
                CatalogSearchField(
                    value = field,
                    onValueChange = { field = it },
                    placeholder = stringResource(R.string.finder_gs_hint),
                    onClear = {
                        field = ""
                        submitted = null
                        results = emptyList()
                    },
                    onSearch = { submit(field) },
                )
            }
        }
        item(key = "scope") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(MiruSpace.S)) {
                item {
                    MiruFilterChip(
                        selected = scopeType == null,
                        onClick = { scopeType = null },
                        label = stringResource(R.string.finder_filter_all),
                    )
                }
                items(ContentType.entries.toList()) { type ->
                    MiruFilterChip(
                        selected = scopeType == type.name,
                        onClick = { scopeType = if (scopeType == type.name) null else type.name },
                        label = typeLabel(type),
                    )
                }
            }
        }

        val query = submitted
        if (query == null) {
            // Before the first search: history, or one calm hint.
            item(key = "idle") {
                if (history.isEmpty()) {
                    MiruCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.finder_gs_idle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalMiruExtraColors.current.muted,
                        )
                    }
                } else {
                    MiruSectionCard(
                        title = stringResource(R.string.finder_history_title),
                        actionLabel = stringResource(R.string.finder_history_clear),
                        onAction = {
                            history = emptyList()
                            AppSettings.setSearchHistory(SearchHistoryRules.GlobalKey, history)
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        history.forEachIndexed { index, entry ->
                            if (index > 0) MiruRowDivider()
                            HistoryRow(
                                text = entry,
                                onClick = { submit(entry) },
                                onRemove = {
                                    history = SearchHistoryRules.remove(history, entry)
                                    AppSettings.setSearchHistory(SearchHistoryRules.GlobalKey, history)
                                },
                            )
                        }
                    }
                }
            }
        } else {
            item(key = "progress") {
                if (done < total) {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            text = stringResource(R.string.finder_gs_progress_fmt, done, total),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalMiruExtraColors.current.muted,
                            modifier = Modifier.padding(bottom = MiruSpace.S),
                        )
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            color = LocalMiruExtraColors.current.accent,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            items(withBooks, key = { globalSourceKey(it.source.id) }) { result ->
                MiruSectionCard(
                    title = sourceDisplayName(result.source),
                    description = stringResource(
                        R.string.finder_gs_results_fmt,
                        result.books.size,
                    ),
                    actionLabel = stringResource(R.string.finder_gs_open_catalogue),
                    onAction = { onOpenCatalogue(result.source.id, query) },
                    contentPadding = PaddingValues(MiruSpace.S),
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(MiruSpace.CardGap)) {
                        items(
                            dedupeCatalogBooks(result.books),
                            key = { globalBookKey(it.bookUrl) },
                        ) { book ->
                            Box(modifier = Modifier.width(MiruSize.Tile)) {
                                CatalogTile(
                                    book = book,
                                    inLibrary = book.inLibrary,
                                    plain = true,
                                    membership = book.badge?.toMiruBadge(),
                                    onClick = { onBookOpen(book.bookUrl) },
                                    onLongClick = { toggleMembership(book) },
                                )
                            }
                        }
                    }
                }
            }
            items(failureReports, key = { "failure-${it.first.id}" }) { (source, report) ->
                PluginErrorState(
                    title = report.title ?: stringResource(R.string.finder_error_title),
                    message = report.message.orEmpty(),
                    detailText = report.detailText,
                    reportText = report.reportText,
                    onRetry = { retrySource(source, query) },
                )
            }
            item(key = "summary") {
                val finished = done >= total
                when {
                    finished && withBooks.isEmpty() && failureReports.isEmpty() &&
                        failedNames.isEmpty() -> MiruCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.finder_gs_nothing),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    finished && failedNames.isNotEmpty() -> Text(
                        text = stringResource(
                            R.string.finder_gs_failed_fmt,
                            failedNames.joinToString(", "),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalMiruExtraColors.current.muted,
                    )
                }
            }
        }
    }
}

// Global search is submit-only: keystrokes mutate only
// `field`; the fan-out key below is the submitted query. Enter (IME Search)
// and history rows call submit() and run immediately — no auto-search, no
// debounce on an explicit submit.
internal fun globalSearchRequestQuery(submitted: String?): String? = submitted

// Explicit submit: trim, ignore a blank field (nothing to search for).
internal fun submitGlobalQuery(field: String): String? = field.trim().ifEmpty { null }

// Global search maps a typed source failure through the ONE shared report
// builder so it shows source+operation, HTTP code/class and request=host+path
// exactly like the catalogue. Untyped throwables stay a name-only summary.
internal fun globalSearchFailureReport(source: CatalogSource, error: Throwable): PluginErrorReport? {
    val failure = error as? PluginRequestException ?: return null
    return buildPluginErrorReport(
        error = failure.error,
        sourceId = failure.sourceId ?: source.id,
        sourceName = failure.sourceName ?: source.name,
        operation = failure.operation ?: "search",
        identity = source.id,
    )
}

@Composable
private fun HistoryRow(text: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = MiruSize.Touch)
            .padding(start = MiruSpace.L),
        horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
    ) {
        Icon(
            imageVector = Icons.Filled.History,
            contentDescription = null,
            tint = extra.muted,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(MiruSize.Touch)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.finder_history_remove_cd),
                tint = extra.muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
