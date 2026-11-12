package dev.mirutime.app.features.finder.data

import dev.mirutime.app.core.PluginRequestException
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFacetGroup
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogPage
import dev.mirutime.app.features.finder.domain.CatalogSection
import dev.mirutime.app.features.finder.domain.CatalogSortOption
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.finder.domain.OpdsSources
import dev.mirutime.app.features.finder.domain.dedupeCatalogBooks
import dev.mirutime.app.features.opds.domain.OpdsFailure
import dev.mirutime.app.features.opds.domain.OpdsPage
import dev.mirutime.core.api.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CatalogPageState(
    val books: List<CatalogBook> = emptyList(),
    val pagesLoaded: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val failure: PluginRequestException? = null,
    val generation: Int = 0,
    val started: Boolean = false,
    val active: Boolean = true,
    // OPDS feed navigation: sections/facets/sort belong to the current feed,
    // never merged into an appended page.
    val sections: List<CatalogSection> = emptyList(),
    val facets: List<CatalogFacetGroup> = emptyList(),
    val sortOptions: List<CatalogSortOption> = emptyList(),
    val canGoBack: Boolean = false,
    /** A navigation feed with no books: show sections, not the empty state. */
    val sectionsOnly: Boolean = false,
    /** The current OPDS feed advertises a usable search link. */
    val searchAvailable: Boolean = false,
)

/** What the catalogue screen needs from a session; OPDS and plugin sessions differ. */
internal interface CatalogSessionLike {
    val state: StateFlow<CatalogPageState>

    fun activate()
    fun deactivate()
    fun ensureLoaded()
    fun requestNext()
    fun retry()
    fun refresh(force: Boolean = false)
    fun saveScroll(bookUrl: String?, offset: Int)
    fun updateBook(bookUrl: String, transform: (CatalogBook) -> CatalogBook)
    fun scrollIndex(books: List<CatalogBook>): Int
    fun scrollOffset(): Int
    fun close()

    // OPDS feed navigation: a pushed feed keeps the parent on the stack, and
    // [goBack] restores it without a refetch. No-ops for plugin/local sources.
    fun openSection(url: String) {}
    fun openFacet(url: String) {}
    fun openSort(url: String) {}
    fun search(text: String) {}
    fun goBack() {}
}

/**
 * One catalogue visit that outlives its composition: an in-flight page keeps
 * loading while the user is in a book and lands in [state] for the return.
 *
 * The session owns a scope on purpose (a named application-scoped holder, see
 * the `kotlin-coroutines` rule): the screen's scope is cancelled by navigation,
 * which is exactly the cancellation this class exists to avoid. It lives until
 * [close], called by [CatalogSessionRegistry] on eviction or teardown.
 */
internal class CatalogSession(
    private val loadPage: suspend (page: Int, refresh: Boolean) -> CatalogPage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : CatalogSessionLike {
    private val mutableState = MutableStateFlow(CatalogPageState())
    override val state: StateFlow<CatalogPageState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var scrollBookUrl: String? = null
    private var scrollOffsetPx = 0

    override fun activate() {
        mutableState.update { it.copy(active = true) }
    }

    /** Keeps the list and any in-flight page; only background work pauses. */
    override fun deactivate() {
        mutableState.update { it.copy(active = false) }
    }

    override fun ensureLoaded() {
        if (mutableState.value.started) return
        mutableState.update { it.copy(started = true) }
        reload(force = false)
    }

    override fun requestNext() {
        val current = mutableState.value
        if (!current.started || current.loading || !current.hasMore) return
        if (current.error != null || current.failure != null) return
        load(page = current.pagesLoaded + 1, refresh = false, append = true)
    }

    override fun retry() {
        val current = mutableState.value
        mutableState.update { it.copy(error = null, failure = null) }
        if (current.books.isEmpty()) reload(force = false) else requestNext()
    }

    override fun refresh(force: Boolean) {
        mutableState.update { it.copy(started = true) }
        reload(force)
    }

    override fun saveScroll(bookUrl: String?, offset: Int) {
        scrollBookUrl = bookUrl
        scrollOffsetPx = offset
    }

    /** In-place card update (membership badge) without reloading the page. */
    override fun updateBook(bookUrl: String, transform: (CatalogBook) -> CatalogBook) {
        mutableState.update { state ->
            state.copy(
                books = state.books.map {
                    if (it.bookUrl == bookUrl) transform(it) else it
                },
            )
        }
    }

    /** Index of the book that was visible before a list replacement. */
    override fun scrollIndex(books: List<CatalogBook>): Int =
        books.indexOfFirst { it.bookUrl == scrollBookUrl }.takeIf { it >= 0 } ?: 0

    override fun scrollOffset(): Int = scrollOffsetPx

    override fun close() {
        scope.cancel()
    }

    private fun reload(force: Boolean) {
        val generation = mutableState.value.generation + 1
        mutableState.update {
            it.copy(
                generation = generation,
                loading = true,
                error = null,
                failure = null,
                books = emptyList(),
                pagesLoaded = 0,
                hasMore = true,
            )
        }
        launchLoad(page = 1, refresh = force, append = false, generation = generation)
    }

    private fun load(page: Int, refresh: Boolean, append: Boolean) {
        val generation = mutableState.value.generation
        mutableState.update { it.copy(loading = true, error = null, failure = null) }
        launchLoad(page, refresh, append, generation)
    }

    private fun launchLoad(page: Int, refresh: Boolean, append: Boolean, generation: Int) {
        scope.launch {
            try {
                val result = mutex.withLock { loadPage(page, refresh) }
                if (mutableState.value.generation != generation) return@launch
                mutableState.update {
                    val books = if (append) {
                        dedupeCatalogBooks(it.books + result.books)
                    } else {
                        dedupeCatalogBooks(result.books)
                    }
                    it.copy(
                        books = books,
                        pagesLoaded = page,
                        hasMore = result.hasMore,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PluginRequestException) {
                if (mutableState.value.generation == generation) {
                    mutableState.update { it.copy(loading = false, failure = failure) }
                }
            } catch (error: Exception) {
                if (mutableState.value.generation == generation) {
                    mutableState.update {
                        it.copy(loading = false, error = error.message ?: error.toString())
                    }
                }
            }
        }
    }
}

/**
 * Process-wide session cache, one per source+filters. Only the visible session
 * survives a membership change; hidden ones are closed so the next visit
 * re-reads badges instead of replaying a stale snapshot.
 */
internal class CatalogSessionRegistry(
    private val page: suspend (sourceId: String, filters: CatalogFilters, page: Int) -> CatalogPage,
    private val localSnapshot: suspend (sourceId: String) -> LocalCatalogSnapshot? = { null },
    private val localPage: suspend (
        sourceId: String,
        snapshot: LocalCatalogSnapshot,
        filters: CatalogFilters,
        page: Int,
    ) -> CatalogPage = { sourceId, _, filters, page -> page(sourceId, filters, page) },
    private val opdsFeed: suspend (sourceId: String, url: String?, includeLinks: Boolean?) -> Outcome<OpdsPage> =
        { _, _, _ ->
            Outcome.Err(OpdsFailure.Unknown("opds-unavailable"))
        },
    /** Search template (`{searchTerms}` intact) behind [feedUrl], or null. */
    private val opdsSearch: suspend (sourceId: String, feedUrl: String) -> Outcome<String?> = { _, _ ->
        Outcome.Ok(null)
    },
    private val opdsBooks: (sourceId: String, page: OpdsPage, includeLinks: Boolean) -> List<CatalogBook> =
        { _, _, _ -> emptyList() },
    private val newScope: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    },
) {
    private val sessions = LinkedHashMap<CatalogCacheKey, CatalogSessionLike>()

    fun get(sourceId: String, filters: CatalogFilters): CatalogSessionLike {
        // The OPDS feed ignores the query: search pushes a feed in the session,
        // so a new query must not throw the session (and its stack) away.
        val key = if (OpdsSources.catalogIdOf(sourceId) != null) {
            CatalogCacheKey(sourceId, filters.copy(query = ""))
        } else {
            CatalogCacheKey(sourceId, filters)
        }
        sessions.remove(key)?.let {
            sessions[key] = it
            return it
        }
        val session = create(sourceId, filters)
        sessions[key] = session
        while (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.keys.first()
            sessions.remove(oldest)?.close()
        }
        return session
    }

    fun invalidateAll() {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.state.value.active) {
                entry.value.close()
                iterator.remove()
            }
        }
    }

    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private fun create(sourceId: String, filters: CatalogFilters): CatalogSessionLike {
        // Each session owns its own scope: close() must cancel only this visit,
        // never the shared pager of another source or the visible one.
        val scope = newScope()
        if (OpdsSources.catalogIdOf(sourceId) != null) {
            return OpdsCatalogSession(
                fetchFeed = { url, includeLinks ->
                    when (val loaded = opdsFeed(sourceId, url, includeLinks)) {
                        is Outcome.Ok -> loaded
                        is Outcome.Err -> throw PluginRequestException(
                            opdsPluginError(loaded.error),
                            sourceId = sourceId,
                            operation = "catalog",
                        )
                    }
                },
                resolveSearchUrl = { feedUrl -> opdsSearch(sourceId, feedUrl) },
                mapBooks = { feed, includeLinks -> opdsBooks(sourceId, feed, includeLinks) },
                scope = scope,
            )
        }
        if (!LocalSources.isLocal(sourceId)) {
            return CatalogSession({ pageNumber, _ -> page(sourceId, filters, pageNumber) }, scope)
        }
        var snapshot: LocalCatalogSnapshot? = null
        return CatalogSession(
            { pageNumber, refresh ->
                if (refresh) {
                    // A failed rescan keeps the previous snapshot instead of
                    // blanking the catalogue.
                    localSnapshot(sourceId)?.let { snapshot = it }
                } else if (snapshot == null) {
                    snapshot = localSnapshot(sourceId)
                }
                val current = snapshot ?: return@CatalogSession CatalogPage(emptyList(), hasMore = false)
                localPage(sourceId, current, filters, pageNumber)
            },
            scope,
        )
    }

    private companion object {
        const val MAX_SESSIONS = 8
    }
}
