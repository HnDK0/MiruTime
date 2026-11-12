package dev.mirutime.app.features.finder.data

import dev.mirutime.app.core.PluginRequestException
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFacet
import dev.mirutime.app.features.finder.domain.CatalogFacetGroup
import dev.mirutime.app.features.finder.domain.CatalogSection
import dev.mirutime.app.features.finder.domain.CatalogSortOption
import dev.mirutime.app.features.finder.domain.dedupeCatalogBooks
import dev.mirutime.app.features.opds.domain.OpdsFailure
import dev.mirutime.app.features.opds.domain.OpdsPage
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One OPDS catalogue visit: a stack of feeds (root -> section/facet/sort/search)
 * so Back returns to the parent feed without a refetch.
 *
 * A feed is paged through its own `nextUrl`; the stack layer owns those pages,
 * so "back" restores the parent's books *and* its scroll position. Navigation
 * links, facets and sort links belong to the current feed only: an appended
 * page never replaces them.
 */
internal class OpdsCatalogSession(
    private val fetchFeed: suspend (url: String?, includeLinks: Boolean?) -> Outcome<OpdsPage>,
    /** Search template (still carrying its `{…}` variable) for the feed, or null. */
    private val resolveSearchUrl: suspend (feedUrl: String) -> Outcome<String?>,
    private val mapBooks: (OpdsPage, Boolean) -> List<CatalogBook>,
    private val scope: CoroutineScope,
) : CatalogSessionLike {
    private val mutableState = MutableStateFlow(CatalogPageState())
    override val state: StateFlow<CatalogPageState> = mutableState.asStateFlow()
    private val mutex = Mutex()

    private class FeedLayer(
        val url: String,
        /** First page was a result list, so every page stays a book list. */
        val publicationFeed: Boolean,
    ) {
        val pages = linkedMapOf<Int, OpdsPage>()
        val current: OpdsPage get() = pages.getValue(pages.keys.last())

        /** Feed URLs already fetched for this layer: `next` must never revisit one. */
        val visited = mutableSetOf<String>()
    }

    private val layers = ArrayDeque<FeedLayer>()

    // A push that failed (section/facet/sort/search) is what Retry must repeat;
    // reloading the parent layer would silently drop the user's navigation.
    private var pendingPushUrl: String? = null

    // Scroll per stack layer: going back restores that feed's own position.
    private val scroll = mutableMapOf<String, Pair<String?, Int>>()

    override fun activate() {
        mutableState.update { it.copy(active = true) }
    }

    override fun deactivate() {
        mutableState.update { it.copy(active = false) }
    }

    override fun ensureLoaded() {
        if (mutableState.value.started) return
        mutableState.update { it.copy(started = true) }
        reloadRoot()
    }

    override fun requestNext() {
        val current = mutableState.value
        if (!current.started || current.loading || !current.hasMore) return
        if (current.error != null || current.failure != null) return
        val layer = layers.lastOrNull() ?: return
        val nextUrl = layer.current.nextUrl
        // A catalogue may point `next` back at a page we already have: archive.org
        // repeats page 1 as page 0. Asking again would loop or duplicate the list.
        if (nextUrl == null || nextUrl == layer.url || nextUrl in layer.visited) {
            mutableState.update { it.copy(hasMore = false) }
            return
        }
        val nextPage = layer.pages.keys.last() + 1
        val generation = beginLoad(clear = false)
        launchLoad(generation) {
            // The OPDS seam re-throws a typed MiruError; normalise to the same
            // shape the tests feed in, so paging has one error path.
            val fetched = try {
                fetchFeed(nextUrl, layer.publicationFeed)
            } catch (failure: PluginRequestException) {
                Outcome.Err(failure.error)
            }
            when (fetched) {
                is Outcome.Err -> endOfList(generation, fetched.error)
                is Outcome.Ok -> {
                    if (!isCurrent(generation)) return@launchLoad
                    layer.visited += nextUrl
                    layer.visited += fetched.value.url
                    layer.pages[nextPage] = fetched.value
                    mutableState.update { it.withFeed(layer) }
                }
            }
        }
    }

    override fun retry() {
        val pending = pendingPushUrl
        if (pending != null) {
            val generation = beginLoad(clear = false)
            launchLoad(generation) { push(generation, pending) }
            return
        }
        if (layers.isEmpty()) {
            // The root never landed: there is nothing to reload but the root.
            reloadRoot()
            return
        }
        reload(layers.last())
    }

    override fun refresh(force: Boolean) {
        mutableState.update { it.copy(started = true) }
        if (layers.isEmpty()) reloadRoot() else reload(layers.last())
    }

    override fun search(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        val layer = layers.lastOrNull() ?: return
        val generation = beginLoad(clear = false)
        launchLoad(generation) {
            val template = when (val result = resolveSearchUrl(layer.url)) {
                is Outcome.Err -> {
                    fail(generation, result.error)
                    return@launchLoad
                }
                is Outcome.Ok -> result.value
            }
            if (template == null) {
                fail(generation, "opds-search-unavailable")
                return@launchLoad
            }
            push(generation, substituteSearchTerms(template, query))
        }
    }

    override fun openSection(url: String) = open(url)

    override fun openFacet(url: String) = open(url)

    override fun openSort(url: String) = open(url)

    private fun open(url: String) {
        if (url.isBlank() || layers.isEmpty()) return
        val generation = beginLoad(clear = false)
        launchLoad(generation) { push(generation, url) }
    }

    override fun goBack() {
        if (layers.size <= 1) return
        val generation = beginLoad(clear = false)
        launchLoad(generation) {
            if (!isCurrent(generation)) return@launchLoad
            if (layers.size <= 1) return@launchLoad
            layers.removeLast()
            mutableState.update { it.withFeed(layers.last()) }
        }
    }

    override fun saveScroll(bookUrl: String?, offset: Int) {
        val url = layers.lastOrNull()?.url ?: return
        scroll[url] = bookUrl to offset
    }

    override fun updateBook(bookUrl: String, transform: (CatalogBook) -> CatalogBook) {
        mutableState.update { state ->
            state.copy(
                books = state.books.map { if (it.bookUrl == bookUrl) transform(it) else it },
            )
        }
    }

    override fun scrollIndex(books: List<CatalogBook>): Int {
        val saved = layers.lastOrNull()?.url?.let { scroll[it]?.first } ?: return 0
        return books.indexOfFirst { it.bookUrl == saved }.takeIf { it >= 0 } ?: 0
    }

    override fun scrollOffset(): Int =
        layers.lastOrNull()?.url?.let { scroll[it]?.second } ?: 0

    override fun close() {
        scope.cancel()
    }

    private fun reloadRoot() {
        val generation = beginLoad(clear = true)
        launchLoad(generation) {
            when (val result = fetchFeed(null, null)) {
                is Outcome.Err -> fail(generation, result.error)
                is Outcome.Ok -> {
                    if (!isCurrent(generation)) return@launchLoad
                    layers.clear()
                    val layer = appendLayer(result.value)
                    mutableState.update { it.withFeed(layer) }
                }
            }
        }
    }

    /** Reloads the current feed's page 1, keeping the stack above it. */
    private fun reload(layer: FeedLayer) {
        val generation = beginLoad(clear = true)
        launchLoad(generation) {
            when (val result = fetchFeed(layer.url, null)) {
                is Outcome.Err -> fail(generation, result.error)
                is Outcome.Ok -> {
                    if (!isCurrent(generation)) return@launchLoad
                    layer.pages.clear()
                    layer.pages[1] = result.value
                    mutableState.update { it.withFeed(layer) }
                }
            }
        }
    }

    /** Fetches [url] and pushes it as a new layer; the parent stays cached. */
    private suspend fun push(generation: Int, url: String) {
        when (val result = fetchFeed(url, null)) {
            is Outcome.Err -> {
                // A superseded push must not hijack Retry: pendingPushUrl is
                // what retry() repeats, and only the latest request may own it.
                if (!isCurrent(generation)) return
                pendingPushUrl = url
                fail(generation, result.error)
            }
            is Outcome.Ok -> {
                if (!isCurrent(generation)) return
                pendingPushUrl = null
                val layer = appendLayer(result.value)
                mutableState.update { it.withFeed(layer) }
            }
        }
    }

    private fun appendLayer(feed: OpdsPage): FeedLayer =
        FeedLayer(
            url = feed.url,
            // A paged feed is a result list; its later pages keep that shape
            // even when the last one has no `next` link. A feed whose links are
            // publications of their own (`.opds`) is one even without paging.
            publicationFeed = feed.books.isNotEmpty() ||
                feed.nextUrl != null ||
                feed.links.any { isPublicationLink(it.url) },
        ).also {
            it.pages[1] = feed
            it.visited += feed.url
            layers.addLast(it)
        }

    private fun beginLoad(clear: Boolean): Int {
        val generation = mutableState.value.generation + 1
        mutableState.update {
            it.copy(
                generation = generation,
                loading = true,
                error = null,
                failure = null,
                books = if (clear) emptyList() else it.books,
                pagesLoaded = if (clear) 0 else it.pagesLoaded,
                sections = if (clear) emptyList() else it.sections,
                facets = if (clear) emptyList() else it.facets,
                sortOptions = if (clear) emptyList() else it.sortOptions,
                sectionsOnly = if (clear) false else it.sectionsOnly,
            )
        }
        return generation
    }

    private fun launchLoad(generation: Int, block: suspend () -> Unit) {
        scope.launch {
            try {
                mutex.withLock { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PluginRequestException) {
                fail(generation, failure)
            } catch (error: Exception) {
                fail(generation, error.message ?: error.toString())
            }
        }
    }

    private fun isCurrent(generation: Int): Boolean =
        mutableState.value.generation == generation

    private fun fail(generation: Int, failure: PluginRequestException) {
        if (!isCurrent(generation)) return
        mutableState.update { it.copy(loading = false, failure = failure) }
    }

    // The feed fetcher reports a typed MiruError; the screen's error state is a
    // string, so keep its class name and never a URL.
    private fun fail(generation: Int, error: MiruError) {
        if (!isCurrent(generation)) return
        mutableState.update {
            it.copy(loading = false, error = error::class.simpleName ?: "opds")
        }
    }

    /**
     * Some catalogues answer the page past the last one with a server error;
     * archive.org 500s a valid `last` page. When the user already reads books,
     * that is the end of the list, not an error card over their place.
     */
    private fun endOfList(generation: Int, error: MiruError) {
        if (!isCurrent(generation)) return
        if (endsPaging(error) && mutableState.value.books.isNotEmpty()) {
            mutableState.update { it.copy(loading = false, hasMore = false) }
        } else {
            fail(generation, error)
        }
    }

    /** 5xx/429, or a body that is not a feed, past the end of a list. */
    private fun endsPaging(error: MiruError): Boolean = when (error) {
        is OpdsFailure.NotAFeed -> true
        is OpdsFailure.Network -> error.status == 500 || error.status == 429
        is PluginError.Network -> error.status == 500 || error.status == 429
        // The OPDS seam reports a non-network failure by its class name.
        is PluginError.ScriptError -> error.detail == NOT_A_FEED
        else -> false
    }

    private fun fail(generation: Int, message: String) {
        if (!isCurrent(generation)) return
        mutableState.update { it.copy(loading = false, error = message) }
    }

    /**
     * Publishes one layer: its books come from every page loaded so far, while
     * navigation/facets/sort/search describe the feed's latest page.
     */
    private fun CatalogPageState.withFeed(layer: FeedLayer): CatalogPageState {
        pendingPushUrl = null
        val books = dedupeCatalogBooks(
            layer.pages.values.flatMap { page -> mapBooks(page, layer.publicationFeed) },
        )
        val feed = layer.current
        // A paged feed is a result list: its entries are publications even when
        // the acquisition lives one feed down (Gutenberg). An unpaged feed is a
        // navigation feed: its entries are sections.
        val sections = if (layer.publicationFeed) {
            emptyList()
        } else {
            feed.links.map { CatalogSection(it.title, it.subtitle, it.url) }
        }
        return copy(
            books = books,
            pagesLoaded = layer.pages.size,
            hasMore = feed.nextUrl != null,
            loading = false,
            error = null,
            failure = null,
            sections = sections,
            facets = feed.facets.map { group ->
                CatalogFacetGroup(
                    label = group.label,
                    options = group.options.map {
                        CatalogFacet(it.label, it.href, it.active, it.count)
                    },
                )
            },
            sortOptions = feed.sortLinks.map {
                CatalogSortOption(
                    label = it.title?.takeIf { title -> title.isNotBlank() } ?: hrefTail(it.href),
                    href = it.href,
                    active = false,
                )
            },
            canGoBack = layers.size > 1,
            sectionsOnly = books.isEmpty() && sections.isNotEmpty(),
            searchAvailable = feed.searchLink != null,
        )
    }

    private fun hrefTail(href: String): String =
        href.trimEnd('/').substringAfterLast('/').ifBlank { href }

    // A publication feed linked from its own catalogue: Gutenberg lists books
    // as `.opds` feeds, a section index never does.
    private fun isPublicationLink(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".opds", ignoreCase = true)
}

// PluginError.ScriptError detail opdsPluginError() uses for OpdsFailure.NotAFeed.
private const val NOT_A_FEED = "NotAFeed"
