package dev.mirutime.app.features.book.data

import dev.mirutime.core.log.MiruLog
import dev.mirutime.app.core.buildPluginErrorReport
import dev.mirutime.app.core.uriHostPath
import dev.mirutime.app.di.PluginGraph
import dev.mirutime.app.di.TextFetchResult
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.book.domain.displaySubtitle
import dev.mirutime.app.features.finder.data.LocalSafBookSource
import dev.mirutime.app.features.finder.data.isLocalFileBook
import dev.mirutime.app.features.finder.data.localFileChapterUrl
import dev.mirutime.app.features.finder.data.localFileDocumentUri
import dev.mirutime.app.features.finder.data.resolveCoverUrl
import dev.mirutime.app.features.finder.data.toAppType
import dev.mirutime.app.features.downloads.data.DownloadFileStore
import dev.mirutime.app.features.library.data.BookCoverStore
import dev.mirutime.app.features.library.data.DirectSafParserVersion
import dev.mirutime.app.features.library.data.LocalBookProvider
import dev.mirutime.app.features.library.data.LocalMangaInstallId
import dev.mirutime.app.features.library.data.resolveStoredCover
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.reader.document.chapterKey
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.core.reader.ChapterSource
import dev.mirutime.core.text.chapterHtml
import dev.mirutime.core.text.textToParagraphHtml
import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.ChapterContent as CoreChapterContent
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.VideoStream
import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.network.cf.CfChallengeRequiredException
import dev.mirutime.core.network.security.ResponseTooLargeException
import dev.mirutime.core.network.security.SsrfBlockedException
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterStatusSnapshot
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.LibraryBookFiles
import dev.mirutime.data.library.BookWriteLocks
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.bookKeyUrl
import dev.mirutime.data.library.libraryBookKey
import dev.mirutime.data.library.LocalBookFileEntity
import dev.mirutime.data.library.TagEntity
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONArray

// Real plugin-backed book provider. For a plugin book it resolves the source
// via PluginBookIndex, then loads BookMeta and the chapter list independently
// (the host fetches the chapter HTML itself, then asks the plugin to parse
// it). Local `local://` books go through LocalBookProvider; any other URL
// (including unknown/fixture schemes) has no provider and yields null/empty.
class PluginBookDataProvider(
    private val graphOrNull: PluginGraph? = null,
    private val library: LibraryRepository? = null,
    private val coverStore: BookCoverStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
    // Local `local://<uuid>` books resolve through local_book_files, not a plugin.
    private val local: LocalBookProvider? = null,
    // Direct SAF books (`local-file://`) read the user's document in place.
    private val localSaf: LocalSafBookSource? = null,
    // Same app-private store the download engine uses. When supplied, a
    // successful reader fetch is persisted as a body/page cache row.
    private val chapterFiles: DownloadFileStore? = null,
    // Enrichment runs here after the membership row is committed; tests can
    // inject a controlled scope. File cleanup goes through the same app-private
    // filesystem the importer uses (never a SAF source document).
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val fileCleanup: suspend (List<LibraryBookFiles>) -> Unit = {
        local?.removeFiles(it)
    },
    // Offline video cache is owned by media3, cleaned after the DB commit.
    private val onBookRemoved: suspend (String) -> Unit = {},
    // Chapter-list tests run without a real PluginGraph (it needs an Android
    // Context), so the handle lookup is injectable. Production uses the graph.
    private val pluginSourceFor: suspend (String) -> PluginSource? = { id ->
        graphOrNull?.source(id)
    },
    private val bookLocks: BookWriteLocks = BookWriteLocks(),
) : BookDataProvider {
    private val graph: PluginGraph
        get() = requireNotNull(graphOrNull) { "plugin graph is not configured" }

    // A route passes an opaque (installId + URL) key; plugins and local
    // adapters need the real URL. DB calls through LibraryRepository accept
    // the key directly and decode it there.
    private fun realUrl(bookKey: String): String = bookKeyUrl(bookKey)

    // Installed plugins keep the display name; the DB row only stores the id.
    // Library cards already resolve through this map, so the book page and the
    // shell header must use the same name instead of the raw install id.
    private suspend fun sourceLabel(id: String, fallback: String = id): String {
        val live = graphOrNull?.installed?.value.orEmpty()
        // A cold start can open a cached library row before bootstrap has
        // published the installed list; wait for that scan once so the page
        // never caches a raw install id as the display name.
        val installed = live.ifEmpty { graphOrNull?.installedNow().orEmpty() }
        return installed
            .firstOrNull { it.id == id }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: fallback
    }
    private val details = ConcurrentHashMap<String, BookDetails>()
    // Last typed plugin failure per book, read by BookScreen after a null
    // metadata result; cleared by the next successful load.
    private val pluginErrors = ConcurrentHashMap<String, PluginError>()
    // Last cover repair per bookUrl: a broken cover is re-resolved once per
    // window, never per composition/scroll of a grid of covers.
    private val coverRefreshAt = ConcurrentHashMap<String, Long>()
    private val chapterLists = ConcurrentHashMap<String, List<BookChapter>>()
    private val chapterUrls = ConcurrentHashMap<String, Map<Int, String>>()
    private val chapterTitles = ConcurrentHashMap<String, Map<Int, String>>()
    // Status snapshot is several queries; a chapter list page asks for it on
    // every call. Reuse it within one generation, and let a short TTL cover
    // writes made by other lanes (downloads/translation) without exposing a
    // second owner of reader state.
    private val chapterStatusSnapshots = ConcurrentHashMap<String, CachedChapterStatus>()
    private val chapterStatusGenerations = ConcurrentHashMap<String, Long>()
    // Change-only chapter-status diagnostics: the book page polls while a
    // queue is active, and an unchanged snapshot must not spam logcat.
    private val lastStatusLog = ConcurrentHashMap<Long, Pair<ChapterStatusCounters, Long>>()
    // Metadata fetched for the book page before the row exists; lets an add
    // persist the row without a second network round-trip.
    private val pluginMeta = ConcurrentHashMap<String, BookMeta>()
    // Membership mutations for one book must not interleave: add→remove→add
    // is a last-intent operation, not a read-modify-write toggle.
    private val membershipLocks = ConcurrentHashMap<String, Mutex>()
    // Only the newest explicit target inside the mutex may write. A cancelled
    // UI coroutine can still finish a started DB call; this makes it harmless.
    private val latestMembershipTarget = ConcurrentHashMap<String, Boolean>()
    // One enrichment pass per book at a time: chapters + cover + tags.
    private val enrichmentJobs = ConcurrentHashMap<String, Job>()
    // Direct SAF file identity (URI + mtime + size hash). A changed file drops
    // the provider chapter cache before the page asks for the new list.
    private val localSafHashes = ConcurrentHashMap<String, String>()
    // Cache-on-read: writes the same body/page tables as the download engine,
    // never a download_tasks row. Null in providers without a library/store.
    private val chapterCache: ChapterCacheStore? by lazy {
        val repo = library ?: return@lazy null
        val files = chapterFiles ?: return@lazy null
        ChapterCacheStore(LibraryChapterCacheRepository(repo), files, now, bookLocks)
    }

    override fun invalidateBookCache(bookUrl: String) {
        details.remove(bookUrl)
        pluginMeta.remove(bookUrl)
        chapterLists.remove(bookUrl)
        chapterUrls.remove(bookUrl)
        chapterTitles.remove(bookUrl)
        invalidateChapterStatus(bookUrl)
    }

    override suspend fun book(bookUrl: String): BookDetails? =
        when {
            isLocalFileBook(realUrl(bookUrl)) -> loadLocalSafBook(bookUrl)
            LocalBookProvider.isLocalBook(realUrl(bookUrl)) -> loadLocalBook(bookUrl)
            else -> loadBook(bookUrl, persist = false)
        }

    // Repair path for a dead/rotated cover (Hitomi rotates CDN tokens, a site
    // may replace a 404). Writes only book_covers.remoteUrl + the local file —
    // never books.coverUrl — and never re-saves the book through provider.book.
    // The old file/URI survives a failed forced fetch (BookCoverStore).
    override suspend fun refreshCover(bookUrl: String): Outcome<String?> {
        if (isLocalFileBook(realUrl(bookUrl)) || LocalBookProvider.isLocalBook(realUrl(bookUrl))) {
            return Outcome.Ok(null)
        }
        val store = coverStore ?: return Outcome.Ok(null)
        val row = runCatching { library?.book(bookUrl) }.getOrNull() ?: return Outcome.Ok(null)
        val at = now()
        val last = coverRefreshAt[bookUrl]
        if (last != null && at - last < COVER_REFRESH_COOLDOWN_MS) {
            // Cooldown: keep what is already displayed, make no request.
            MiruLog.d("covers", "event=cover-repair book=${row.bookId} result=cooldown")
            return Outcome.Ok(store.localCoverUrl(row.bookId))
        }
        coverRefreshAt[bookUrl] = at
        MiruLog.d("covers", "event=cover-repair book=${row.bookId} result=start")
        val sourceId = PluginBookIndex.sourceForBook(bookUrl)
            ?: row.installId.takeIf { it.isNotBlank() }
            ?: return Outcome.Err(PluginError.ScriptError("cover source unavailable"))
        val source = pluginSourceFor(sourceId)
            ?: return Outcome.Err(PluginError.ScriptError("cover source unavailable"))
        val meta = when (val result = source.book(realUrl(bookUrl))) {
            is Outcome.Err -> return Outcome.Err(result.error)
            is Outcome.Ok -> result.value
        }
        val baseUrl = source.metadata.baseUrl
        val coverUrl = resolveCoverUrl(meta.coverUrl, baseUrl)
            ?: return Outcome.Ok(store.localCoverUrl(row.bookId))
        graphOrNull?.registerCoverReferers(sourceId, listOf(coverUrl), baseUrl)
        return Outcome.Ok(store.ensureCover(row.bookId, sourceId, coverUrl, force = true))
    }

    // Explicit metadata refresh: the same fields loadBook writes, but always
    // re-fetched and written back. A blank field keeps its stored value so a
    // source hiccup (or a genuinely absent field) never wipes data (FR-012);
    // membership, shelf, progress and read flags come from the existing row.
    override suspend fun refreshMetadata(bookUrl: String): Outcome<Unit> {
        if (LocalBookProvider.isLocalBook(realUrl(bookUrl)) || isLocalFileBook(realUrl(bookUrl))) {
            return Outcome.Ok(Unit)
        }
        val sourceId = PluginBookIndex.sourceForBook(bookUrl)
            ?: return Outcome.Err(PluginError.ScriptError("metadata source unavailable"))
        val source = pluginSourceFor(sourceId)
            ?: return Outcome.Err(PluginError.ScriptError("metadata source unavailable"))
        val existing = library?.book(sourceId, realUrl(bookUrl)) ?: library?.book(bookUrl)
            ?: return Outcome.Err(PluginError.ScriptError("book is not stored"))
        val meta = when (val result = source.book(realUrl(bookUrl))) {
            is Outcome.Err -> {
                pluginErrors[bookUrl] = result.error as? PluginError
                    ?: PluginError.ScriptError(result.error::class.simpleName ?: "unknown error")
                return Outcome.Err(result.error)
            }
            is Outcome.Ok -> result.value
        }
        pluginErrors.remove(bookUrl)
        pluginMeta[bookUrl] = meta
        val baseUrl = source.metadata.baseUrl
        val title = meta.title.trim().ifBlank {
            PluginBookIndex.titleFor(bookUrl)
                ?: existing.title.takeIf { it.isNotBlank() }
                ?: source.metadata.name
        }
        val coverUrl = resolveCoverUrl(meta.coverUrl, baseUrl)
        graphOrNull?.registerCoverReferers(sourceId, listOfNotNull(coverUrl), baseUrl)
        val timestamp = now()
        val readCount = library?.readChapterIndexes(existing.bookId)?.size ?: 0
        val freshRating = parsePluginRating(meta.rating)
        val built = BookDetails(
            bookUrl = bookUrl,
            title = title,
            source = sourceLabel(
                sourceId,
                source.metadata.name.ifBlank { hostOf(realUrl(bookUrl)) },
            ),
            installId = sourceId,
            author = meta.author?.takeIf { it.isNotBlank() }
                ?: existing.authorRaw.ifBlank { existing.author }.ifBlank { "Unknown" },
            originLanguage = meta.extras["language"]?.takeIf { it.isNotBlank() }
                ?: meta.extras["originLanguage"]?.takeIf { it.isNotBlank() }
                ?: existing.originLanguage,
            contentType = source.metadata.contentType.toAppType(),
            // A freshly parsed rating wins; a blank one keeps the stored value
            // instead of wiping it, so "refresh restores, never erases".
            rating = mergedRating(freshRating, existing.rating.toFloat()),
            ongoing = pluginStatusOngoing(meta.status),
            updatedLabel = meta.lastUpdated.orEmpty(),
            totalChapters = existing.totalChapters,
            readCount = readCount,
            readMinutes = 0,
            ttsMinutes = 0,
            inLibrary = existing.inLibrary,
            shelfId = existing.shelfId,
            genres = (meta.genres + extraTags(meta.extras)).distinct(),
            description = mergedText(meta.description, existing.description),
            sourceUrl = resolveCoverUrl(realUrl(bookUrl), baseUrl) ?: realUrl(bookUrl),
            authUrl = null,
            chapters = emptyList(),
            reviews = emptyList(),
            coverUrl = coverUrl ?: existing.coverUrl,
            subtitle = displaySubtitle(
                subtitle = meta.extras["subtitle"] ?: existing.subtitle,
                altTitles = parseAltTitles(extraAltTitles(meta.extras))
                    .ifEmpty { parseAltTitles(existing.altTitlesJson) },
                title = title,
            ),
        )
        library?.saveBook(buildEntity(realUrl(bookUrl), sourceId, built, meta, existing, timestamp))
        library?.let { repo -> saveTags(repo, existing.bookId, meta, timestamp) }
        PluginBookIndex.put(bookUrl, sourceId)
        PluginBookIndex.putTitle(bookUrl, title)
        // Re-download the cover file only when the source exposes a URL; a
        // failed fetch keeps the previous file (BookCoverStore).
        if (coverUrl != null) {
            coverStore?.ensureCover(existing.bookId, sourceId, coverUrl, force = true)
        }
        details.remove(bookUrl)
        MiruLog.event(
            "book",
            "metadata-refresh",
            "book" to MiruLog.idHash(bookUrl),
            "rating" to built.rating,
            "cover" to (coverUrl != null),
        )
        return Outcome.Ok(Unit)
    }

    override suspend fun ensureHistoryBook(bookUrl: String) {
        if (isLocalFileBook(realUrl(bookUrl))) {
            ensureLocalSafHistory(bookUrl)
            return
        }
        if (LocalBookProvider.isLocalBook(realUrl(bookUrl))) {
            // The import already created the row, chapters and copy.
            loadLocalBook(bookUrl)
            return
        }
        // Reader open must not wait for a network cover; the library backfill
        // and the explicit add action handle cover downloads.
        loadBook(bookUrl, persist = true)
        // The book page may have loaded its chapter list before any DB row
        // existed. Persist that cached list now so bulk read/translate can
        // attach to chapter ids (never re-fetch the network list here).
        persistCachedChapters(bookUrl)
    }

    private suspend fun persistCachedChapters(bookUrl: String) {
        val repo = library ?: return
        val cached = chapterLists[bookUrl] ?: return
        val row = repo.book(bookUrl) ?: return
        if (repo.chapters(row.bookId).isNotEmpty()) return
        repo.replaceChapters(
            bookId = row.bookId,
            seeds = cached.map { chapter ->
                ChapterSeed(
                    index = chapter.index,
                    url = chapter.url ?: chapterKey(bookUrl, chapter.index),
                    title = chapter.title,
                )
            },
            at = now(),
        )
    }

    private suspend fun loadBook(bookUrl: String, persist: Boolean): BookDetails? {
        if (!persist) {
            details[bookUrl]?.let { cached ->
                PluginBookIndex.put(bookUrl, cached.installId)
                PluginBookIndex.putTitle(bookUrl, cached.title)
                return overlayLibraryState(bookUrl, cached)
            }
        }
        // A cached book opens from its persisted row: no plugin call, so no
        // network and no CF challenge. Only an explicit refresh or the
        // scheduled library update asks the source for fresh chapters.
        library?.book(bookUrl)?.let { row ->
            val cached = bookDetailsFromRow(row)
            details[bookUrl] = cached
            PluginBookIndex.put(bookUrl, row.installId)
            PluginBookIndex.putTitle(bookUrl, row.title)
            return overlayLibraryState(bookUrl, cached)
        }
        val sourceId = PluginBookIndex.sourceForBook(bookUrl) ?: return null
        val source = pluginSourceFor(sourceId) ?: return null
        // Metadata only: the chapter list is a separate (possibly slow) call
        // so the page header never waits for it.
        val metaResult = source.book(realUrl(bookUrl))
        val meta = metaResult.getOrNull()
        (metaResult as? Outcome.Err)?.let { failure ->
            pluginErrors[bookUrl] = failure.error as? PluginError
                ?: PluginError.ScriptError(failure.error::class.simpleName ?: "unknown error")
        }
        if (meta != null) pluginErrors.remove(bookUrl)
        if (meta == null) {
            // Offline reopen: the DB row is authoritative enough to restore the
            // reader even when the plugin cannot reach its site.
            val existing = library?.book(sourceId, realUrl(bookUrl)) ?: library?.book(bookUrl)
                ?: return null
            val built = bookDetailsFromRow(existing)
            details[bookUrl] = built
            PluginBookIndex.put(bookUrl, existing.installId)
            PluginBookIndex.putTitle(bookUrl, existing.title)
            return overlayLibraryState(bookUrl, built)
        }
        pluginMeta[bookUrl] = meta
        val baseUrl = source.metadata.baseUrl
        // The DB row owns identity flags; a fresh plugin load must never reset
        // library membership, shelf, progress or the original added time.
        val existing = library?.book(sourceId, realUrl(bookUrl))
        val title = meta.title.trim().ifBlank {
            PluginBookIndex.titleFor(bookUrl)
                ?: existing?.title?.takeIf { it.isNotBlank() }
                ?: source.metadata.name
        }
        val coverUrl = resolveCoverUrl(meta.coverUrl, baseUrl)
        graphOrNull?.registerCoverReferers(sourceId, listOfNotNull(coverUrl), baseUrl)
        val readCount = existing?.let { library?.readChapterIndexes(it.bookId)?.size } ?: 0
        val timestamp = now()
        val built = BookDetails(
            bookUrl = bookUrl,
            title = title,
            source = sourceLabel(
                sourceId,
                source.metadata.name.ifBlank { hostOf(realUrl(bookUrl)) },
            ),
            installId = sourceId,
            author = meta.author?.takeIf { it.isNotBlank() } ?: "Unknown",
            originLanguage = meta.extras["language"]?.takeIf { it.isNotBlank() }
                ?: meta.extras["originLanguage"]?.takeIf { it.isNotBlank() }
                ?: existing?.originLanguage,
            contentType = source.metadata.contentType.toAppType(),
            rating = parsePluginRating(meta.rating),
            ongoing = pluginStatusOngoing(meta.status),
            updatedLabel = meta.lastUpdated.orEmpty(),
            // The screen merges the lazy chapter list into these two fields.
            totalChapters = existing?.totalChapters ?: 0,
            readCount = readCount,
            readMinutes = 0,    // stub: reading time needs the library store
            ttsMinutes = 0,
            inLibrary = existing?.inLibrary == true,
            shelfId = existing?.shelfId ?: "reading",
            genres = (meta.genres + extraTags(meta.extras)).distinct(),
            description = meta.description.orEmpty(),
            sourceUrl = resolveCoverUrl(realUrl(bookUrl), baseUrl) ?: realUrl(bookUrl),
            authUrl = null,     // stub: plugin login flow is a later stage
            chapters = emptyList(),
            reviews = emptyList(), // stub: plugins do not expose reviews yet
            coverUrl = coverUrl,
            subtitle = displaySubtitle(
                subtitle = meta.extras["subtitle"],
                altTitles = parseAltTitles(extraAltTitles(meta.extras)),
                title = title,
            ),
        )
        if (persist) {
            library?.saveBook(
                buildEntity(
                    bookUrl = bookUrl,
                    sourceId = sourceId,
                    built = built,
                    meta = meta,
                    existing = existing,
                    timestamp = timestamp,
                ),
            )
        }
        if (persist) library?.let { repo ->
            val saved = repo.book(sourceId, realUrl(bookUrl))
            if (saved != null) saveTags(repo, saved.bookId, meta, timestamp)
        }
        PluginBookIndex.put(bookUrl, sourceId)
        PluginBookIndex.putTitle(bookUrl, title)
        details[bookUrl] = built
        return overlayLibraryState(bookUrl, built)
    }

    // Cold-process offline fallback. Everything the reader needs comes from the
    // persisted row; the chapter list falls back to stored chapters separately.
    private suspend fun bookDetailsFromRow(row: BookEntity): BookDetails = BookDetails(
        bookUrl = libraryBookKey(row.installId, row.url),
        title = row.title,
        source = sourceLabel(row.installId),
        installId = row.installId,
        author = row.authorRaw.ifBlank { row.author }.ifBlank { "Unknown" },
        originLanguage = row.originLanguage,
        contentType = runCatching { ContentType.valueOf(row.contentType) }
            .getOrDefault(ContentType.Novel),
        rating = row.rating.toFloat(),
        ongoing = pluginStatusOngoing(row.statusRaw.ifBlank { row.statusNormalized }),
        updatedLabel = row.lastUpdatedRaw,
        totalChapters = row.totalChapters,
        readCount = 0, // overlayLibraryState fills the real count
        readMinutes = 0,
        ttsMinutes = 0,
        inLibrary = row.inLibrary,
        shelfId = row.shelfId,
        // Tags are the persisted genre/tag set; reading them keeps a cached
        // open as rich as the original plugin payload without any network.
        genres = library?.tagsForBook(row.bookId)?.map { it.name }.orEmpty(),
        description = row.description,
        sourceUrl = row.url,
        authUrl = null,
        chapters = emptyList(),
        reviews = emptyList(),
        coverUrl = coverStore?.localCoverUrl(row.bookId) ?: row.coverUrl,
        subtitle = displaySubtitle(
            subtitle = row.subtitle,
            altTitles = parseAltTitles(row.altTitlesJson),
            title = row.title,
        ),
    )

    // Shared row builder: metadata save, fast cached add and history all write
    // the same fields so a later plugin refresh cannot reset membership.
    internal fun buildEntity(
        bookUrl: String,
        sourceId: String,
        built: BookDetails,
        meta: BookMeta,
        existing: BookEntity?,
        timestamp: Long,
    ): BookEntity = BookEntity(
        bookId = existing?.bookId ?: 0L,
        installId = sourceId,
        url = persistedBookUrl(bookUrl),
        title = built.title.ifBlank { existing?.title ?: "" },
        author = built.author,
        coverUrl = built.coverUrl,
        contentType = built.contentType.name,
        rating = built.rating.toDouble(),
        shelfId = built.shelfId,
        totalChapters = built.totalChapters,
        inLibrary = built.inLibrary,
        addedAt = existing?.addedAt ?: timestamp,
        updatedAt = timestamp,
        lastReadAt = existing?.lastReadAt,
        lastChapterId = existing?.lastChapterId,
        lastChapterIndex = existing?.lastChapterIndex,
        chapterListHash = existing?.chapterListHash,
        lastUpdateAt = existing?.lastUpdateAt,
        genresJson = buildJsonArray { meta.genres.distinct().forEach { add(it) } }.toString(),
        firstSeenAt = existing?.firstSeenAt?.takeIf { it > 0 } ?: timestamp,
        addedToLibraryAt = existing?.addedToLibraryAt
            ?: if (built.inLibrary) timestamp else null,
        subtitle = meta.extras["subtitle"]?.takeIf { it.isNotBlank() },
        altTitlesJson = extraAltTitles(meta.extras),
        authorRaw = meta.author.orEmpty(),
        authorsJson = extraAuthors(meta.author),
        description = built.description,
        originLanguage = built.originLanguage,
        ratingRaw = meta.rating.orEmpty(),
        statusRaw = meta.status.orEmpty(),
        statusNormalized = normalizeStatus(meta.status),
        lastUpdatedRaw = meta.lastUpdated.orEmpty(),
        lastUpdatedAt = existing?.lastUpdatedAt,
        extrasJson = buildJsonObject {
            meta.extras.forEach { (key, value) -> put(key, value) }
        }.toString(),
        metadataSource = "PLUGIN",
        metadataUpdatedAt = timestamp,
        metadataHash = metadataHash(meta),
    )

    // Persist the page's already-fetched metadata without another network
    // call. Tags are deliberately left to enrichment: membership must land in
    // one fast row write, and the FTS rebuild happens once afterwards.
    private suspend fun persistCachedRow(
        bookUrl: String,
        meta: BookMeta,
        built: BookDetails,
    ): BookEntity? {
        val repo = library ?: return null
        val sourceId = PluginBookIndex.sourceForBook(bookUrl) ?: return null
        val existing = repo.book(sourceId, realUrl(bookUrl))
        repo.saveBook(
            buildEntity(
                bookUrl = bookUrl,
                sourceId = sourceId,
                built = built,
                meta = meta,
                existing = existing,
                timestamp = now(),
            ),
        )
        PluginBookIndex.put(bookUrl, sourceId)
        PluginBookIndex.putTitle(bookUrl, built.title)
        return repo.book(sourceId, realUrl(bookUrl))
    }

    // Local books have no plugin and no network metadata. The DB row and the
    // imported copy are authoritative; core LocalSource only fills in the
    // parsed title/author/description/chapter count.
    private suspend fun loadLocalBook(bookUrl: String): BookDetails? {
        details[bookUrl]?.let { return overlayLibraryState(bookUrl, it) }
        val adapter = local ?: return null
        val meta = adapter.book(realUrl(bookUrl)) ?: return null
        val row = library?.book(bookUrl)
        val chapterCount = adapter.chapters(realUrl(bookUrl))?.size ?: row?.totalChapters ?: 0
        val title = meta.title.trim().ifBlank { row?.title ?: "Untitled" }
        val author = meta.author?.trim().takeUnless { it.isNullOrBlank() }
            ?: row?.authorRaw?.takeIf { it.isNotBlank() }
            ?: row?.author?.takeIf { it.isNotBlank() }
            ?: "Unknown"
        // Imported EPUB/FB2/CBZ/folder covers live under BookCoverStore; the
        // legacy books.coverUrl is only the last fallback.
        val coverUrl = localBookCoverUrl(row, coverStore)
        val built = BookDetails(
            bookUrl = bookUrl,
            title = title,
            source = row?.installId ?: "local",
            installId = row?.installId ?: "local",
            author = author,
            originLanguage = row?.originLanguage,
            contentType = if (row?.installId == LocalMangaInstallId) {
                ContentType.Manga
            } else {
                ContentType.Novel
            },
            rating = row?.rating?.toFloat() ?: 0f,
            ongoing = false,
            updatedLabel = "",
            totalChapters = chapterCount,
            readCount = row?.let { repo -> library?.readChapterIndexes(repo.bookId)?.size } ?: 0,
            readMinutes = 0,
            ttsMinutes = 0,
            inLibrary = row?.inLibrary != false,
            shelfId = row?.shelfId ?: "reading",
            genres = emptyList(),
            description = meta.description.orEmpty().ifBlank { row?.description.orEmpty() },
            sourceUrl = bookUrl,
            authUrl = null,
            chapters = emptyList(),
            reviews = emptyList(),
            coverUrl = coverUrl,
            subtitle = displaySubtitle(
                subtitle = row?.subtitle,
                altTitles = parseAltTitles(row?.altTitlesJson),
                title = title,
            ),
        )
        PluginBookIndex.put(bookUrl, row?.installId ?: "local")
        PluginBookIndex.putTitle(bookUrl, title)
        details[bookUrl] = built
        return overlayLibraryState(bookUrl, built)
    }

    // Direct SAF: the catalog URL is already the book identity. Nothing is
    // copied and no row is needed until the reader/ensureHistoryBook asks.
    private suspend fun loadLocalSafBook(bookUrl: String): BookDetails? {
        val source = localSaf ?: return null
        val meta = source.book(realUrl(bookUrl))
        val previousHash = localSafHashes.put(bookUrl, meta.cacheHash)
        if (previousHash != null && previousHash != meta.cacheHash) {
            chapterLists.remove(bookUrl)
            chapterUrls.remove(bookUrl)
            chapterTitles.remove(bookUrl)
            invalidateChapterStatus(bookUrl)
        }
        val row = library?.book(bookUrl)
        val chapterCount = maxOf(meta.chapterCount, row?.totalChapters ?: 0)
        val coverUrl = row?.let { coverStore?.localCoverUrl(it.bookId) }
            ?: meta.coverUrl
            ?: row?.coverUrl
        val built = BookDetails(
            bookUrl = bookUrl,
            title = meta.title.trim().ifBlank { row?.title ?: meta.displayName },
            source = row?.installId ?: meta.installId,
            installId = row?.installId ?: meta.installId,
            author = meta.author.trim().ifBlank {
                row?.authorRaw?.takeIf { it.isNotBlank() } ?: "Unknown"
            },
            originLanguage = row?.originLanguage,
            contentType = if (meta.isManga) ContentType.Manga else ContentType.Novel,
            rating = row?.rating?.toFloat() ?: 0f,
            ongoing = false,
            updatedLabel = "",
            totalChapters = chapterCount,
            readCount = row?.let { repo -> library?.readChapterIndexes(repo.bookId)?.size } ?: 0,
            readMinutes = 0,
            ttsMinutes = 0,
            inLibrary = row?.inLibrary == true,
            shelfId = row?.shelfId ?: "reading",
            genres = emptyList(),
            description = meta.description.ifBlank { row?.description.orEmpty() },
            sourceUrl = bookUrl,
            authUrl = null,
            chapters = emptyList(),
            reviews = emptyList(),
            coverUrl = coverUrl,
            subtitle = displaySubtitle(
                subtitle = row?.subtitle,
                altTitles = parseAltTitles(row?.altTitlesJson),
                title = row?.title ?: meta.title,
            ),
        )
        PluginBookIndex.put(bookUrl, meta.installId)
        PluginBookIndex.putTitle(bookUrl, built.title)
        details[bookUrl] = built
        return overlayLibraryState(bookUrl, built)
    }

    private suspend fun localSafChapters(bookUrl: String): List<BookChapter> {
        chapterLists[bookUrl]?.let { return withChapterStatus(bookUrl, it) }
        val source = localSaf ?: return emptyList()
        val mapped = source.chapters(realUrl(bookUrl)).map { chapter ->
            BookChapter(
                index = chapter.index,
                title = chapter.title.ifBlank { "Chapter ${chapter.index}" },
                isRead = false,
                downloaded = false,
                translated = false,
                downloading = false,
                url = chapter.url,
            )
        }
        chapterUrls[bookUrl] = mapped.associate { it.index to (it.url ?: "") }
        chapterTitles[bookUrl] = mapped.associate { it.index to it.title }
        chapterLists[bookUrl] = mapped
        return withChapterStatus(bookUrl, mapped)
    }

    /**
     * Creates or refreshes the transient local-file row in one DB transaction
     * (book + local file with copyPath=null + chapter rows). It runs only from
     * the reader/ensure path, never from the book page or catalogue.
     *
     * The repository returns early when the file identity hash and chapter URL
     * sequence are unchanged, so a second reader open performs no book/file/
     * chapter upserts and keeps chapter ids (progress + translation identity).
     */
    private suspend fun ensureLocalSafHistory(bookUrl: String) {
        val repo = library ?: return
        val source = localSaf ?: return
        val meta = source.book(realUrl(bookUrl))
        val chapters = source.chapters(realUrl(bookUrl))
        val documentUri = meta.documentUri
        val existingFile = repo.localBookFileByUri(documentUri)
        val existingBook = existingFile?.let { repo.book(it.bookId) }
        val timestamp = now()
        val seeds = chapters.map { chapter ->
            ChapterSeed(
                index = chapter.index,
                url = chapter.url,
                title = chapter.title.ifBlank { "Chapter ${chapter.index}" },
            )
        }
        if (existingBook != null && existingFile?.sha256 != meta.cacheHash) {
            val oldUrls = repo.chapters(existingBook.bookId)
                .filter { it.removedAt == null }
                .map { it.url }
            if (oldUrls != seeds.map { it.url }) {
                // Same URL sequence = same chapter identity; progress survives
                // a mtime/size refresh. A changed list starts clean.
                runCatching { repo.clearProgress(existingBook.bookId) }
            }
        }
        val bookRow = buildLocalSafBookRow(existingBook, meta, realUrl(bookUrl), seeds.size, timestamp)
        val fileRow = directBookFileRow(meta, documentUri, existingFile, timestamp)
        val bookId = repo.ensureDirectBook(bookRow, fileRow, seeds, timestamp)
        // Opening a direct book must make its embedded cover visible on the
        // dashboard/history, not only an explicit Add. Still inLibrary=false.
        persistDirectCover(bookId, bookUrl)
        details.remove(bookUrl)
        chapterLists.remove(bookUrl)
        chapterUrls.remove(bookUrl)
        chapterTitles.remove(bookUrl)
        invalidateChapterStatus(bookUrl)
    }

    // The catalog metadata cache already holds the embedded cover file; copy it
    // into BookCoverStore once so Dashboard/History resolve it. No library add.
    private suspend fun persistDirectCover(bookId: Long, bookUrl: String) {
        val store = coverStore ?: return
        if (store.localCoverUrl(bookId) != null) return
        val bytes = localSaf?.coverBytes(realUrl(bookUrl)) ?: return
        store.storeLocalCover(bookId, remoteUrl = null, bytes)
    }

    companion object {
        private const val STATUS_LOG_LIMIT = 128
        private const val STATUS_LOG_MIN_INTERVAL_MS = 30_000L

        internal fun directBookFileRow(
            meta: dev.mirutime.app.features.finder.data.LocalSafBook,
            documentUri: String,
            existingFile: LocalBookFileEntity?,
            timestamp: Long,
        ): LocalBookFileEntity = LocalBookFileEntity(
            bookId = existingFile?.bookId ?: 0,
            uri = documentUri,
            displayName = meta.displayName,
            format = meta.format,
            mime = null,
            sizeBytes = meta.sizeBytes ?: existingFile?.sizeBytes ?: 0,
            sha256 = meta.cacheHash,
            // Direct rows own no copy. A legacy imported row keeps its copy so
            // its old delete semantics remain intact.
            copyPath = existingFile?.copyPath?.takeIf { it.isNotBlank() },
            parserVersion = existingFile?.parserVersion ?: DirectSafParserVersion,
            importedAt = existingFile?.importedAt ?: timestamp,
        )

        internal fun buildLocalSafBookRow(
            existing: BookEntity?,
            meta: dev.mirutime.app.features.finder.data.LocalSafBook,
            bookUrl: String,
            totalChapters: Int,
            timestamp: Long,
        ): BookEntity {
            val base = existing
            return BookEntity(
                bookId = base?.bookId ?: 0,
                installId = base?.installId ?: meta.installId,
                url = bookUrl,
                title = meta.title.ifBlank { base?.title ?: meta.displayName },
                author = meta.author.ifBlank { base?.author ?: "Unknown" },
                coverUrl = base?.coverUrl,
                contentType = if (meta.isManga) "Manga" else "Novel",
                rating = base?.rating ?: 0.0,
                shelfId = base?.shelfId ?: "reading",
                totalChapters = totalChapters,
                inLibrary = base?.inLibrary ?: false,
                addedAt = base?.addedAt ?: timestamp,
                updatedAt = timestamp,
                lastReadAt = base?.lastReadAt,
                lastChapterId = base?.lastChapterId,
                lastChapterIndex = base?.lastChapterIndex,
                chapterListHash = base?.chapterListHash,
                lastUpdateAt = base?.lastUpdateAt,
                genresJson = base?.genresJson ?: "[]",
                firstSeenAt = base?.firstSeenAt?.takeIf { it > 0 } ?: timestamp,
                addedToLibraryAt = base?.addedToLibraryAt,
                subtitle = base?.subtitle,
                altTitlesJson = base?.altTitlesJson ?: "[]",
                authorRaw = meta.author.ifBlank { base?.authorRaw ?: "Unknown" },
                authorsJson = base?.authorsJson ?: jsonAuthors(meta.author),
                description = meta.description.ifBlank { base?.description.orEmpty() },
                originLanguage = base?.originLanguage,
                ratingRaw = base?.ratingRaw.orEmpty(),
                statusRaw = base?.statusRaw.orEmpty(),
                statusNormalized = base?.statusNormalized ?: "UNKNOWN",
                lastUpdatedRaw = base?.lastUpdatedRaw.orEmpty(),
                lastUpdatedAt = base?.lastUpdatedAt,
                newChapters = base?.newChapters ?: 0,
                lastUpdateCheckAt = base?.lastUpdateCheckAt,
                extrasJson = base?.extrasJson ?: "{}",
                metadataSource = "LOCAL",
                metadataUpdatedAt = timestamp,
                metadataHash = meta.cacheHash,
            )
        }

        }


    private suspend fun addLocalSafToLibrary(
        repo: LibraryRepository,
        bookUrl: String,
    ): MembershipResult {
        val key = "local-file|$bookUrl"
        val lock = membershipLocks.getOrPut(key) { Mutex() }
        return try {
            lock.withLock {
                if (latestMembershipTarget[bookUrl] != true) {
                    return@withLock MembershipResult(success = true, superseded = true)
                }
                ensureLocalSafHistory(bookUrl)
                val row = repo.book(bookUrl) ?: return@withLock MembershipResult(false)
                if (!row.inLibrary) repo.addToLibrary(row.bookId, now())
                persistDirectCover(row.bookId, bookUrl)
                details[bookUrl] = (details[bookUrl] ?: loadLocalSafBook(bookUrl))?.let {
                    it.copy(inLibrary = true, shelfId = row.shelfId, readCount = repo.readChapterIndexes(row.bookId).size)
                } ?: return@withLock MembershipResult(true)
                MembershipResult(success = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MiruLog.w("library", "Direct local add failed for $bookUrl", e)
            MembershipResult(success = false)
        }
    }

    private suspend fun removeLocalSafFromLibrary(
        repo: LibraryRepository,
        bookUrl: String,
    ): MembershipResult {
        val row = repo.book(bookUrl) ?: return MembershipResult(success = true)
        val copyPath = repo.localBookFile(row.bookId)?.copyPath
        if (copyPath != null) {
            // A legacy imported row owns a copy; keep the old delete semantics.
            return removeFromLibrary(repo, bookUrl)
        }
        val key = "local-file|$bookUrl"
        val lock = membershipLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            if (latestMembershipTarget[bookUrl] != false) {
                return@withLock MembershipResult(success = true, superseded = true)
            }
            repo.setInLibrary(row.bookId, false)
            details[bookUrl] = details[bookUrl]?.copy(inLibrary = false) ?: return@withLock MembershipResult(true)
            MembershipResult(success = true)
        }
    }

    // Cached metadata goes stale the moment the library changes (toggle,
    // shelf move, reader progress), so every read overlays the current row.
    private suspend fun overlayLibraryState(bookUrl: String, base: BookDetails): BookDetails {
        val repo = library ?: return base
        val row = repo.book(bookUrl) ?: return base
        return base.copy(
            inLibrary = row.inLibrary,
            shelfId = row.shelfId,
            totalChapters = maxOf(base.totalChapters, row.totalChapters),
            readCount = repo.readChapterIndexes(bookUrl).size,
            readMinutes = (repo.durationForBook(row.bookId, "reading") / 60_000L).toInt(),
            ttsMinutes = (repo.durationForBook(row.bookId, "tts") / 60_000L).toInt(),
        )
    }

    override suspend fun chapterListHash(bookUrl: String): String? {
        if (isLocalFileBook(realUrl(bookUrl)) || LocalBookProvider.isLocalBook(realUrl(bookUrl))) return null
        val sourceId = PluginBookIndex.sourceForBook(bookUrl) ?: return null
        val source = pluginSourceFor(sourceId) ?: return null
        return source.chapterListHash(realUrl(bookUrl)).getOrNull()
    }

    override suspend fun refreshChapters(bookUrl: String): List<BookChapter> =
        when (val result = refreshChaptersOutcome(bookUrl)) {
            is Outcome.Ok -> result.value
            // List callers (library update/migration) only need the rows.
            is Outcome.Err -> storedChapters(bookUrl).orEmpty()
        }

    // Explicit refresh (LibraryUpdateService/book page) is the one path that
    // must ask the plugin; chapters() itself serves persisted rows first. A
    // partial plugin answer must not replace (soft-remove) the stored list,
    // and a typed failure keeps the rows while reaching the caller.
    override suspend fun refreshChaptersOutcome(
        bookUrl: String,
    ): Outcome<List<BookChapter>> {
        invalidateChapterCaches(bookUrl)
        if (isLocalFileBook(realUrl(bookUrl))) {
            localSaf?.invalidate(realUrl(bookUrl))
            return Outcome.Ok(localSafChapters(bookUrl))
        }
        if (LocalBookProvider.isLocalBook(realUrl(bookUrl))) return Outcome.Ok(localChapters(bookUrl))
        val stored = storedChapters(bookUrl)
        return when (val result = fetchPluginChaptersOutcome(bookUrl, stored?.size ?: 0)) {
            is Outcome.Err -> result
            is Outcome.Ok -> Outcome.Ok(result.value ?: stored.orEmpty())
        }
    }

    // Typed variant for the Updates screen: an Outcome.Err keeps the plugin
    // failure instead of collapsing into "no chapters".
    override suspend fun refreshChapterCountOutcome(bookUrl: String): Outcome<Int?> {
        invalidateChapterCaches(bookUrl)
        if (isLocalFileBook(realUrl(bookUrl))) {
            localSaf?.invalidate(realUrl(bookUrl))
            return Outcome.Ok(localSafChapters(bookUrl).size.takeIf { it > 0 })
        }
        if (LocalBookProvider.isLocalBook(realUrl(bookUrl))) {
            return Outcome.Ok(localChapters(bookUrl).size.takeIf { it > 0 })
        }
        val stored = storedChapters(bookUrl)
        return when (val result = fetchPluginChaptersOutcome(bookUrl, stored?.size ?: 0)) {
            is Outcome.Err -> result
            is Outcome.Ok -> Outcome.Ok(result.value?.size?.takeIf { it > 0 })
        }
    }

    override fun lastPluginError(bookUrl: String): PluginError? = pluginErrors[bookUrl]

    private fun invalidateChapterCaches(bookUrl: String) {
        chapterLists.remove(bookUrl)
        chapterUrls.remove(bookUrl)
        chapterTitles.remove(bookUrl)
        invalidateChapterStatus(bookUrl)
    }

    // DB-only: local/SAF books keep their existing paths and return null so the
    // screen does not start a redundant background refresh for them.
    override suspend fun cachedChapters(bookUrl: String): List<BookChapter>? {
        if (isLocalFileBook(realUrl(bookUrl)) || LocalBookProvider.isLocalBook(realUrl(bookUrl))) return null
        return storedChapters(bookUrl)
    }

    override suspend fun chapters(bookUrl: String): List<BookChapter> =
        when (val result = chaptersOutcome(bookUrl)) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> emptyList()
        }

    // Typed variant for the book page: an Err must reach the screen instead of
    // collapsing into an empty list, which reads as "No chapters".
    override suspend fun chaptersOutcome(bookUrl: String): Outcome<List<BookChapter>> {
        if (isLocalFileBook(realUrl(bookUrl))) return Outcome.Ok(localSafChapters(bookUrl))
        if (LocalBookProvider.isLocalBook(realUrl(bookUrl))) return Outcome.Ok(localChapters(bookUrl))
        chapterLists[bookUrl]?.let {
            persistCachedChapters(bookUrl)
            return Outcome.Ok(withChapterStatus(bookUrl, it))
        }
        // DB first: an existing book's persisted rows are authoritative for
        // opening. Only a book with no stored chapters reaches the plugin;
        // LibraryUpdateService/refreshChapters is the path that fetches and
        // persists a newer list.
        storedChapters(bookUrl)?.let { return Outcome.Ok(it) }
        return when (val result = fetchPluginChaptersOutcome(bookUrl)) {
            is Outcome.Err -> result
            // Ok(null) = no source/source answered nothing: a real empty page.
            is Outcome.Ok -> Outcome.Ok(result.value.orEmpty())
        }
    }

    private suspend fun fetchPluginChaptersOutcome(
        bookUrl: String,
        minimumCount: Int = 0,
    ): Outcome<List<BookChapter>?> {
        val sourceId = PluginBookIndex.sourceForBook(bookUrl) ?: return Outcome.Ok(null)
        val source = pluginSourceFor(sourceId) ?: return Outcome.Ok(null)
        val chaptersResult = source.chapters(realUrl(bookUrl))
        val raw = when (chaptersResult) {
            is Outcome.Ok -> chaptersResult.value
            is Outcome.Err -> return chaptersResult
        }
        // A plugin can return a successful but empty list (CF/login page,
        // parser regression, source outage) or fail outright offline. Report
        // no fetch result so the caller keeps stored rows (refresh) or returns
        // empty (no rows yet), and never reconcile that failed refresh — it
        // would mark every existing chapter removed.
        if (raw.isEmpty()) return Outcome.Ok(null)
        val baseUrl = source.metadata.baseUrl
        val mapped = dedupeChapters(
            raw.mapIndexed { position, chapter ->
                val index = position + 1
                BookChapter(
                    index = index,
                    title = chapter.title.trim().ifBlank { "Chapter $index" },
                    isRead = false,
                    downloaded = false, // stub: downloads arrive with the queue
                    translated = false, // stub: per-chapter translation cache is not wired yet
                    downloading = false,
                    url = resolveCoverUrl(chapter.url, baseUrl) ?: chapter.url,
                )
            },
        )
        if (mapped.size < minimumCount) return Outcome.Ok(null)
        val urls = LinkedHashMap<Int, String>(mapped.size)
        val titles = LinkedHashMap<Int, String>(mapped.size)
        mapped.forEach { chapter ->
            urls[chapter.index] = chapter.url.orEmpty()
            titles[chapter.index] = chapter.title
        }
        chapterUrls[bookUrl] = urls
        chapterTitles[bookUrl] = titles
        chapterLists[bookUrl] = mapped
        library?.let { repo ->
            val row = repo.book(sourceId, realUrl(bookUrl))
            if (row != null) {
                repo.replaceChapters(
                    bookId = row.bookId,
                    seeds = mapped.map { chapter ->
                        ChapterSeed(
                            index = chapter.index,
                            url = chapter.url ?: chapterKey(bookUrl, chapter.index),
                            title = chapter.title,
                        )
                    },
                    at = now(),
                )
            } else {
                repo.updateTotalChapters(bookUrl, mapped.size)
            }
        }
        return Outcome.Ok(withChapterStatus(bookUrl, mapped))
    }

    // DB-first source for plugin books: the persisted rows carry chapter
    // identity, URLs and the status overlay. Opening must not hit the plugin;
    // LibraryUpdateService/refreshChapters replaces the rows on a real update.
    private suspend fun storedChapters(bookUrl: String): List<BookChapter>? {
        val repo = library ?: return null
        val row = repo.book(bookUrl) ?: return null
        val rows = repo.chapters(row.bookId)
        if (rows.isEmpty()) return null
        val mapped = rows.map { chapter ->
            BookChapter(
                index = chapter.idx,
                title = chapter.title.ifBlank { "Chapter ${chapter.idx}" },
                isRead = false,
                downloaded = false,
                translated = false,
                downloading = false,
                url = chapter.url,
            )
        }
        chapterUrls[bookUrl] = mapped.associate { it.index to it.url.orEmpty() }
        chapterTitles[bookUrl] = mapped.associate { it.index to it.title }
        chapterLists[bookUrl] = mapped
        return withChapterStatus(bookUrl, mapped)
    }

    // Stable chapter urls are persisted by the importer; parse only to recover
    // titles and (for legacy rows) to backfill a missing chapter list.
    private suspend fun localChapters(bookUrl: String): List<BookChapter> {
        chapterLists[bookUrl]?.let { return withChapterStatus(bookUrl, it) }
        val adapter = local ?: return emptyList()
        val raw = adapter.chapters(realUrl(bookUrl)) ?: return emptyList()
        val mapped = dedupeChapters(
            raw.mapIndexed { position, chapter ->
                val index = position + 1
                BookChapter(
                    index = index,
                    title = chapter.title.trim().ifBlank { "Chapter $index" },
                    isRead = false,
                    downloaded = false,
                    translated = false,
                    downloading = false,
                    url = chapter.url,
                )
            },
        )
        chapterUrls[bookUrl] = mapped.associate { it.index to (it.url ?: "") }
        chapterTitles[bookUrl] = mapped.associate { it.index to it.title }
        chapterLists[bookUrl] = mapped
        library?.let { repo ->
            val row = repo.book(bookUrl) ?: return@let
            if (repo.chapters(row.bookId).isEmpty()) {
                repo.replaceChapters(
                    bookId = row.bookId,
                    seeds = mapped.map { chapter ->
                        ChapterSeed(
                            index = chapter.index,
                            url = chapter.url ?: chapterKey(bookUrl, chapter.index),
                            title = chapter.title,
                        )
                    },
                    at = now(),
                )
            }
        }
        return withChapterStatus(bookUrl, mapped)
    }

    // The raw chapter list is cached, but status flags must reflect the current
    // DB. One batch snapshot per book per generation; provider mutations bump
    // the generation, while the short TTL absorbs writes from other lanes.
    private suspend fun withChapterStatus(
        bookUrl: String,
        chapters: List<BookChapter>,
    ): List<BookChapter> {
        val snapshot = chapterStatusSnapshot(bookUrl)
        if (snapshot != null) {
            val counters = ChapterStatusCounters(
                bookId = snapshot.bookId,
                count = snapshot.chapterCount,
                read = snapshot.readIndexes.size,
                downloaded = snapshot.downloadedIndexes.size,
                translated = snapshot.translatedIndexes.size,
                last = snapshot.lastIndex,
            )
            val timestamp = now()
            val logStatus = synchronized(lastStatusLog) {
                val previous = lastStatusLog[counters.bookId]
                val shouldLog = shouldLogChapterStatus(
                    previous = previous?.first,
                    current = counters,
                    now = timestamp,
                    lastLoggedAt = previous?.second ?: 0L,
                )
                if (shouldLog) {
                    if (lastStatusLog.size >= STATUS_LOG_LIMIT) {
                        lastStatusLog.entries.removeAll {
                            timestamp - it.value.second >= STATUS_LOG_MIN_INTERVAL_MS
                        }
                    }
                    if (lastStatusLog.size >= STATUS_LOG_LIMIT) {
                        lastStatusLog.entries.minByOrNull { it.value.second }?.let {
                            lastStatusLog.remove(it.key)
                        }
                    }
                    lastStatusLog[counters.bookId] = counters to timestamp
                }
                shouldLog
            }
            if (logStatus) {
                MiruLog.d(
                    "chapter-status",
                    "bookHash=${shortHash(bookUrl)} bookId=${counters.bookId} " +
                        "count=${counters.count} read=${counters.read} " +
                        "downloaded=${counters.downloaded} " +
                        "translated=${counters.translated} last=${counters.last}",
                )
            }
        }
        return applyChapterStatus(
            chapters = chapters,
            snapshot = snapshot,
            offline = isLocalFileBook(realUrl(bookUrl)) || LocalBookProvider.isLocalBook(realUrl(bookUrl)),
        )
    }

    private suspend fun chapterStatusSnapshot(bookUrl: String): ChapterStatusSnapshot? {
        val repo = library ?: return null
        val generation = chapterStatusGenerations[bookUrl] ?: 0L
        val timestamp = now()
        val cached = chapterStatusSnapshots[bookUrl]
        if (cached != null &&
            cached.generation == generation &&
            timestamp - cached.at <= ChapterStatusTtlMs
        ) {
            return cached.snapshot
        }
        val snapshot = repo.chapterStatusSnapshot(bookUrl)
        chapterStatusSnapshots[bookUrl] = CachedChapterStatus(snapshot, generation, timestamp)
        return snapshot
    }

    private fun invalidateChapterStatus(bookUrl: String) {
        chapterStatusGenerations.merge(bookUrl, 1L, Long::plus)
        chapterStatusSnapshots.remove(bookUrl)
    }

    override suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? {
        val started = System.nanoTime()
        if (isLocalFileBook(realUrl(bookUrl))) {
            val source = localSaf ?: return null
            val content = source.chapterContent(realUrl(bookUrl), chapterIndex)
            return ChapterContent(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                title = content.title,
                html = content.html,
                chapterUrl = localFileChapterUrl(bookUrl, chapterIndex),
                source = chapterSourceFor(bookUrl, safCacheHit = content.cacheHit),
            ).also { logChapterContent(it, started) }
        }
        if (LocalBookProvider.isLocalBook(realUrl(bookUrl))) {
            return localChapterContent(bookUrl, chapterIndex)?.also {
                logChapterContent(it, started)
            }
        }
        // A downloaded chapter is a complete local payload: prefer it so the
        // reader works offline and never re-fetches bytes the user paid for.
        downloadedBodyContent(bookUrl, chapterIndex)?.let { content ->
            return content.also { logChapterContent(it, started) }
        }
        val sourceId = PluginBookIndex.sourceForBook(bookUrl)
            ?: throw ChapterDownloadException(
                ChapterFailureKind.SOURCE,
                retryable = false,
                message = "Unknown source",
                pluginError = PluginError.ScriptError("Unknown source"),
            )
        val source = pluginSourceFor(sourceId)
            ?: throw ChapterDownloadException(
                ChapterFailureKind.SOURCE,
                retryable = false,
                message = "Source unavailable",
                pluginError = PluginError.ScriptError("Source unavailable"),
            )
        // Reader can be restored straight into a chapter with a cold cache.
        if (chapterUrls[bookUrl] == null) chapters(bookUrl)
        val chapterUrl = chapterUrls[bookUrl]?.get(chapterIndex)
            ?: throw ChapterDownloadException(
                ChapterFailureKind.NOT_FOUND,
                retryable = false,
                message = "Chapter not found",
                pluginError = PluginError.ScriptError("Chapter not found"),
            )
        // Typed transport failures replace the old silent null: the reader
        // must say WHAT failed (HTTP 403 / Timeout / host+path), not show a
        // blank page.
        val fetched = graph.fetchTextResult(sourceId, chapterUrl, source.metadata.charset)
        val html = fetched.body ?: throw fetchFailure(
            fetched,
            chapterUrl,
            sourceId,
            source.metadata.name,
        )
        val parsed = when (val result = source.chapterContent(html, chapterUrl)) {
            is Outcome.Ok -> result.value
            is Outcome.Err -> throw pluginFailure(
                result.error,
                sourceId,
                "content",
                source.metadata.name,
            )
        }
        return when (parsed) {
            is CoreChapterContent.Text -> {
                val content = ChapterContent(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    title = chapterTitles[bookUrl]?.get(chapterIndex) ?: "Chapter $chapterIndex",
                    html = chapterHtml(parsed.text),
                    chapterUrl = chapterUrl,
                    source = ChapterSource.PLUGIN_NETWORK,
                )
                cacheChapterText(bookUrl, chapterIndex, content.html)
                content.also { logChapterContent(it, started) }
            }
            is CoreChapterContent.Pages -> {
                val pageUrls = resolveMangaPageUrls(parsed.pageUrls, chapterUrl)
                val html = mangaPagesHtml(pageUrls)
                    ?: throw pluginFailure(
                        PluginError.ScriptError("Chapter pages are empty"),
                        sourceId,
                        "content",
                        source.metadata.name,
                    )
                // Page CDNs often require the chapter URL as Referer; the
                // reader's Coil requests read it back through imageHeadersFor.
                graph.registerCoverReferers(sourceId, pageUrls, chapterUrl)
                ChapterContent(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    title = chapterTitles[bookUrl]?.get(chapterIndex) ?: "Chapter $chapterIndex",
                    html = html,
                    chapterUrl = chapterUrl,
                    source = ChapterSource.PLUGIN_NETWORK,
                ).also { logChapterContent(it, started) }
            }
            is CoreChapterContent.Video -> null
        }
    }

    /**
     * Video payloads are parsed from an empty document: the plugin resolves
     * the stream URLs itself, so the host must not fetch chapter HTML. Text
     * and page chapters stay typed errors rather than silently empty lists.
     */
    override suspend fun videoStreams(
        bookUrl: String,
        chapterIndex: Int,
    ): Outcome<List<VideoStream>> {
        val sourceId = PluginBookIndex.sourceForBook(bookUrl)
            ?: return Outcome.Err(PluginError.ScriptError("Unknown source"))
        val source = pluginSourceFor(sourceId)
            ?: return Outcome.Err(PluginError.ScriptError("Source unavailable"))
        if (chapterUrls[bookUrl] == null) chapters(bookUrl)
        val chapterUrl = chapterUrls[bookUrl]?.get(chapterIndex)
            ?: library?.book(bookUrl)?.let { row ->
                library?.chapterByIndex(row.bookId, chapterIndex)?.url
            }
            ?: return Outcome.Err(PluginError.ScriptError("Chapter not found"))
        val parsed = try {
            source.chapterContent("", chapterUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Outcome.Err(PluginError.ScriptError("Source could not parse the chapter"))
        }
        return when (parsed) {
            is Outcome.Err -> parsed
            is Outcome.Ok -> when (val content = parsed.value) {
                is CoreChapterContent.Video -> Outcome.Ok(content.streams)
                is CoreChapterContent.Text -> Outcome.Err(
                    PluginError.ScriptError("Expected a video chapter, got text"),
                )
                is CoreChapterContent.Pages -> Outcome.Err(
                    PluginError.ScriptError("Expected a video chapter, got pages"),
                )
            }
        }
    }

    // Reading must continue when the cache write fails (read-only storage, a
    // row deleted mid-load, etc.), so only cancellation escapes.
    private suspend fun cacheChapterText(bookUrl: String, chapterIndex: Int, html: String) {
        val cache = chapterCache ?: return
        try {
            cache.saveText(bookUrl, chapterIndex, html)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // non-fatal for reading
        }
    }

    private suspend fun cacheChapterPages(
        bookUrl: String,
        chapterIndex: Int,
        pageUrls: List<String>,
        sourceId: String,
    ) {
        val cache = chapterCache ?: return
        try {
            cache.savePages(bookUrl, chapterIndex, pageUrls) { url ->
                graph.fetchImage(sourceId, url, graph.imageHeadersFor(url)).bytes
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // non-fatal for reading
        }
    }

    private suspend fun downloadedBodyContent(
        bookUrl: String,
        chapterIndex: Int,
    ): ChapterContent? {
        val repo = library ?: return null
        val row = repo.book(bookUrl) ?: return null
        val chapter = repo.chapterByIndex(row.bookId, chapterIndex) ?: return null
        val body = repo.chapterBody(chapter.chapterId)
        if (body != null) {
            val file = File(body.path).takeIf { it.isFile }
            if (file != null) {
                return ChapterContent(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    title = chapter.title,
                    html = withContext(Dispatchers.IO) { file.readText() },
                    chapterUrl = chapter.url,
                    source = ChapterSource.IMPORTED_DB_BODY,
                    chapterId = chapter.chapterId,
                )
            }
        }
        // Downloaded remote manga is stored as page files; expose it as the
        // same <img> HTML the local CBZ path uses, so the pager works offline.
        val pages = repo.chapterPage(chapter.chapterId) ?: return null
        val paths = runCatching {
            val array = JSONArray(pages.pagePathsJson)
            List(array.length()) { array.optString(it) }
        }.getOrDefault(emptyList())
            .filter { it.isNotBlank() && File(it).isFile }
        if (paths.isEmpty()) return null
        val html = paths.joinToString("") { path ->
            "<p><img src=\"${File(path).toURI()}\"/></p>"
        }
        return ChapterContent(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            title = chapter.title,
            html = html,
            chapterUrl = chapter.url,
            source = ChapterSource.IMPORTED_DB_BODY,
            chapterId = chapter.chapterId,
        )
    }

    override suspend fun chapterPages(bookUrl: String, chapterIndex: Int): List<String> {
        if (isLocalFileBook(realUrl(bookUrl)) || LocalBookProvider.isLocalBook(realUrl(bookUrl))) return emptyList()
        val sourceId = PluginBookIndex.sourceForBook(bookUrl) ?: return emptyList()
        val source = pluginSourceFor(sourceId) ?: return emptyList()
        if (chapterUrls[bookUrl] == null) chapters(bookUrl)
        val chapterUrl = chapterUrls[bookUrl]?.get(chapterIndex)
            // Resumed queue after process death: the DB already has the URL.
            ?: library?.book(bookUrl)?.let { row ->
                library?.chapterByIndex(row.bookId, chapterIndex)?.url
            }
            ?: return emptyList()
        val html = try {
            graph.fetchText(sourceId, chapterUrl, source.metadata.charset)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val parsed = source.chapterContent(html, chapterUrl).getOrNull() ?: return emptyList()
        return when (parsed) {
            is CoreChapterContent.Pages -> {
                val urls = parsed.pageUrls.mapNotNull { raw -> resolveCoverUrl(raw, chapterUrl) }
                // Page CDNs often require the chapter URL as Referer.
                graph.registerCoverReferers(sourceId, urls, chapterUrl)
                cacheChapterPages(bookUrl, chapterIndex, urls, sourceId)
                urls
            }
            else -> emptyList()
        }
    }

    // Download path: the same host fetch + plugin parse, but failures keep
    // their auth/CF/rate-limit type so the queue can fail fast instead of
    // retrying a blocked source forever.
    override suspend fun downloadChapterText(
        bookUrl: String,
        chapterIndex: Int,
        sourceId: String?,
    ): String {
        val resolvedId = resolveDownloadSource(
            bookUrl = bookUrl,
            persisted = sourceId,
            indexed = { PluginBookIndex.sourceFor(bookUrl) },
        ) ?: failDownload(ChapterFailureKind.SOURCE, "Unknown source", retryable = false)
        val source = pluginSourceFor(resolvedId)
            ?: failDownload(ChapterFailureKind.SOURCE, "Source unavailable", retryable = false)
        val chapterUrl = downloadChapterUrl(bookUrl, chapterIndex)
            ?: failDownload(ChapterFailureKind.NOT_FOUND, "Chapter not found", retryable = false)
        val html = fetchChapterHtml(resolvedId, chapterUrl)
        val content = parseChapterContent(source, html, chapterUrl, resolvedId)
        val text = when (content) {
            is CoreChapterContent.Text -> chapterHtml(content.text)
            is CoreChapterContent.Pages ->
                failDownload(ChapterFailureKind.SOURCE, "Expected text chapter", retryable = false)
            is CoreChapterContent.Video ->
                failDownload(ChapterFailureKind.SOURCE, "Unsupported video chapter", retryable = false)
        }
        if (text.isBlank()) {
            failDownload(ChapterFailureKind.SOURCE, "Chapter text is empty", retryable = false)
        }
        return text
    }

    override suspend fun downloadChapterPages(
        bookUrl: String,
        chapterIndex: Int,
        sourceId: String?,
    ): List<String> {
        val resolvedId = resolveDownloadSource(
            bookUrl = bookUrl,
            persisted = sourceId,
            indexed = { PluginBookIndex.sourceFor(bookUrl) },
        ) ?: failDownload(ChapterFailureKind.SOURCE, "Unknown source", retryable = false)
        val source = pluginSourceFor(resolvedId)
            ?: failDownload(ChapterFailureKind.SOURCE, "Source unavailable", retryable = false)
        val chapterUrl = downloadChapterUrl(bookUrl, chapterIndex)
            ?: failDownload(ChapterFailureKind.NOT_FOUND, "Chapter not found", retryable = false)
        val html = fetchChapterHtml(resolvedId, chapterUrl)
        val content = parseChapterContent(source, html, chapterUrl, resolvedId)
        if (content !is CoreChapterContent.Pages) {
            failDownload(ChapterFailureKind.SOURCE, "Expected image chapter", retryable = false)
        }
        val urls = (content as CoreChapterContent.Pages).pageUrls.mapNotNull { raw ->
            resolveCoverUrl(raw, chapterUrl)
        }
        if (urls.isEmpty()) {
            failDownload(ChapterFailureKind.SOURCE, "Chapter pages are empty", retryable = false)
        }
        // Page CDNs often require the chapter URL as Referer.
        graph.registerCoverReferers(resolvedId, urls, chapterUrl)
        return urls
    }

    private suspend fun parseChapterContent(
        source: dev.mirutime.core.api.PluginSource,
        html: String,
        chapterUrl: String,
        sourceId: String? = null,
    ): CoreChapterContent = try {
        when (val parsed = source.chapterContent(html, chapterUrl)) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> throw pluginFailure(
                parsed.error,
                sourceId,
                "download",
                source.metadata.name,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ChapterDownloadException) {
        throw e
    } catch (e: CfChallengeRequiredException) {
        throw ChapterDownloadException(
            ChapterFailureKind.CLOUDFLARE,
            retryable = false,
            message = downloadMessage(
                e.error,
                sourceId,
                "Cloudflare verification required",
                sourceName = source.metadata.name,
            ),
            pluginError = e.error,
        )
    } catch (e: Exception) {
        throw ChapterDownloadException(
            ChapterFailureKind.SOURCE,
            retryable = false,
            message = downloadMessage(
                null,
                sourceId,
                "Source could not parse the chapter",
                sourceName = source.metadata.name,
            ).ifBlank { "Source could not parse the chapter" },
        )
    }

    private suspend fun downloadChapterUrl(bookUrl: String, chapterIndex: Int): String? {
        if (chapterUrls[bookUrl] == null) chapters(bookUrl)
        return chapterUrls[bookUrl]?.get(chapterIndex)
            ?: library?.book(bookUrl)?.let { row ->
                library?.chapterByIndex(row.bookId, chapterIndex)?.url
            }
    }

    private suspend fun fetchChapterHtml(sourceId: String, chapterUrl: String): String {
        // The chapter page must be decoded with the plugin's declared charset
        // (69shuba/GKB); the HTTP default is UTF-8 only for undeclared sources.
        val source = pluginSourceFor(sourceId)
        val fetched = graph.fetchTextResult(sourceId, chapterUrl, source?.metadata?.charset)
        fetched.body?.let { return it }
        throw fetchFailure(fetched, chapterUrl, sourceId, source?.metadata?.name)
    }

    private fun fetchFailure(
        fetched: TextFetchResult,
        chapterUrl: String,
        sourceId: String?,
        sourceName: String? = null,
    ): ChapterDownloadException {
        if (fetched.error is CfChallengeRequiredException) {
            return ChapterDownloadException(
                ChapterFailureKind.CLOUDFLARE,
                retryable = false,
                message = downloadMessage(
                    (fetched.error as CfChallengeRequiredException).error,
                    sourceId,
                    "Cloudflare verification required",
                    sourceName = sourceName,
                ),
                pluginError = (fetched.error as CfChallengeRequiredException).error,
            )
        }
        val (scheme, host, path) = uriHostPath(chapterUrl)
        val status = fetched.status
        val kind = when {
            status == 401 || status == 403 -> ChapterFailureKind.AUTH_REQUIRED
            status == 429 -> ChapterFailureKind.RATE_LIMITED
            status == 404 || status == 410 -> ChapterFailureKind.NOT_FOUND
            status in 500..599 -> ChapterFailureKind.SERVER
            status > 0 -> ChapterFailureKind.SOURCE
            else -> ChapterFailureKind.NETWORK
        }
        val retryable = kind == ChapterFailureKind.SERVER || kind == ChapterFailureKind.NETWORK
        val pluginError = if (status > 0) {
            PluginError.Network(
                cause = "network",
                status = status,
                reason = httpReason(status),
                host = host,
                path = path,
                scheme = scheme,
            )
        } else {
            PluginError.Network(
                cause = fetchFailureClass(fetched.error),
                host = host,
                path = path,
                scheme = scheme,
            )
        }
        val fallback = when (kind) {
            ChapterFailureKind.AUTH_REQUIRED -> "Source login required (HTTP $status)"
            ChapterFailureKind.RATE_LIMITED -> "Source rate limit/challenge (HTTP 429); retry later"
            ChapterFailureKind.NOT_FOUND -> "Chapter not found (HTTP $status)"
            ChapterFailureKind.SERVER -> "Source server error (HTTP $status)"
            ChapterFailureKind.SOURCE -> "Source rejected the chapter (HTTP $status)"
            else -> "Network error while fetching the chapter"
        }
        return ChapterDownloadException(
            kind,
            retryable,
            downloadMessage(pluginError, sourceId, fallback, sourceName = sourceName),
            pluginError,
        )
    }

    private fun fetchFailureClass(error: Throwable?): String = when (error) {
        is SsrfBlockedException -> "SsrfBlocked"
        is ResponseTooLargeException -> "ResponseTooLarge"
        is HttpRequestTimeoutException, is TimeoutCancellationException -> "Timeout"
        else -> "Network"
    }

    private fun httpReason(status: Int): String? =
        runCatching { HttpStatusCode.fromValue(status).description }.getOrNull()

    private fun pluginFailure(
        error: MiruError,
        sourceId: String? = null,
        operation: String = "download",
        sourceName: String? = null,
    ): ChapterDownloadException {
        val pluginError = error as? PluginError
        val (kind, retryable, fallback) = when (error) {
            is PluginError.CfChallengeRequired -> Triple(
                ChapterFailureKind.CLOUDFLARE,
                false,
                "Cloudflare verification required",
            )
            is PluginError.Network, is PluginError.DnsFailure -> Triple(
                ChapterFailureKind.NETWORK,
                true,
                "Network error while fetching the chapter",
            )
            is PluginError.UserMessage -> Triple(
                if (error.authUrl != null) ChapterFailureKind.AUTH_REQUIRED else ChapterFailureKind.SOURCE,
                false,
                if (error.authUrl != null) "Source login required" else "Source could not parse the chapter",
            )
            is PluginError.ResponseTooLarge -> Triple(
                ChapterFailureKind.SOURCE,
                false,
                "Source chapter is too large",
            )
            else -> Triple(ChapterFailureKind.SOURCE, false, "Source could not parse the chapter")
        }
        return ChapterDownloadException(
            kind,
            retryable,
            downloadMessage(pluginError, sourceId, fallback, operation, sourceName),
            pluginError,
        )
    }

    // One-line detail for the download/reader row (the queue flattens
    // whitespace and caps its DB field): source + operation + HTTP/class +
    // host+path.
    private fun downloadMessage(
        error: PluginError?,
        sourceId: String?,
        fallback: String,
        operation: String = "download",
        sourceName: String? = null,
    ): String {
        val detail = buildPluginErrorReport(
            error = error,
            sourceId = sourceId,
            sourceName = sourceName,
            operation = operation,
            fallbackMessage = fallback,
        ).detailText.lineSequence().joinToString(" ").trim()
        return detail.ifBlank { fallback }
    }

    private fun failDownload(
        kind: ChapterFailureKind,
        message: String,
        retryable: Boolean,
        pluginError: PluginError? = null,
    ): Nothing = throw ChapterDownloadException(kind, retryable, message, pluginError)

    // One line per successful chapter open. INFO so support exports include it;
    // never includes chapter text, URLs or keys.
    private fun logChapterContent(content: ChapterContent, startedNanos: Long) {
        MiruLog.i(
            "chapter-source",
            chapterSourceLogLine(
                source = content.source ?: chapterSourceFor(content.bookUrl, safCacheHit = false),
                chapterId = content.chapterId,
                chapterIndex = content.chapterIndex,
                bytes = content.html.toByteArray().size,
                elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000,
                cacheHit = content.source == ChapterSource.LOCAL_SAF_CACHE,
            ),
        )
    }

    // LocalBookProvider already maps text/pages to the app ChapterContent
    // model; CBZ pages become image HTML and then Image blocks.
    private suspend fun localChapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? {
        val adapter = local ?: return null
        val chapterUrl = LocalBookProvider.chapterUrl(realUrl(bookUrl), chapterIndex)
        return adapter.chapterContent(realUrl(bookUrl), chapterUrl)
    }

    // Library state lives in the Room store; downloads still wait for the queue.
    override suspend fun moveShelf(bookUrl: String, shelfId: String) {
        library?.setShelf(bookUrl, shelfId)
        details[bookUrl]?.let { details[bookUrl] = it.copy(shelfId = shelfId) }
    }

    override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean): MembershipResult =
        setInLibrary(bookUrl, inLibrary, installId = null)

    override suspend fun setInLibrary(
        bookUrl: String,
        inLibrary: Boolean,
        installId: String?,
    ): MembershipResult {
        val repo = library ?: return MembershipResult(false)
        // The explicit target is published before any lock/DB work, so an
        // older in-flight call can see it is stale and skip its write.
        latestMembershipTarget[bookUrl] = inLibrary
        if (isLocalFileBook(realUrl(bookUrl))) {
            return if (inLibrary) {
                addLocalSafToLibrary(repo, bookUrl)
            } else {
                removeLocalSafFromLibrary(repo, bookUrl)
            }
        }
        return if (inLibrary) {
            addToLibrary(repo, bookUrl)
        } else {
            removeFromLibrary(repo, bookUrl, installId)
        }
    }

    private suspend fun addToLibrary(
        repo: LibraryRepository,
        bookUrl: String,
    ): MembershipResult {
        val localBook = LocalBookProvider.isLocalBook(realUrl(bookUrl))
        val key = (PluginBookIndex.sourceForBook(bookUrl) ?: if (localBook) "local" else "unknown") +
            "|" + bookUrl
        val lock = membershipLocks.getOrPut(key) { Mutex() }

        // Ensure the row exists first. Cached page metadata keeps this a local
        // write; only a never-opened book falls back to the plugin network call,
        // and that happens outside the membership mutex.
        if (repo.book(bookUrl) == null) {
            val cached = details[bookUrl]
            val cachedMeta = pluginMeta[bookUrl]
            if (!localBook && cached != null && cachedMeta != null) {
                persistCachedRow(bookUrl, cachedMeta, cached)
            } else {
                loadBook(bookUrl, persist = true)
            }
        }

        val result = lock.withLock {
            if (latestMembershipTarget[bookUrl] != true) {
                return@withLock MembershipResult(success = true, superseded = true)
            }
            val row = repo.book(bookUrl) ?: run {
                // A just-committed remove may have deleted the row after the
                // pre-lock check; cached metadata can recreate it locally.
                val cached = details[bookUrl]
                val cachedMeta = pluginMeta[bookUrl]
                if (!localBook && cached != null && cachedMeta != null) {
                    persistCachedRow(bookUrl, cachedMeta, cached)
                } else {
                    null
                }
            } ?: return@withLock MembershipResult(false)
            if (!row.inLibrary) repo.addToLibrary(row.bookId, now())
            details[bookUrl]?.let {
                details[bookUrl] = it.copy(inLibrary = true, shelfId = row.shelfId)
            }
            MembershipResult(success = true)
        }
        // The library card shows the chapter count/progress as soon as the
        // caller reloads the library. Metadata-only add leaves totalChapters=0,
        // and the async enrichment would finish AFTER that reload, so the card
        // stayed empty. Fetch the list once here (cached when the book page
        // already loaded it) before returning; a failure still lets the add
        // succeed and the update service fills the list later.
        if (result.success && !result.superseded && !localBook) {
            try {
                chapters(bookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: the card keeps its progress once chapters land.
            }
        }
        // Chapters/cover/tags never delay the visible membership state.
        if (result.success && !result.superseded && !localBook) enqueueEnrichment(bookUrl)
        return result
    }

    private suspend fun removeFromLibrary(
        repo: LibraryRepository,
        bookUrl: String,
        installId: String? = null,
    ): MembershipResult {
        val localBook = LocalBookProvider.isLocalBook(realUrl(bookUrl))
        val key = (PluginBookIndex.sourceForBook(bookUrl) ?: if (localBook) "local" else "unknown") +
            "|" + bookUrl
        val lock = membershipLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            if (latestMembershipTarget[bookUrl] != false) {
                return@withLock MembershipResult(success = true, superseded = true)
            }
            // One transaction: collect every owned path, then delete books +
            // FTS. Missing rows are an idempotent success.
            val deleted = try {
                // Null installId arrives before BookDetails loads. Resolve the
                // stored raw id instead of falling back to URL-wide delete.
                repo.deleteBookWithFiles(
                    bookUrl,
                    installId ?: repo.book(bookUrl)?.installId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MiruLog.w("library", "Library remove failed for $bookUrl", e)
                return@withLock MembershipResult(false)
            }
            // Keep page metadata for a fast re-add, but report the real flag;
            // chapter caches must go so a re-add refetches/persists the list.
            details[bookUrl]?.let { details[bookUrl] = it.copy(inLibrary = false) }
            chapterLists.remove(bookUrl)
            chapterUrls.remove(bookUrl)
            chapterTitles.remove(bookUrl)
            invalidateChapterStatus(bookUrl)
            var cleanupFailed = false
            if (deleted.files.isNotEmpty()) {
                // DB commit already happened. File I/O is IO-only and a
                // failure must not resurrect the row or report remove failed.
                val cleanup = runCatching {
                    withContext(Dispatchers.IO) { fileCleanup(deleted.files) }
                }
                cleanupFailed = cleanup.isFailure
                cleanup.exceptionOrNull()?.let {
                    MiruLog.w("library", "Removed $bookUrl but file cleanup failed", it)
                }
            }
            try {
                onBookRemoved(bookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MiruLog.w("library", "Offline video cleanup failed", e)
            }
            MembershipResult(success = true, fileCleanupFailed = cleanupFailed)
        }
    }

    // One pass per book: cached/DB chapter list, cover, then tags + one FTS
    // rebuild. A second add while this runs reuses the same job.
    private fun enqueueEnrichment(bookUrl: String) {
        enrichmentJobs.compute(bookUrl) { _, running ->
            if (running?.isActive == true) {
                running
            } else {
                scope.launch {
                    try {
                        if (chapterLists[bookUrl] == null) chapters(bookUrl)
                        ensureCover(bookUrl)
                        refreshTags(bookUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MiruLog.w("library", "Library enrichment failed for $bookUrl", e)
                    } finally {
                        enrichmentJobs.remove(bookUrl)
                    }
                }
            }
        }
    }

    private suspend fun refreshTags(bookUrl: String) {
        val repo = library ?: return
        val meta = pluginMeta[bookUrl] ?: return
        val row = repo.book(bookUrl) ?: return
        if (!row.inLibrary) return
        saveTags(repo, row.bookId, meta, now())
    }

    // Repair trigger: a persisted dead-cover error means the stored URL itself
    // is stale, so re-resolve it from the source instead of retrying the same
    // one (Hitomi tokens). Internal for CoverRefreshTest.
    internal suspend fun ensureCover(bookUrl: String) {
        val row = library?.book(bookUrl) ?: return
        if (coverStore?.needsRepair(row.bookId) == true) {
            refreshCover(bookUrl)
            return
        }
        ensureCover(row)
    }

    // Cover failures must never break membership/history bookkeeping: the
    // store records the retry state and the next backfill picks it up.
    private suspend fun ensureCover(row: BookEntity) {
        val store = coverStore ?: return
        val url = row.coverUrl?.takeIf { it.isNotBlank() } ?: return
        try {
            store.ensureCover(row.bookId, row.installId, url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // lastError/retryAt already persisted by the store when possible.
        }
    }

    // The download engine owns body/page rows; drop the status snapshot so the
    // next chapter list reflects the delete instead of a stale downloaded flag.
    override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) {
        invalidateChapterStatus(bookUrl)
    }

    override suspend fun downloadNext100(bookUrl: String) = Unit

    override suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) {
        library?.markChapterRead(bookUrl, chapterIndex, read, now())
        invalidateChapterStatus(bookUrl)
    }

    override suspend fun markChaptersRead(
        bookUrl: String,
        chapterIndexes: Set<Int>,
        read: Boolean,
    ) {
        library?.markChaptersRead(bookUrl, chapterIndexes, read, now())
        invalidateChapterStatus(bookUrl)
    }

    override suspend fun deleteChapterTranslations(
        bookUrl: String,
        chapterIndexes: Set<Int>,
    ): Int {
        val deleted = library?.deleteChapterTranslations(bookUrl, chapterIndexes) ?: 0
        if (deleted > 0) invalidateChapterStatus(bookUrl)
        return deleted
    }

    private suspend fun saveTags(
        repo: LibraryRepository,
        bookId: Long,
        meta: BookMeta,
        timestamp: Long,
    ) {
        val tags = buildList {
            meta.genres.forEach { add(it to "GENRE") }
            extraTags(meta.extras).forEach { add(it to "TAG") }
        }.mapNotNull { (name, kind) ->
            val normalized = normalizeTag(name)
            if (normalized.isBlank()) {
                null
            } else {
                TagEntity(
                    name = name.trim(),
                    normalizedName = normalized,
                    kind = kind,
                    createdAt = timestamp,
                )
            }
        }
        // One batch + one FTS rebuild, not a reindex per tag.
        repo.replaceBookTags(bookId, tags)
    }
}

// Status overlay is pure so tests cover the mapping without Room/Compose.
internal fun applyChapterStatus(
    chapters: List<BookChapter>,
    snapshot: ChapterStatusSnapshot?,
    offline: Boolean,
): List<BookChapter> {
    val lastIndex = snapshot?.lastIndex ?: chapters.maxOfOrNull { it.index }
    return chapters.map { chapter ->
        val url = chapter.url
        val downloaded = !offline && snapshot != null && (
            snapshot.downloadedIndexes.contains(chapter.index) ||
                (url != null && snapshot.downloadedUrls.contains(url))
            )
        val translated = snapshot != null && (
            snapshot.translatedIndexes.contains(chapter.index) ||
                (url != null && snapshot.translatedUrls.contains(url))
            )
        val download = snapshot?.downloads?.get(chapter.index)
        val downloadState = download?.state?.takeUnless { it == "COMPLETED" }
        chapter.copy(
            isRead = snapshot?.readIndexes?.contains(chapter.index) == true,
            downloaded = downloaded,
            translated = translated,
            isLast = chapter.index == lastIndex,
            offline = offline,
            isLastRead = snapshot != null && (
                snapshot.lastReadIndex == chapter.index ||
                    (url != null && snapshot.lastReadUrl == url)
                ),
            downloading = downloadState == "QUEUED" || downloadState == "RUNNING",
            downloadState = downloadState,
            downloadProgress = download?.progress?.toFloat() ?: 0f,
            translationState = snapshot?.translationStates?.get(chapter.index)
                ?.takeUnless { it.equals("COMPLETE", ignoreCase = true) },
            sizeBytes = snapshot?.sizeBytes?.get(chapter.index) ?: 0L,
        )
    }
}

// Existing content came from the local file/DB, not the plugin network.
internal fun chapterSourceFor(bookUrl: String, safCacheHit: Boolean): ChapterSource = when {
    isLocalFileBook(bookKeyUrl(bookUrl)) ->
        if (safCacheHit) ChapterSource.LOCAL_SAF_CACHE else ChapterSource.LOCAL_SAF_DIRECT
    LocalBookProvider.isLocalBook(bookKeyUrl(bookUrl)) -> ChapterSource.IMPORTED_DB_BODY
    else -> ChapterSource.PLUGIN_NETWORK
}

internal fun chapterSourceLogLine(
    source: ChapterSource,
    chapterId: Long?,
    chapterIndex: Int?,
    bytes: Int,
    elapsedMs: Long,
    cacheHit: Boolean,
): String = "source=$source chapterId=${chapterId ?: "-"} " +
    "chapterIndex=${chapterIndex ?: "-"} bytes=$bytes elapsedMs=$elapsedMs " +
    "cache=${if (cacheHit) "hit" else "miss"}"

private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(16)

// Chapter identity is the resolved URL. Providers occasionally repeat a URL
// (mirrors/fixtures); keep the first occurrence and reindex contiguously so
// chapterUrls, seeds and the reader feed all agree.
internal fun dedupeChapters(chapters: List<BookChapter>): List<BookChapter> {
    val seen = HashSet<String>()
    return chapters
        .filter { chapter ->
            val url = chapter.url
            url.isNullOrBlank() || seen.add(url)
        }
        .mapIndexed { position, chapter -> chapter.copy(index = position + 1) }
}

// Book-page view of an imported local book's cover. Kept as a tiny seam so
// the EPUB/FB2/CBZ/folder precedence is testable without Room/plugin graph.
internal suspend fun localBookCoverUrl(
    row: BookEntity?,
    coverStore: BookCoverStore?,
): String? = row?.let { resolveStoredCover(coverStore, it.bookId, it.coverUrl) }

// Plugin ratings arrive as a raw string: a bare number on the 0-5 scale, or
// "value/max" (the corpus sends "9.5/10" — see chikari.lua). Scale a "/max"
// form onto 0-5 and clamp; anything unparseable is 0 ("no rating").
internal fun parsePluginRating(raw: String?): Float {
    val parts = raw?.trim().orEmpty().split('/')
    val value = parts.firstOrNull()?.toFloatOrNull() ?: return 0f
    if (!value.isFinite()) return 0f
    val max = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    val scaled = if (max != null) value / max * 5f else value
    return scaled.coerceIn(0f, 5f)
}

// BookMeta.status is free text; "ongoing" -> ongoing, "complete"/"finished"
// -> completed, anything else (or absent) is treated as completed.
internal fun pluginStatusOngoing(status: String?): Boolean {
    val text = status?.lowercase().orEmpty()
    return when {
        text.contains("ongoing") -> true
        text.contains("complete") || text.contains("finished") -> false
        else -> false
    }
}

private fun hostOf(url: String): String =
    runCatching { URI(url).host.orEmpty() }.getOrNull().orEmpty()

// shortcut: 1s TTL, replace with a status Flow if downloads need push updates.
private const val ChapterStatusTtlMs = 1_000L

// A repaired cover is re-resolved at most once per this window per book: a
// dead hitomi token must not turn a grid of failed covers into a request storm.
private const val COVER_REFRESH_COOLDOWN_MS = 10 * 60 * 1000L

private data class CachedChapterStatus(
    val snapshot: ChapterStatusSnapshot?,
    val generation: Long,
    val at: Long,
)

// A route passes an opaque (installId + URL) key; the database stores the real
// URL only. Kept top-level and pure so the row builder is unit-testable.
internal fun persistedBookUrl(bookKey: String): String = bookKeyUrl(bookKey)

private fun normalizeTag(raw: String): String =
    raw.trim().lowercase().replace(Regex("\\s+"), " ")

private fun extraTags(extras: Map<String, String>): List<String> =
    extras.entries
        .filter { it.key.lowercase() in setOf("tags", "tag", "warnings", "warning") }
        .flatMap { it.value.split(',', ';', '\n') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private fun extraAltTitles(extras: Map<String, String>): String {
    val values = listOfNotNull(
        extras["altTitle"] ?: extras["altTitles"],
        extras["alternativeTitle"],
    ).flatMap { it.split('|', '\n') }.map { it.trim() }.filter { it.isNotEmpty() }
    return buildJsonArray { values.forEach { add(it) } }.toString()
}

private fun extraAuthors(author: String?): String =
    buildJsonArray {
        author?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.toString()

// Pure-JVM author JSON for the direct row (no Android JSONObject stub in tests).
private fun jsonAuthors(author: String): String {
    val value = author.trim()
    if (value.isEmpty()) return "[]"
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "[\"$escaped\"]"
}

private fun normalizeStatus(raw: String?): String {
    val value = raw?.trim()?.lowercase().orEmpty()
    return when {
        value.isBlank() -> "UNKNOWN"
        "ongoing" in value || "progress" in value -> "ONGOING"
        "complete" in value || "finished" in value -> "COMPLETED"
        "hiatus" in value || "hold" in value || "pause" in value -> "HIATUS"
        else -> "UNKNOWN"
    }
}

// Manga plugins return page URLs, not HTML. Absolute URLs are required before
// the reader parser can keep stable image refs; a blank/unresolvable list is
// an empty chapter, not a text page.
internal fun resolveMangaPageUrls(pageUrls: List<String>, chapterUrl: String): List<String> =
    pageUrls.mapNotNull { resolveCoverUrl(it, chapterUrl) }

internal fun mangaPagesHtml(pageUrls: List<String>, chapterUrl: String): String? =
    mangaPagesHtml(resolveMangaPageUrls(pageUrls, chapterUrl))

internal fun mangaPagesHtml(pageUrls: List<String>): String? {
    if (pageUrls.isEmpty()) return null
    return pageUrls.joinToString(separator = "") { url ->
        // The reader parser turns &amp; back into &; a raw quote would end the
        // attribute early, so percent-encode it instead of inventing an entity.
        val safe = url.replace("\"", "%22").replace("&", "&amp;")
        "<p><img src=\"$safe\"/></p>"
    }
}

private fun metadataHash(meta: BookMeta): String {
    val payload = listOf(
        meta.title,
        meta.coverUrl.orEmpty(),
        meta.description.orEmpty(),
        meta.genres.joinToString("\u0001"),
        meta.rating.orEmpty(),
        meta.status.orEmpty(),
        meta.lastUpdated.orEmpty(),
        meta.author.orEmpty(),
        meta.extras.entries.sortedBy { it.key }.joinToString("\u0001") { "${it.key}=${it.value}" },
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
