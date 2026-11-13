package dev.mirutime.app.features.finder.data

import android.net.Uri
import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.di.InstalledPlugin
import dev.mirutime.app.di.InstallReport
import dev.mirutime.app.di.PluginGraph
import dev.mirutime.app.di.REPO_FAILURE_KEY
import dev.mirutime.app.di.RepoCatalog
import dev.mirutime.app.di.host.MiruCoreLogger
import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.data.PluginBookIndex
import dev.mirutime.app.features.book.data.parsePluginRating
import dev.mirutime.app.features.book.data.pluginStatusOngoing
import dev.mirutime.app.features.finder.domain.CatalogBook
import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogPage
import dev.mirutime.app.features.finder.domain.CatalogRequest
import dev.mirutime.app.features.finder.domain.CatalogSource
import dev.mirutime.app.features.finder.domain.FilterPreset
import dev.mirutime.app.features.finder.domain.LinkResolution
import dev.mirutime.app.features.finder.domain.LocalBook
import dev.mirutime.app.features.finder.domain.LocalCatalogFolder
import dev.mirutime.app.features.finder.domain.LocalSources
import dev.mirutime.app.features.finder.domain.OpdsSources
import dev.mirutime.app.features.finder.domain.PluginExtension
import dev.mirutime.app.features.finder.domain.PluginFileInstall
import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.PluginResetResult
import dev.mirutime.app.features.finder.domain.PluginSetting
import dev.mirutime.app.features.finder.domain.parsePluginFilters
import dev.mirutime.app.features.finder.domain.pluginCatalogRequest
import dev.mirutime.app.features.finder.domain.restrictToSchema
import dev.mirutime.app.features.finder.domain.applyCatalogFilters
import dev.mirutime.app.features.finder.domain.applyMembership
import dev.mirutime.app.features.library.data.BookCoverStore
import dev.mirutime.app.features.library.data.ImportSummary
import dev.mirutime.app.features.library.data.LocalBooksInstallId
import dev.mirutime.app.features.library.data.LocalMangaInstallId
import dev.mirutime.app.features.library.data.LocalBookImporter
import dev.mirutime.app.features.library.data.LocalBookProvider
import dev.mirutime.app.features.library.data.LocalBooksInstallId
import dev.mirutime.app.features.library.data.LocalImportResult
import dev.mirutime.app.features.library.data.PickedImportFile
import dev.mirutime.app.features.library.data.resolveStoredCover
import dev.mirutime.app.features.library.data.summarizeImports
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.opds.data.OpdsEntryOptions
import dev.mirutime.app.features.opds.data.canonicalCatalogUrl
import dev.mirutime.app.features.opds.domain.OpdsController
import dev.mirutime.app.features.opds.domain.OpdsFailure
import dev.mirutime.app.features.opds.domain.OpdsPage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.SettingsStore
import dev.mirutime.data.library.LibraryMembership
import dev.mirutime.data.library.OpdsEntryEntity
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.libraryBookKey
import dev.mirutime.core.api.CatalogPage as CoreCatalogPage
import dev.mirutime.core.api.ContentType as CoreContentType
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.app.core.PluginRequestException
import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.installer.IndexEntry
import dev.mirutime.core.opds.OpdsSearchTemplate
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "MiruTime.Finder"

// A broken catalogue cover can fail on every visible tile at once. Keep the
// automatic repair to one source round-trip per URL per window.
private const val CATALOG_COVER_REPAIR_COOLDOWN_MS = 30_000L

// OPDS feeds kept for card taps: the tap only needs the feed it came from.
private const val MAX_OPDS_PAGES = 6
// Rendered entries cached per catalog: the book page and add read this table,
// so it is a recent-visits cache, not an archive of the catalog.
private const val MAX_OPDS_ENTRIES = 200
private const val SEARCH_TERMS = "{searchTerms}"
private const val OPENSEARCH_DESCRIPTION_TYPE = "application/opensearchdescription+xml"
private const val OPENSEARCH_ATOM_TYPE = "application/atom+xml"

// Optimistic pin write: AppSettings flips first so the screen updates
// immediately; the store write is awaited only to detect a failed commit and
// put the previous set back before the screen reports it.
internal suspend fun persistPinnedSource(sourceId: String, store: SettingsStore?): Boolean {
    val previous = AppSettings.pinnedSources
    AppSettings.togglePinnedSource(sourceId)
    val written = store?.let {
        withContext(Dispatchers.IO) {
            try {
                it.save(AppSettings.persistedSnapshot())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }
    } ?: true
    if (!written) AppSettings.pinnedSources = previous
    return written
}

// Real plugin-backed provider: Browse lists installed+enabled plugins from the
// runtime; catalogue/search pages resolve the plugin source lazily and map its
// output. Local sources, pins, presets, base URLs and link resolution use the
// real library/settings stores — there is no fixture fallback.
class PluginFinderDataProvider(
    private val graph: PluginGraph,
    private val library: LibraryRepository? = null,
    private val importer: LocalBookImporter? = null,
    private val localLibrary: LocalBookProvider? = null,
    // Existing local covers live in BookCoverStore, not books.coverUrl.
    private val coverStore: BookCoverStore? = null,
    // Nullable so unit tests and previews can construct the provider without
    // SAF/SharedPreferences; the app always injects the real folder store.
    private val localFolders: LocalSourceFolders? = null,
    private val metadataCache: LocalCatalogMetadataCache? = null,
    // Shared short-id registry; scans normally register entries already, this
    // is the belt-and-braces path for tests/previews and bulk imports.
    private val registry: LocalSafRegistry? = null,
    private val settingsStore: SettingsStore? = null,
    // Real membership writer (add/remove through the book data layer). Kept
    // nullable for previews/tests that only read the catalogue.
    private val bookProvider: BookDataProvider? = null,
    // Saved OPDS catalogs ride the ordinary catalogue pipeline. Nullable for
    // tests/previews that have no OPDS lane.
    private val opds: OpdsController? = null,
) : FinderDataProvider {
    // One filters() call per source per app session: Lua evaluators are
    // expensive, and the schema cannot change until the plugin is reloaded.
    private val filterSchemas = PluginFilterSchemaCache()
    private val catalogCoverRefreshAt = ConcurrentHashMap<String, Long>()

    // Pages loaded per OPDS source, newest last. A card is opened by its
    // acquisition URL, so the feed that produced it has to stay reachable.
    private val opdsPages = ConcurrentHashMap<String, List<OpdsPage>>()

    // Resolved OpenSearch search templates per catalog + description document:
    // the description is a static document, re-fetching it per search is waste.
    private val opdsSearchTemplates = ConcurrentHashMap<String, String>()

    override suspend fun sources(): List<CatalogSource> {
        // Local built-ins keep working (Finder routes source.local to the local
        // screens); plugins come from the runtime. Local rows are real library
        // books, so their counts come from the DB by installId.
        val local = localSourceRows(
            pinned = AppSettings.pinnedSources,
            booksCount = library?.libraryBookCount(LocalSources.Books) ?: 0,
            mangaCount = library?.libraryBookCount(LocalSources.Manga) ?: 0,
        )
        val installed = graph.installed.value
        // One count per installed plugin: N is small (tens), and a DAO
        // aggregate per plugin keeps the row honest without a schema change.
        val counts = installed.mapNotNull { plugin ->
            library?.libraryBookCount(plugin.id)?.let { plugin.id to it }
        }.toMap()
        val plugins = mapSources(installed, AppSettings.pinnedSources, counts)
        // Saved OPDS catalogs are ordinary Browse rows. refresh() re-reads the
        // catalog rows from storage; it is a DB read, not a network call.
        opds?.refresh()
        val opdsSources = opds?.catalogs?.value.orEmpty().map { catalog ->
            CatalogSource(
                id = OpdsSources.idOf(catalog.id),
                name = catalog.name,
                language = "",
                type = ContentType.Novel,
                version = "OPDS",
                pinned = OpdsSources.idOf(catalog.id) in AppSettings.pinnedSources,
                local = false,
                // Imported entries of THIS catalog, not the whole local library.
                libraryCount = library?.localBookCountByOrigin(
                    canonicalCatalogUrl(catalog.url),
                ) ?: 0,
            )
        }
        return (local + plugins + opdsSources).distinctBy { it.id }
    }

    override suspend fun togglePin(sourceId: String): Boolean {
        val written = persistPinnedSource(sourceId, settingsStore)
        if (!written) MiruCoreLogger.w(TAG, "event=pin_persist_failed source=$sourceId")
        return written
    }

    override suspend fun page(sourceId: String, filters: CatalogFilters, page: Int): CatalogPage {
        if (LocalSources.isLocal(sourceId)) {
            val kind = LocalSources.kindOf(sourceId)
            val catalog = localCatalog(kind)
            val pageResult = if (catalog != null) {
                localCatalogPageForEntries(catalog.entries, catalog.imported, filters, page)
            } else {
                // No folder store injected (tests/previews): keep the old
                // DB-backed projection instead of returning an empty page.
                localCatalogPage(localBooksFromDb(kind), filters, page)
            }
            return pageResult.withMembership()
        }
        OpdsSources.catalogIdOf(sourceId)?.let {
            // The catalogue screen pages OPDS through OpdsCatalogSession; this
            // branch is the one-shot path (global search, other callers): one
            // query, no feed stack.
            return opdsQueryPage(sourceId, filters)
        }
        val source = graph.source(sourceId) ?: run {
            MiruCoreLogger.w(TAG, "catalog '$sourceId': plugin not loaded")
            throw PluginRequestException(
                PluginError.ScriptError("plugin not loaded"),
                sourceId = sourceId,
                operation = "catalog",
            )
        }
        // Plugin catalogue indexes are 0-based; the app's page is 1-based.
        val index = (page - 1).coerceAtLeast(0)
        // A failed schema load must not turn into "no filters": fall back to
        // the unfiltered catalog page, while the filter sheet gets the error
        // from filterSchema() and offers Retry. The schema is also needed for
        // the query+filters capability, and it is cached per source.
        val schema = filterSchema(sourceId).getOrNull().orEmpty()
        val request = pluginCatalogRequest(filters, schema, source.supportsSearchFilters)
        val operation = when (request) {
            is CatalogRequest.Search -> "search"
            is CatalogRequest.Filtered -> "filters"
            CatalogRequest.List -> "catalog"
        }
        val result = when (request) {
            is CatalogRequest.Search -> source.search(index, request.query)
            is CatalogRequest.Filtered -> source.catalogFiltered(index, request.filters)
            CatalogRequest.List -> source.catalog(index)
        }
        val ok = requirePluginPage(operation, result, sourceId, source.metadata.name)
        val mapped = mapCatalogPage(ok, baseUrl = source.metadata.baseUrl, sourceId = sourceId)
        // A catalogue page carries no genre/status metadata: the provider did
        // the real filtering above, so genres are trusted and ongoingOnly is
        // not re-applied here (it would empty every result). Sort/direction are
        // applied to the loaded page only; `hasMore` still comes from the
        // provider, so filtering cannot hide the fact that more pages exist.
        val books = applyCatalogFilters(
            books = mapped,
            // Plugin filters and the old synthetic genres/status are not
            // composed client-side: the plugin already answered the request.
            filters = filters.copy(query = "", genres = emptySet(), ongoingOnly = false),
            sourceOrderIsLatest = true,
            queryAlreadyApplied = true,
        )
        graph.registerCoverReferers(sourceId, books.mapNotNull { it.coverUrl }, source.metadata.baseUrl)
        recordBooks(sourceId, books)
        MiruCoreLogger.d(TAG, "catalog '$sourceId': ${ok.items.size} raw -> ${books.size} mapped")
        return CatalogPage(books = withMembership(books), hasMore = ok.hasNext)
    }

    // One-shot OPDS query: the catalog root, or the feed's search results for a
    // non-blank query. Feed paging belongs to OpdsCatalogSession.
    private suspend fun opdsQueryPage(sourceId: String, filters: CatalogFilters): CatalogPage {
        val query = filters.query.trim()
        val feed = if (query.isEmpty()) {
            opdsFeedOrThrow(sourceId, null, "catalog")
        } else {
            val root = opdsFeedOrThrow(sourceId, null, "search")
            val template = when (val resolved = opdsSearchUrl(sourceId, root.url)) {
                is Outcome.Ok -> resolved.value
                is Outcome.Err -> throw PluginRequestException(
                    opdsPluginError(resolved.error),
                    sourceId = sourceId,
                    operation = "search",
                )
            } ?: return CatalogPage(emptyList(), hasMore = false)
            opdsFeedOrThrow(sourceId, substituteSearchTerms(template, query), "search")
        }
        val books = applyCatalogFilters(
            books = opdsPageToCatalogBooks(sourceId, feed),
            filters = filters,
            // One feed page carries no dates: keep the server order.
            sourceOrderIsLatest = false,
            // A non-blank query already went through the feed's search.
            queryAlreadyApplied = query.isNotEmpty(),
            // The feed declares its own order (sort links); never re-rank it.
            sortEnabled = false,
        )
        MiruCoreLogger.d(TAG, "opds '$sourceId': ${feed.books.size} feed books -> ${books.size} mapped")
        return CatalogPage(books = withMembership(books), hasMore = feed.nextUrl != null)
    }

    /**
     * One feed page for [OpdsCatalogSession]. Null [url] opens the catalog root;
     * the page is remembered so a card tap can still find its download entry.
     * [includeLinks] is the session layer's publication-feed flag (null on a
     * feed's first page, where the page itself answers it).
     */
    suspend fun opdsFeed(
        sourceId: String,
        url: String?,
        includeLinks: Boolean? = null,
    ): Outcome<OpdsPage> {
        val catalogId = OpdsSources.catalogIdOf(sourceId)
            ?: return Outcome.Err(OpdsFailure.Unknown("not-opds-source"))
        val controller = opds ?: return Outcome.Err(OpdsFailure.Unknown("opds-unavailable"))
        val catalog = controller.catalogs.value.firstOrNull { it.id == catalogId }
            ?: return Outcome.Err(OpdsFailure.Unknown("unknown-catalog"))
        return when (val result = controller.page(catalogId, url)) {
            is Outcome.Err -> result
            is Outcome.Ok -> {
                val page = result.value.withMembership(catalog.url, includeLinks)
                rememberOpdsPage(sourceId, page)
                // One batch per rendered page: the entry cache is what a later
                // book page and an explicit add read, so it never re-fetches.
                cacheOpdsEntries(catalog.url, page, includeLinks)
                Outcome.Ok(page)
            }
        }
    }

    /**
     * Entry URL -> library key for everything on the page, in one join. A card
     * uses it to open the imported book instead of downloading the entry again.
     */
    private suspend fun OpdsPage.withMembership(
        catalogUrl: String,
        includeLinks: Boolean?,
    ): OpdsPage {
        val urls = entryUrls(includeLinks)
        val repo = library ?: return this
        val membership = runCatching {
            repo.originMembership(canonicalCatalogUrl(catalogUrl), urls)
        }.getOrDefault(emptyMap())
        return if (membership.isEmpty()) this else copy(membership = membership)
    }

    // Best effort: a cache write must never fail the feed the user is reading.
    private suspend fun cacheOpdsEntries(
        catalogUrl: String,
        page: OpdsPage,
        includeLinks: Boolean?,
    ) {
        val repo = library ?: return
        val entries = opdsEntryRows(catalogUrl, page, includeLinks, System.currentTimeMillis())
        if (entries.isEmpty()) return
        runCatching {
            withContext(Dispatchers.IO) {
                repo.upsertOpdsEntries(entries)
                repo.pruneOpdsEntries(catalogUrl, MAX_OPDS_ENTRIES)
            }
        }.onFailure { error ->
            MiruCoreLogger.w(TAG, "opds entry cache write failed: ${error::class.simpleName}")
        }
    }

    /**
     * Search template for [feedUrl] (its variable left for the caller to fill,
     * `{searchTerms}` or OPDS 2's `{?query}`), or null when the feed offers no
     * usable search. A link pointing at an OpenSearch description is fetched and
     * its Atom template resolved against that document's URL.
     */
    suspend fun opdsSearchUrl(sourceId: String, feedUrl: String): Outcome<String?> {
        val catalogId = OpdsSources.catalogIdOf(sourceId)
            ?: return Outcome.Err(OpdsFailure.Unknown("not-opds-source"))
        val controller = opds ?: return Outcome.Err(OpdsFailure.Unknown("opds-unavailable"))
        val feed = opdsPages[sourceId].orEmpty().firstOrNull { it.url == feedUrl }
            ?: when (val loaded = opdsFeed(sourceId, feedUrl)) {
                is Outcome.Err -> return loaded
                is Outcome.Ok -> loaded.value
            }
        val link = feed.searchLink ?: return Outcome.Ok(null)
        if (OpdsSearchTemplate.isTemplated(link.href)) return Outcome.Ok(link.href)
        if (link.type?.startsWith(OPENSEARCH_DESCRIPTION_TYPE) != true) return Outcome.Ok(null)
        val cacheKey = "$catalogId|${link.href}"
        opdsSearchTemplates[cacheKey]?.let { return Outcome.Ok(it) }
        val document = when (val text = controller.text(catalogId, link.href)) {
            is Outcome.Err -> return text
            is Outcome.Ok -> text.value
        }
        val template = opensearchTemplate(document) ?: return Outcome.Ok(null)
        val resolved = resolveUrlAgainst(link.href, template)
        opdsSearchTemplates[cacheKey] = resolved
        return Outcome.Ok(resolved)
    }

    private suspend fun opdsFeedOrThrow(sourceId: String, url: String?, operation: String): OpdsPage =
        when (val result = opdsFeed(sourceId, url)) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> throw PluginRequestException(
                opdsPluginError(result.error),
                sourceId = sourceId,
                operation = operation,
            )
        }

    // Newest page last, capped: a tap only needs the feed it came from.
    private fun rememberOpdsPage(sourceId: String, page: OpdsPage) {
        opdsPages[sourceId] = (opdsPages[sourceId].orEmpty().filterNot { it.url == page.url } + page)
            .takeLast(MAX_OPDS_PAGES)
    }

    override suspend fun openOpdsBook(sourceId: String, bookUrl: String): Outcome<String> {
        val catalogId = OpdsSources.catalogIdOf(sourceId)
            ?: return Outcome.Err(OpdsFailure.Unknown("not-opds-source"))
        val controller = opds ?: return Outcome.Err(OpdsFailure.Unknown("opds-unavailable"))
        val catalog = controller.catalogs.value.firstOrNull { it.id == catalogId }
            ?: return Outcome.Err(OpdsFailure.Unknown("unknown-catalog"))
        // Same path as an explicit add: the importer writes catalogue
        // provenance, so the card becomes "in library" on the next page load.
        val localUrl = when (val added = controller.addEntry(catalog.url, bookUrl, null, catalog.name)) {
            is Outcome.Err -> return added
            is Outcome.Ok -> added.value
        }
        val installId = library?.book(localUrl)?.installId ?: LocalBooksInstallId
        return Outcome.Ok(libraryBookKey(installId, localUrl))
    }

    override suspend fun removeOpdsCatalog(sourceId: String) {
        val catalogId = OpdsSources.catalogIdOf(sourceId) ?: return
        val controller = opds ?: return
        val url = controller.catalogs.value.firstOrNull { it.id == catalogId }?.url
        controller.remove(catalogId)
        opdsPages.remove(sourceId)
        opdsSearchTemplates.keys.removeAll { it.startsWith("$catalogId|") }
        // remove() already clears its own host entry; this also covers a stale
        // catalog row that had no credentials registered.
        if (url != null) graph.clearOpdsCatalogHost(catalogId, url)
    }

    // Legacy genre chips are not a plugin schema. Kept for the current filter
    // sheet until the per-source plugin UI lands; it intentionally returns
    // nothing instead of library tags.
    override fun genres(): List<String> = emptyList()

    override suspend fun supportsSearchFilters(sourceId: String): Boolean =
        !LocalSources.isLocal(sourceId) && graph.source(sourceId)?.supportsSearchFilters == true

    // An OPDS feed has no plugin filter schema and no client sort: the screen
    // keeps its filter/sort entry points hidden for it.
    override suspend fun supportsFilters(sourceId: String): Boolean =
        OpdsSources.catalogIdOf(sourceId) == null

    override suspend fun sourceLanguage(sourceId: String): String? =
        if (LocalSources.isLocal(sourceId)) null else graph.source(sourceId)?.metadata?.language

    override suspend fun filterSchema(sourceId: String): Outcome<List<PluginFilter>> {
        if (LocalSources.isLocal(sourceId)) return Outcome.Ok(emptyList())
        // OPDS feeds expose no plugin filter schema; an empty schema is honest,
        // an error would block the filter sheet for a working source.
        if (OpdsSources.catalogIdOf(sourceId) != null) return Outcome.Ok(emptyList())
        return loadFilterSchema(sourceId, graph.source(sourceId), filterSchemas)
    }

    override suspend fun searchSource(sourceId: String, query: String): List<CatalogBook> {
        if (LocalSources.isLocal(sourceId)) {
            val kind = LocalSources.kindOf(sourceId)
            val catalog = localCatalog(kind)
            val books = if (catalog != null) {
                localCatalogSearch(catalog.entries, catalog.imported, query)
            } else {
                localSearch(localBooksFromDb(kind), query)
            }
            return withMembership(books)
        }
        OpdsSources.catalogIdOf(sourceId)?.let {
            val books = opdsQueryPage(sourceId, CatalogFilters(query = query)).books.take(40)
            recordBooks(sourceId, books)
            return books
        }
        val source = graph.source(sourceId)
            ?: throw PluginRequestException(
                PluginError.ScriptError("plugin not loaded"),
                sourceId = sourceId,
                operation = "search",
            )
        val result = try {
            source.search(0, query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ktor/plugin failures may embed the full URL in their message:
            // log the class only, never the throwable.
            MiruCoreLogger.e(TAG, "search '$sourceId' failed error=${e::class.simpleName}")
            throw e
        }
        val ok = requirePluginPage("search", result, sourceId, source.metadata.name)
        val books = mapCatalogPage(ok, baseUrl = source.metadata.baseUrl, sourceId = sourceId).take(12)
        graph.registerCoverReferers(sourceId, books.mapNotNull { it.coverUrl }, source.metadata.baseUrl)
        recordBooks(sourceId, books)
        return withMembership(books)
    }

    override suspend fun libraryUrls(urls: Collection<String>): Set<String> {
        val clean = urls.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (clean.isEmpty()) return emptySet()
        return library?.libraryUrls(clean).orEmpty()
    }

    override suspend fun libraryMembership(
        urls: Collection<String>,
        titles: Collection<String>,
    ): LibraryMembership {
        val cleanUrls = urls.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        val cleanTitles = titles.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (cleanUrls.isEmpty() && cleanTitles.isEmpty()) return LibraryMembership()
        return library?.libraryMembership(cleanUrls, cleanTitles) ?: LibraryMembership()
    }

    override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult =
        bookProvider?.setInLibrary(bookUrl, inLibrary) ?: MembershipResult(success = false)
    override suspend fun localBooks(kind: String): List<LocalBook> = localBooksFromDb(kind)

    override suspend fun importLocal(files: List<PickedImportFile>): ImportSummary {
        val importer = importer ?: return ImportSummary()
        return withContext(Dispatchers.IO) {
            summarizeImports(files.map { importer.import(it.documentUri()) })
        }
    }

    override suspend fun importTree(uri: String): LocalImportResult? {
        val importer = importer ?: return null
        return withContext(Dispatchers.IO) { importer.importTree(Uri.parse(uri)) }
    }

    override suspend fun removeLocal(bookUrl: String) {
        localLibrary?.remove(bookUrl)
    }

    override suspend fun localCatalogFolders(kind: String): List<LocalCatalogFolder> {
        val store = localFolders ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val scan = store.scan(kind)
            store.uris(kind).map { uri ->
                val folderUri = uri.toString()
                LocalCatalogFolder(
                    uri = folderUri,
                    name = store.displayName(uri),
                    fileCount = scan.entries.count { it.folderUri == folderUri },
                    kind = kind,
                )
            }
        }
    }

    override suspend fun addLocalCatalogFolder(kind: String, uri: String) {
        val store = localFolders ?: return
        val added = withContext(Dispatchers.IO) { store.add(kind, Uri.parse(uri)) }
        if (!added) {
            // No persistable read grant means the folder cannot be rescanned
            // after a restart; keep it out of the list instead of failing later.
            MiruCoreLogger.w(
                TAG,
                "local folder grant not persistable: uriHash=${dev.mirutime.core.log.MiruLog.idHash(uri)}",
            )
        }
    }

    override suspend fun removeLocalCatalogFolder(kind: String, uri: String) {
        val store = localFolders ?: return
        withContext(Dispatchers.IO) { store.remove(kind, Uri.parse(uri)) }
    }

    override suspend fun importLocalCatalogFolder(kind: String, uri: String): ImportSummary {
        val store = localFolders ?: return ImportSummary()
        val importer = importer ?: return ImportSummary()
        val treeUri = Uri.parse(uri)
        val entries = withContext(Dispatchers.IO) {
            store.scan(kind).entries.filter { it.folderUri == uri }
        }
        val results = entries.map { entry ->
            withContext(Dispatchers.IO) {
                if (entry.isFolder) {
                    importer.importTree(
                        uri = treeUri,
                        documentId = entry.documentId,
                        addToLibrary = true,
                    )
                } else {
                    importer.import(Uri.parse(entry.uri), addToLibrary = true)
                }
            }
        }.filterNotNull()
        return summarizeImports(results)
    }

    // The globe opens the real plugin site: a Manage-extensions override
    // wins, then the plugin's own metadata. Local/unknown ids have no site.
    override fun baseUrl(id: String): String {
        if (LocalSources.isLocal(id)) return ""
        graph.baseUrlOverride(id)?.let { return it }
        val pluginBase = graph.installed.value
            .firstOrNull { it.id == id }
            ?.baseUrl
            ?.trim()
            .orEmpty()
        return if (pluginBase.startsWith("http://") || pluginBase.startsWith("https://")) {
            pluginBase
        } else {
            ""
        }
    }

    override suspend fun setBaseUrl(id: String, url: String) {
        if (LocalSources.isLocal(id)) return
        val plugin = graph.installed.value.firstOrNull { it.id == id } ?: return
        val oldEffective = baseUrl(id)
        graph.setBaseUrlOverride(id, url)
        val override = graph.baseUrlOverride(id)
        val effective = override ?: plugin.baseUrl.ifBlank { oldEffective }
        val repo = library ?: return
        val existing = repo.install(id)
        repo.saveInstall(
            dev.mirutime.data.library.PluginInstallEntity(
                installId = id,
                pluginId = id,
                baseUrlDefault = plugin.baseUrl,
                baseUrlOverride = override,
                baseUrlEffective = effective,
                version = plugin.version,
                enabled = plugin.enabled,
                installedAt = existing?.installedAt ?: System.currentTimeMillis(),
            ),
        )
        if (oldEffective != effective) {
            repo.rebaseInstall(
                installId = id,
                oldBase = oldEffective,
                newBase = effective,
                override = override,
                effective = effective,
                at = System.currentTimeMillis(),
            )
        }
        graph.invalidateSource(id)
        filterSchemas.invalidate(id)
    }

    // The core source contract exposes settingsSchema() as suspend; this
    // synchronous Finder contract has no place for it yet, so declare nothing
    // rather than inventing a plugin schema.
    override fun settingsSchema(id: String): List<PluginSetting> = emptyList()

    // Local-first list: installed plugins plus the memory/disk repository
    // snapshot. Never touches the network, so Manage extensions can paint rows
    // immediately on a cold start.
    override suspend fun extensions(): List<PluginExtension> {
        val installed = graph.installedNow()
        return mergeExtensions(installed, graph.repoCatalogCached().entries)
    }

    // Explicit refresh: rescan disk, then fetch the repository. A failed fetch
    // keeps the cached rows instead of blanking the list; a partial fetch wins
    // because it already carries the freshest entries. Shared libraries are
    // installed before the rows are returned: a source with a missing
    // require_lib would fail on first open, and that failure is surfaced.
    override suspend fun refreshExtensions(): ExtensionRefreshResult {
        graph.scan()
        val fresh = graph.repoCatalog(force = true)
        val cached = if (fresh.entries.isEmpty() && fresh.error != null) {
            graph.repoCatalogCached()
        } else {
            null
        }
        return refreshExtensionsWithLibraries(
            installed = graph.installed.value,
            fresh = fresh,
            cached = cached,
            syncLibraries = graph::syncLibrariesForInstalled,
        )
    }

    override suspend fun installFromFile(fileName: String, bytes: ByteArray): PluginFileInstall =
        graph.installFromFile(fileName, bytes)

    override suspend fun resetPlugin(id: String): PluginResetResult =
        graph.resetFromRepo(id).also { filterSchemas.invalidate(id) }

    override suspend fun pluginScript(id: String): String? = graph.scriptText(id)

    override suspend fun uninstallMany(ids: Set<String>) {
        ids.forEach {
            graph.uninstall(it)
            filterSchemas.invalidate(it)
        }
    }

    override suspend fun install(id: String): InstallReport =
        graph.install(id).also { filterSchemas.invalidate(id) }

    override suspend fun update(id: String): InstallReport =
        graph.install(id).also { filterSchemas.invalidate(id) }

    override suspend fun updateAll(): InstallReport =
        graph.updateAllInstalled().also { report ->
            report.installed.forEach(filterSchemas::invalidate)
        }

    override suspend fun uninstall(id: String) {
        graph.uninstall(id)
        filterSchemas.invalidate(id)
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        filterSchemas.invalidate(id)
        graph.setEnabled(id, enabled)
    }

    override fun repoUrl(): String = graph.repoUrl()

    override fun defaultRepoUrl(): String = graph.defaultRepoUrl

    // Persist + reload the catalog only. Installing is an explicit action.
    // Only the already-installed scripts' require_lib closures are synced, so
    // switching repositories never downloads the whole library section.
    override suspend fun refreshRepo(url: String): InstallReport {
        graph.setRepoUrl(url)
        val fresh = graph.repoCatalog(force = true)
        val cached = if (fresh.entries.isEmpty() && fresh.error != null) {
            graph.repoCatalogCached()
        } else {
            null
        }
        val libraryFailures = graph.syncLibrariesForInstalled(
            fresh.libraries.ifEmpty { cached?.libraries.orEmpty() },
        )
        val repoFailures = fresh.error?.let { listOf(REPO_FAILURE_KEY to it) }.orEmpty()
        return InstallReport(emptyList(), repoFailures + libraryFailures)
    }

    // Resolve a pasted address against installed plugins by their real base
    // host, then load the book metadata through that plugin. Unknown hosts stay
    // typed as UnknownHost; a failed parse is Invalid — no synthesized book.
    override suspend fun refreshCatalogCover(sourceId: String, bookUrl: String): String? {
        val at = System.currentTimeMillis()
        val last = catalogCoverRefreshAt[bookUrl]
        if (last != null && at - last < CATALOG_COVER_REPAIR_COOLDOWN_MS) return null
        catalogCoverRefreshAt[bookUrl] = at
        val source = graph.source(sourceId) ?: return null
        val meta = try {
            when (val result = source.book(bookUrl)) {
                is Outcome.Err -> return null
                is Outcome.Ok -> result.value
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        }
        val baseUrl = source.metadata.baseUrl
        val cover = resolveCoverUrl(meta.coverUrl, baseUrl) ?: return null
        graph.registerCoverReferers(sourceId, listOf(cover), baseUrl)
        return cover
    }

    override suspend fun resolveLink(url: String): LinkResolution {
        val clean = url.trim()
        val uri = runCatching { Uri.parse(clean) }.getOrNull()
            ?: return LinkResolution.Invalid
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) {
            return LinkResolution.Invalid
        }
        val plugin = graph.installed.value.firstOrNull { installed ->
            installed.enabled && installed.error == null && hostOfUrl(baseUrl(installed.id)) == host
        } ?: return LinkResolution.UnknownHost(host = host, suggestion = null).also {
            MiruLog.event("plugins", "resolve-link", "ok" to false, "reason" to "unknown-host")
        }
        val source = graph.source(plugin.id)
            ?: return LinkResolution.UnknownHost(host = host, suggestion = null)
        val result = try {
            source.book(clean)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reason = sourceErrorReason(e)
            MiruCoreLogger.w(TAG, "resolveLink source=${plugin.id} host=$host failed reason=$reason")
            return LinkResolution.SourceError(host, plugin.name, reason)
        }
        val meta = when (result) {
            is Outcome.Err -> {
                val reason = sourceErrorReason(result.error)
                MiruCoreLogger.w(TAG, "resolveLink source=${plugin.id} host=$host error reason=$reason")
                return LinkResolution.SourceError(host, plugin.name, reason)
            }
            is Outcome.Ok -> result.value
        }
        // A blank title means the plugin ran but its page parser matched
        // nothing (or getBookTitle returned nil). Reporting "not a web link"
        // here sent users chasing a URL problem that did not exist.
        if (meta.title.isBlank()) {
            MiruCoreLogger.w(TAG, "resolveLink source=${plugin.id} host=$host parse=empty-title")
            return LinkResolution.SourceError(host, plugin.name, "empty title")
        }
        val coverUrl = resolveCoverUrl(meta.coverUrl, source.metadata.baseUrl)
        graph.registerCoverReferers(plugin.id, listOfNotNull(coverUrl), source.metadata.baseUrl)
        PluginBookIndex.put(clean, plugin.id)
        PluginBookIndex.putTitle(clean, meta.title)
        MiruLog.event(
            "plugins",
            "resolve-link",
            "ok" to true,
            "plugin" to plugin.id,
            "genres" to meta.genres.size,
        )
        return LinkResolution.Found(
            source = CatalogSource(
                id = plugin.id,
                name = plugin.name,
                language = plugin.language.uppercase(),
                type = plugin.contentType.toAppType(),
                version = plugin.version,
                pinned = plugin.id in AppSettings.pinnedSources,
                iconUrl = resolveCoverUrl(plugin.icon, plugin.baseUrl),
            ),
            book = CatalogBook(
                bookUrl = clean,
                installId = plugin.id,
                title = meta.title.trim().ifBlank { clean },
                rating = parsePluginRating(meta.rating),
                chapters = 0,
                genres = meta.genres,
                ongoing = pluginStatusOngoing(meta.status),
                coverUrl = coverUrl,
            ),
        )
    }

    override fun presets(sourceId: String): List<FilterPreset> {
        val stored = AppSettings.filterPresets[sourceId].orEmpty()
        if (LocalSources.isLocal(sourceId)) return stored
        // Schema is loaded before the sheet in the UI phase; when it is not
        // known yet, leave stored values untouched rather than guessing.
        val schema = filterSchemas.cached(sourceId) ?: return stored
        return stored.map { preset ->
            preset.copy(filters = preset.filters.restrictToSchema(schema))
        }
    }

    // Synchronous by interface contract, so the debounced writer in MiruApp
    // persists these edits off the main thread.
    override fun savePreset(sourceId: String, preset: FilterPreset) {
        AppSettings.filterPresets[sourceId] = mergePresets(presets(sourceId), preset)
    }

    override fun deletePreset(sourceId: String, name: String) {
        AppSettings.filterPresets[sourceId] =
            presets(sourceId).filterNot { it.name.equals(name, ignoreCase = true) }
    }

    override suspend fun localCatalogSnapshot(sourceId: String): LocalCatalogSnapshot? {
        if (!LocalSources.isLocal(sourceId)) return null
        val catalog = localCatalog(LocalSources.kindOf(sourceId)) ?: return null
        return LocalCatalogSnapshot(catalog.entries, catalog.imported)
    }

    override suspend fun localCatalogPage(
        sourceId: String,
        snapshot: LocalCatalogSnapshot,
        filters: CatalogFilters,
        page: Int,
    ): CatalogPage = localCatalogPageForEntries(
        entries = snapshot.entries,
        imported = snapshot.imported,
        filters = filters,
        page = page,
    ).withMembership()

    // Scan every configured folder, then join imported rows by document URI.
    // No DB rows are synthesised for files that were never imported: those stay
    // local-file:// catalogue entries until the user opens one. Metadata and
    // covers for those entries are read into a cache so cards are useful
    // before import.
    private suspend fun localCatalog(kind: String): LocalCatalogSnapshot? {
        val folders = localFolders ?: return null
        var scan = withContext(Dispatchers.IO) { folders.scan(kind) }
        scan.warnings.forEach { MiruCoreLogger.w(TAG, "local scan: $it") }
        registry?.let { reg ->
            scan = scan.copy(
                entries = scan.entries.map { entry ->
                    entry.copy(catalogId = reg.register(entry.uri))
                },
            )
        }
        val repo = library
        val storedImports = if (repo == null) {
            emptyMap()
        } else {
            buildMap {
                for (entry in scan.entries) {
                    val file = repo.localBookFileByUri(entry.uri) ?: continue
                    val book = repo.book(file.bookId) ?: continue
                    put(
                        entry.uri,
                        ImportedLocalBook(
                            bookUrl = book.url,
                            title = book.title,
                            chapters = book.totalChapters,
                            coverUrl = resolveStoredCover(coverStore, book.bookId, book.coverUrl),
                        ),
                    )
                }
            }
        }
        // An imported row with no stored cover still needs the embedded cover
        // from the file itself; a direct open now persists it, but the first
        // card render can happen before that. Do not skip metadata enrichment
        // just because the entry is in the library.
        val needsMetadata = scan.entries.filter { entry ->
            val imported = storedImports[entry.uri]
            imported == null || imported.coverUrl == null
        }
        val entries = if (metadataCache == null || needsMetadata.isEmpty()) {
            scan.entries
        } else {
            val enriched = withContext(Dispatchers.IO) { metadataCache.enrich(needsMetadata) }
                .associateBy { it.uri }
            scan.entries.map { enriched[it.uri] ?: it }
        }
        // Imported mapping: stored cover first, then the embedded/cache cover
        // (same merge rule as Book/Dashboard).
        val byUri = entries.associateBy { it.uri }
        val imported = storedImports.mapValues { (uri, book) ->
            book.copy(coverUrl = book.coverUrl ?: byUri[uri]?.coverUrl)
        }
        return LocalCatalogSnapshot(entries, imported)
    }

    private suspend fun localBooksFromDb(kind: String): List<LocalBook> {
        val repo = library ?: return emptyList()
        val installId = localInstallId(kind)
        return repo.libraryBooks()
            .filter { it.installId == installId }
            .mapNotNull { book ->
                val file = repo.localBookFile(book.bookId) ?: return@mapNotNull null
                LocalBook(
                    bookUrl = book.url,
                    installId = book.installId,
                    fileName = file.displayName,
                    title = book.title,
                    format = file.format,
                    sizeLabel = formatFileSize(file.sizeBytes),
                    count = book.totalChapters,
                    bookId = book.bookId,
                )
            }
    }

    // One batch lookup per rendered list; the screens then update this value
    // locally after a long-press, so no page reload is needed.
    private suspend fun withMembership(books: List<CatalogBook>): List<CatalogBook> {
        if (books.isEmpty()) return books
        return try {
            val membership = libraryMembership(books.map { it.bookUrl }, books.map { it.title })
            books.applyMembership(membership.urls, membership.titles, membership.keys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Badges are an enhancement; a membership read failure must not
            // hide a loaded catalogue page or search result.
            MiruCoreLogger.w(TAG, "membership lookup failed: ${e.message}")
            books
        }
    }

    private suspend fun CatalogPage.withMembership(): CatalogPage =
        if (books.isEmpty()) this else copy(books = withMembership(books))

    // The book route carries only the bookUrl: remember which plugin served
    // each result (and its title, for the synchronous shell header) so the book
    // screen and reader can resolve it without re-querying the catalogue.
    private fun recordBooks(sourceId: String, books: List<CatalogBook>) {
        books.forEach { book ->
            PluginBookIndex.put(book.bookUrl, sourceId)
            PluginBookIndex.putTitle(book.bookUrl, book.title)
        }
    }
}

// One human-readable line per failed source of an install/update pass. The
// root index has no plugin row, so it is named by the caller's localized
// "Repository" label; unknown ids fall back to the raw id rather than vanish.
internal fun installFailureDetails(
    report: InstallReport,
    nameById: Map<String, String> = emptyMap(),
    repositoryLabel: String = "Repository",
): String? = report.failed
    .takeIf { it.isNotEmpty() }
    ?.joinToString("; ") { (id, reason) ->
        val label = if (id == REPO_FAILURE_KEY) repositoryLabel else nameById[id] ?: id
        "$label: $reason"
    }

// Local sources are DB-backed; the "kind" argument is the Finder route kind.
internal fun localInstallId(kind: String): String =
    if (kind == "manga") LocalMangaInstallId else LocalBooksInstallId

// The two built-in rows, built from real counts and the persisted pin set.
internal fun localSourceRows(
    pinned: Set<String>,
    booksCount: Int,
    mangaCount: Int,
): List<CatalogSource> = listOf(
    CatalogSource(
        id = LocalSources.Books,
        name = "Local books",
        language = "",
        type = ContentType.Novel,
        version = "",
        pinned = LocalSources.Books in pinned,
        local = true,
        libraryCount = booksCount,
    ),
    CatalogSource(
        id = LocalSources.Manga,
        name = "Local manga",
        language = "",
        type = ContentType.Manga,
        version = "",
        pinned = LocalSources.Manga in pinned,
        local = true,
        libraryCount = mangaCount,
    ),
)

// A new/replaced preset goes to the front; a NEW name beyond the max is
// refused (the sheet also enforces this, the data layer never overflows).
internal fun mergePresets(current: List<FilterPreset>, preset: FilterPreset): List<FilterPreset> {
    val replaced = current.filterNot { it.name.equals(preset.name, ignoreCase = true) }
    return (listOf(preset) + replaced).take(FilterPreset.Max)
}

private fun hostOfUrl(url: String): String =
    runCatching { URI(url).host.orEmpty() }.getOrNull().orEmpty()
        .lowercase()
        .removePrefix("www.")

// Human-readable source failure for the Add-by-link sheet. Never includes
// cookies or UA; a CF failure is named as such so the user knows the address
// itself was fine. Transport/script failures go through the shared builder:
// its text names the class/HTTP status/scheme+host+path, so the raw
// Network.cause and an unsafe ScriptError.detail (a URL, a query or page
// text) never reach the sheet or the log; a short safe detail is excerpted.
internal fun sourceErrorReason(error: Any): String = when (error) {
    is PluginError.CfChallengeRequired -> "cloudflare challenge"
    is PluginError.Network -> error.sharedDetail()
    is PluginError.DnsFailure -> "DNS failure"
    is PluginError.ScriptError -> when {
        // getBookTitle/getChapterList returning nil is how a plugin reports
        // "nothing parsed" (a 404 page, a removed novel or a changed layout).
        "returned nil" in error.detail -> "page not found or site layout changed"
        else -> error.sharedDetail()
    }
    is PluginError.ResponseTooLarge -> "response too large"
    is PluginError.UserMessage -> buildPluginErrorReport(error).title.orEmpty()
    else -> error::class.simpleName ?: "unknown error"
}

// The sheet embeds the reason in a sentence and the log keeps one line per
// event, so the shared multi-line report is flattened here.
private fun PluginError.sharedDetail(): String =
    buildPluginErrorReport(this).detailText.lineSequence().joinToString(" ").trim()

internal fun LocalBook.toCatalogBook(): CatalogBook = CatalogBook(
    bookUrl = bookUrl,
    installId = installId,
    title = title,
    rating = 0f,
    chapters = count,
    genres = emptyList(),
    ongoing = false,
)

internal fun localSearch(books: List<LocalBook>, query: String): List<CatalogBook> {
    val needle = query.trim()
    return books.asSequence()
        .filter { needle.isEmpty() || it.title.contains(needle, ignoreCase = true) || it.fileName.contains(needle, ignoreCase = true) }
        .take(12)
        .map { it.toCatalogBook() }
        .toList()
}

internal fun localCatalogPage(
    books: List<LocalBook>,
    filters: CatalogFilters,
    page: Int,
    pageSize: Int = 18,
): CatalogPage {
    val query = filters.query.trim()
    // Local imports have no plugin genres/status yet: a genre or "ongoing"
    // filter legitimately yields nothing rather than guessing.
    if (filters.genres.isNotEmpty() || filters.ongoingOnly) {
        return CatalogPage(emptyList(), hasMore = false)
    }
    val matched = books.filter {
        query.isEmpty() || it.title.contains(query, ignoreCase = true) ||
            it.fileName.contains(query, ignoreCase = true)
    }
    // DB rows carry no scan timestamp, so Latest cannot re-rank here: keep the
    // provider order and let the shared pipeline apply the direction/other
    // sorts. The live scan path (localCatalogPageForEntries) does have dates.
    val ordered = applyCatalogFilters(
        books = matched.map { it.toCatalogBook() },
        filters = filters.copy(query = ""),
        sourceOrderIsLatest = false,
        queryAlreadyApplied = true,
    )
    val from = ((page - 1).coerceAtLeast(0)) * pageSize
    return CatalogPage(
        books = ordered.drop(from).take(pageSize),
        hasMore = from + pageSize < ordered.size,
    )
}

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) {
        return if (kb < 10) String.format(Locale.US, "%.1f KB", kb) else "${kb.roundToInt()} KB"
    }
    val mb = kb / 1024.0
    return if (mb < 10) String.format(Locale.US, "%.1f MB", mb) else "${mb.roundToInt()} MB"
}

// Successful schemas are cached by source; a failed filters() call is not
// cached so a transient plugin/network error can be retried on the next open.
internal class PluginFilterSchemaCache {
    private val mutex = Mutex()
    private val values = ConcurrentHashMap<String, List<PluginFilter>>()

    fun cached(sourceId: String): List<PluginFilter>? = values[sourceId]

    fun invalidate(sourceId: String) {
        values.remove(sourceId)
    }

    // Only a successful schema is cached. An Outcome.Err is returned to the
    // caller and retried on the next open instead of being frozen as "this
    // source has no filters".
    suspend fun get(
        sourceId: String,
        load: suspend () -> Outcome<List<PluginFilter>>,
    ): Outcome<List<PluginFilter>> {
        values[sourceId]?.let { return Outcome.Ok(it) }
        return mutex.withLock {
            values[sourceId]?.let { return@withLock Outcome.Ok(it) }
            when (val result = load()) {
                is Outcome.Ok -> {
                    values[sourceId] = result.value
                    result
                }
                is Outcome.Err -> result
            }
        }
    }
}

// A plugin Outcome.Err is a real failure, never an empty page: the finder
// screen catches PluginRequestException and renders its error/Retry state
// instead of "Nothing found". Kept top-level so it is unit-testable.
internal fun requirePluginPage(
    operation: String,
    result: Outcome<CoreCatalogPage>,
    sourceId: String? = null,
    sourceName: String? = null,
): CoreCatalogPage =
    when (result) {
        is Outcome.Ok -> result.value
        is Outcome.Err -> {
            MiruCoreLogger.w(TAG, "$operation '$sourceId' returned ${result.error::class.simpleName}")
            throw PluginRequestException(
                result.error as? PluginError
                    ?: PluginError.ScriptError(result.error::class.simpleName ?: "unknown error"),
                sourceId = sourceId,
                sourceName = sourceName,
                operation = operation,
            )
        }
    }

// Loads one source's getFilterList schema. Kept top-level and source-typed so
// the error/empty distinction is testable without a live PluginGraph.
internal suspend fun loadFilterSchema(
    sourceId: String,
    source: PluginSource?,
    cache: PluginFilterSchemaCache,
): Outcome<List<PluginFilter>> {
    if (source == null) {
        return Outcome.Err(PluginError.ScriptError("plugin not loaded"))
    }
    return cache.get(sourceId) {
        when (val result = source.filters()) {
            is Outcome.Err -> {
                // Class only: UserMessage/ScriptError text may carry a title.
                MiruCoreLogger.w(TAG, "filters '$sourceId' failed ${result.error::class.simpleName}")
                result
            }
            is Outcome.Ok -> Outcome.Ok(parsePluginFilters(result.value))
        }
    }
}

// Pure mapping, kept top-level so it is unit-testable without Android (and so
// a buggy plugin can never inject duplicate Lazy keys or crash the grid: blank
// entries are dropped, duplicate URLs collapse, the list is capped).
internal fun mapCatalogPage(
    page: CoreCatalogPage,
    cap: Int = 300,
    baseUrl: String = "",
    sourceId: String = "",
): List<CatalogBook> {
    val seen = HashSet<String>()
    val books = ArrayList<CatalogBook>(minOf(page.items.size, cap.coerceAtLeast(0)))
    for (item in page.items) {
        if (books.size >= cap) break
        val url = item.url.trim()
        val title = item.title.trim()
        if (url.isEmpty() || title.isEmpty()) continue
        if (!seen.add(url)) continue
        // Plugins publish "9.5/10" as often as a bare 0-5 number; reuse the
        // book-page parser so catalogue cards get the same score.
        val rating = parsePluginRating(item.rating)
        books += CatalogBook(
            bookUrl = url,
            installId = sourceId,
            title = title,
            rating = rating,
            chapters = 0,
            genres = emptyList(),
            ongoing = false,
            coverUrl = resolveCoverUrl(item.coverUrl, baseUrl),
        )
    }
    return books
}

// The one classification of a feed's links: on a publication feed they are
// entries (Gutenberg search results), on a navigation feed they are sections.
// [includeLinks] is the session layer's flag; null means "first page", where the
// page itself answers it.
internal fun OpdsPage.linksArePublications(includeLinks: Boolean?): Boolean =
    includeLinks ?: (books.isNotEmpty() || nextUrl != null)

/** Entry URLs on the page whose library membership the cards need. */
internal fun OpdsPage.entryUrls(includeLinks: Boolean?): List<String> =
    (books.mapNotNull { it.entryUrl } +
        if (linksArePublications(includeLinks)) links.map { it.url } else emptyList())
        .distinct()

/** What the entry cache remembers about one feed page, in one pass. */
internal fun opdsEntryRows(
    catalogUrl: String,
    page: OpdsPage,
    includeLinks: Boolean?,
    now: Long,
): List<OpdsEntryEntity> {
    val direct = page.books.mapNotNull { book ->
        val entryUrl = book.entryUrl ?: return@mapNotNull null
        OpdsEntryEntity(
            catalogUrl = catalogUrl,
            entryUrl = entryUrl,
            // The page the entry was listed in: a cache miss re-resolves
            // the entry from here, never from the entry URL itself.
            feedUrl = page.url,
            title = book.title,
            author = book.author.orEmpty(),
            description = book.summary.orEmpty(),
            coverUrl = book.coverUrl,
            formatsJson = OpdsEntryOptions.encode(book.options),
            updatedAt = now,
        )
    }
    // A publication feed lists entries as links to their own feed: those still
    // need metadata for the detail page, formats resolve on demand.
    val indirect = if (page.linksArePublications(includeLinks)) {
        page.links.map { link ->
            OpdsEntryEntity(
                catalogUrl = catalogUrl,
                entryUrl = link.url,
                feedUrl = page.url,
                title = link.title,
                author = link.author.orEmpty(),
                description = link.subtitle.orEmpty(),
                coverUrl = link.coverUrl,
                formatsJson = "[]",
                updatedAt = now,
            )
        }
    } else {
        emptyList()
    }
    return (direct + indirect).distinctBy { it.entryUrl }
}

// One feed page -> catalogue cards. Navigation entries never reach
// OpdsPage.books (the controller keeps them in links), so only publications
// map here. An entry with no supported acquisition still appears: its card is
// informative, just not openable.
//
// The card is keyed by the entry URL, not the file URL: that is the identity
// `opds_entries` and `local_book_files.origin_entry_url` both use, so a card
// can find the book it was already added as.
internal fun opdsPageToCatalogBooks(
    sourceId: String,
    page: OpdsPage,
    /**
     * True for a result feed: its links are publications even on the last page,
     * where `nextUrl` is already null.
     */
    includeLinks: Boolean = page.nextUrl != null,
): List<CatalogBook> {
    val direct = page.books.mapIndexed { index, book ->
        val entryUrl = book.entryUrl
        CatalogBook(
            bookUrl = entryUrl ?: book.downloadUrl ?: book.coverUrl
                ?: "$sourceId#${page.url}#$index",
            installId = sourceId,
            title = book.title,
            rating = 0f,
            chapters = 0,
            genres = book.categories,
            ongoing = false,
            coverUrl = book.coverUrl,
            localFormat = book.format?.name,
            notImported = true,
            libraryBookKey = entryUrl?.let { page.membership[it] },
        )
    }
    // A paged feed lists publications as links to their own feed (Gutenberg's
    // search results carry no acquisition links). They are books, not sections;
    // the detail step resolves the acquisition one feed down.
    if (!includeLinks) return direct
    val indirect = page.links.map { link ->
        CatalogBook(
            bookUrl = link.url,
            installId = sourceId,
            title = link.title,
            rating = 0f,
            chapters = 0,
            genres = emptyList(),
            ongoing = false,
            coverUrl = link.coverUrl,
            localFormat = null,
            notImported = true,
            libraryBookKey = page.membership[link.url],
        )
    }
    return direct + indirect
}

// Mirrors the page/sort filters onto the PluginError vocabulary the screen
// already renders. Class names and HTTP status only: never a URL or title.
internal fun opdsPluginError(error: MiruError): PluginError = when (error) {
    is OpdsFailure.Network -> PluginError.Network("opds", status = error.status)
    is OpdsFailure.TooLarge -> PluginError.ResponseTooLarge(error.limitMb.toLong() * 1024 * 1024)
    else -> PluginError.ScriptError(error::class.simpleName ?: "opds")
}

internal fun substituteSearchTerms(template: String, query: String): String =
    OpdsSearchTemplate.substitute(template, query)

// OpenSearch 1.1 description: `<Url type="application/atom+xml" template="..."/>`.
// Only attributes matter here, so this stays regex-based and dependency-free.
internal fun opensearchTemplate(document: String): String? =
    OSD_URL_ELEMENT.findAll(document).firstNotNullOfOrNull { element ->
        val type = OSD_TYPE_ATTRIBUTE.find(element.value)?.groupValues?.get(1).orEmpty()
        OSD_TEMPLATE_ATTRIBUTE.find(element.value)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.takeIf { type.startsWith(OPENSEARCH_ATOM_TYPE) && SEARCH_TERMS in it }
    }

private val OSD_URL_ELEMENT = Regex("""<Url\b[^>]*>""", RegexOption.IGNORE_CASE)
private val OSD_TYPE_ATTRIBUTE = Regex("""\btype\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val OSD_TEMPLATE_ATTRIBUTE = Regex("""\btemplate\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

// A description may live on another host than the catalog, so a relative
// template resolves against the document itself. The token survives the URI
// parse, which rejects a literal `{searchTerms}`.
internal fun resolveUrlAgainst(documentUrl: String, template: String): String {
    val marked = template.replace(SEARCH_TERMS, "miru-search-terms")
    val resolved = runCatching { URI(documentUrl).resolve(marked).toString() }.getOrDefault(marked)
    return resolved.replace("miru-search-terms", SEARCH_TERMS)
}

// A plugin may declare a cover as an absolute URL, a protocol-relative
// address ("//cdn...") or a path relative to its base URL. Resolve it once// here so the image loader only ever sees an absolute address; blank stays
// null and an unresolvable relative URL (no base) passes through untouched.
internal fun resolveCoverUrl(raw: String?, baseUrl: String): String? {
    val cover = raw?.trim().orEmpty()
    if (cover.isEmpty()) return null
    val lowerCover = cover.lowercase()
    if (cover.startsWith("//")) {
        val scheme = baseUrl.substringBefore("://").trim().lowercase()
            .takeIf { it == "http" || it == "https" } ?: "https"
        return "$scheme:$cover"
    }
    if (lowerCover.startsWith("http://") || lowerCover.startsWith("https://")) return cover
    val base = baseUrl.trim()
    if (base.isEmpty()) return cover
    // Query/fragment-only references attach to the base as-is; java.net.URI
    // would drop the base's last path segment for a query on a slash-less base.
    if (cover.startsWith("?") || cover.startsWith("#")) return base + cover
    return try {
        // A base without a trailing slash is still a directory base: append one
        // so a bare relative path nests under it (URI would treat it as a file).
        val directoryBase = if (base.endsWith("/")) base else "$base/"
        URI(directoryBase).resolve(cover).toString()
    } catch (e: IllegalArgumentException) {
        // Unparseable base/cover: keep the previous best-effort concatenation.
        base.trimEnd('/') + "/" + cover.trimStart('/')
    }
}

// A refresh that failed without any entries must not drop the rows already on
// screen: fall back to the cached catalog. A partial fetch is still the newer
// truth and wins.
internal fun refreshRepoEntries(fresh: RepoCatalog, cached: RepoCatalog?): List<IndexEntry> =
    if (fresh.entries.isEmpty() && fresh.error != null) {
        cached?.entries.orEmpty()
    } else {
        fresh.entries
    }

// Refresh = sync the require_lib closure of installed scripts only, then merge
// the rows. Not every repository library is installed: only what an installed
// plugin actually requires (or reports as missing/cyclic). Fresh libs win; the
// cached snapshot only supplies them when the network failed before the root
// index was parsed.
internal suspend fun refreshExtensionsWithLibraries(
    installed: List<InstalledPlugin>,
    fresh: RepoCatalog,
    cached: RepoCatalog?,
    syncLibraries: suspend (List<IndexEntry>) -> List<Pair<String, String>>,
): ExtensionRefreshResult {
    val libraries = fresh.libraries.ifEmpty { cached?.libraries.orEmpty() }
    val libraryFailures = syncLibraries(libraries)
    // A failed fetch (e.g. the local-network grant is missing) must name its
    // reason instead of silently keeping the cached rows.
    val repoFailures = fresh.error?.let { listOf(REPO_FAILURE_KEY to it) }.orEmpty()
    return ExtensionRefreshResult(
        extensions = mergeExtensions(installed, refreshRepoEntries(fresh, cached)),
        failed = repoFailures + libraryFailures,
    )
}

// Extensions list = installed plugins + repository-only entries. Installed
// metadata wins on duplicate ids (the local script is authoritative); the
// catalog version wins for the "update available" badge when present, with
// the installed version as fallback for indexes that omit it.
internal fun mergeExtensions(
    installed: List<InstalledPlugin>,
    repository: List<IndexEntry>,
): List<PluginExtension> {
    val repoById = repository.associateBy { it.id }
    val seen = HashSet<String>(installed.size + repository.size)
    val result = ArrayList<PluginExtension>(installed.size + repository.size)
    for (plugin in installed) {
        if (!seen.add(plugin.id)) continue
        val index = repoById[plugin.id]
        result += PluginExtension(
            id = plugin.id,
            name = plugin.name,
            language = plugin.language.uppercase(),
            type = plugin.contentType.toAppType(),
            availableVersion = index?.version?.trim()?.takeIf { it.isNotEmpty() } ?: plugin.version,
            installedVersion = plugin.version,
            enabled = plugin.enabled && plugin.error == null,
            // The installed script's own icon wins: it is what the plugin
            // ships, the index entry only fills gaps.
            iconUrl = resolveCoverUrl(plugin.icon, plugin.baseUrl)
                ?: index?.icon?.trim()?.takeIf { it.isNotEmpty() },
            error = plugin.errorType ?: plugin.error?.let { PluginError.ScriptError(it) },
            enabledByUser = plugin.enabled,
            repoAvailable = index != null,
        )
    }
    for (entry in repository) {
        if (!seen.add(entry.id)) continue
        result += PluginExtension(
            id = entry.id,
            name = entry.name?.trim()?.takeIf { it.isNotEmpty() } ?: entry.id,
            language = entry.language.orEmpty().uppercase(),
            type = indexContentType(entry.contentType),
            availableVersion = entry.version?.trim().orEmpty(),
            installedVersion = null,
            enabled = false,
            iconUrl = entry.icon,
        )
    }
    return result
}

// Index `content_type` ("manga"/"novel"/"video") -> app type; unknown or
// missing values are novels, matching the catalog default.
internal fun indexContentType(raw: String?): ContentType = when (raw?.trim()?.lowercase()) {
    "manga" -> ContentType.Manga
    "video" -> ContentType.Video
    else -> ContentType.Novel
}

// Installed+enabled plugins -> Browse sources, deduped by id (first wins).
// `counts` maps plugin id to its library size; a missing id is simply 0.
internal fun mapSources(
    installed: List<InstalledPlugin>,
    pinned: Set<String> = emptySet(),
    counts: Map<String, Int> = emptyMap(),
): List<CatalogSource> {
    val seen = HashSet<String>()
    val sources = ArrayList<CatalogSource>(installed.size)
    for (plugin in installed) {
        if (!plugin.enabled || plugin.error != null) continue
        if (!seen.add(plugin.id)) continue
        sources += CatalogSource(
            id = plugin.id,
            name = plugin.name,
            language = plugin.language.uppercase(),
            type = plugin.contentType.toAppType(),
            version = plugin.version,
            pinned = plugin.id in pinned,
            libraryCount = counts[plugin.id] ?: 0,
            iconUrl = resolveCoverUrl(plugin.icon, plugin.baseUrl),
        )
    }
    return sources
}

internal fun CoreContentType.toAppType(): ContentType = when (this) {
    CoreContentType.NOVEL -> ContentType.Novel
    CoreContentType.MANGA -> ContentType.Manga
    CoreContentType.VIDEO -> ContentType.Video
    CoreContentType.AUDIO -> ContentType.Audio
}