package dev.mirutime.app.features.opds.data

import dev.mirutime.app.features.library.data.ImportOrigin
import dev.mirutime.app.features.library.data.LocalImportPort
import dev.mirutime.app.features.library.data.LocalImportResult
import dev.mirutime.app.features.opds.domain.OpdsAddPort
import dev.mirutime.app.features.opds.domain.OpdsBook
import dev.mirutime.app.features.opds.domain.OpdsBookOption
import dev.mirutime.app.features.opds.domain.OpdsCatalog
import dev.mirutime.app.features.opds.domain.OpdsCatalogLink
import dev.mirutime.app.features.opds.domain.OpdsController
import dev.mirutime.app.features.opds.domain.OpdsFailure
import dev.mirutime.app.features.opds.domain.OpdsPage
import dev.mirutime.app.features.opds.domain.OpdsUnavailableReason
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.log.MiruLog
import dev.mirutime.core.opds.OpdsEntry
import dev.mirutime.core.opds.OpdsError
import dev.mirutime.core.opds.OpdsFeed
import dev.mirutime.core.opds.OpdsFeeds
import dev.mirutime.core.opds.OpdsFileNormalizer
import dev.mirutime.core.opds.OpdsFormat
import dev.mirutime.core.opds.OpdsMediaTypes
import dev.mirutime.core.opds.OpdsParser
import dev.mirutime.core.opds.OpdsSearchTemplate
import dev.mirutime.core.network.security.ResponseTooLargeException
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.OpdsCatalogEntity
import dev.mirutime.data.library.OpdsEntryEntity
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val OPDS_SCHEME_HTTP = "http://"
private const val OPDS_SCHEME_HTTPS = "https://"
private const val MAX_NAME_LENGTH = 80

// Calibre's default digest-only mode answers a Basic request with this body.
private const val DIGEST_ONLY_MARKER = "Unsupported authentication method"

/**
 * Catalog browsing + download over [OpdsTransport]. Credentials live in
 * [OpdsCredentialStore], never in the database, so a backup export cannot
 * carry them.
 */
class OpdsControllerImpl(
    private val library: LibraryRepository,
    private val credentials: OpdsCredentialStore,
    private val transport: OpdsTransport,
    // Nullable only for JVM tests: the real importer needs an Android Context.
    // Production wiring always passes it.
    private val importer: LocalImportPort? = null,
    private val cacheDir: File,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    // Host auth for Coil covers of private catalogs. Defaults keep JVM tests
    // and previews free of the plugin graph.
    private val registerHost: (sourceId: String, feedUrl: String, authorization: String?) -> Unit =
        { _, _, _ -> },
    private val clearHost: (sourceId: String, feedUrl: String) -> Unit = { _, _ -> },
) : OpdsController, OpdsAddPort {
    private val _catalogs = MutableStateFlow<List<OpdsCatalog>>(emptyList())
    override val catalogs: StateFlow<List<OpdsCatalog>> = _catalogs.asStateFlow()

    // Indirect publications (Gutenberg) are one feed per entry: the detail page
    // and the add both ask for the same list, so keep it for the session.
    private val resolvedOptions = ConcurrentHashMap<String, List<OpdsBookOption>>()

    override suspend fun refresh() {
        _catalogs.value = library.opdsCatalogs().map { it.toCatalog() }
    }

    override suspend fun add(
        name: String,
        url: String,
        login: String,
        password: String,
    ): Outcome<OpdsCatalog> {
        val normalized = normalizeUrl(url) ?: return Outcome.Err(OpdsFailure.Unknown("invalid-url"))
        val canonical = canonicalCatalogUrl(normalized)
        if (library.opdsCatalogs().any { canonicalCatalogUrl(it.url) == canonical }) {
            return Outcome.Err(OpdsFailure.Unknown("duplicate-url"))
        }
        val entity = OpdsCatalogEntity(
            id = newId(),
            name = name.trim().ifBlank { normalized },
            url = normalized,
            addedAt = now(),
        )
        library.saveOpdsCatalog(entity)
        if (login.isNotBlank() || password.isNotBlank()) {
            withContext(Dispatchers.IO) { credentials.put(entity.id, login, password) }
        }
        registerHost(entity.id, normalized, authorizationFor(entity.id))
        refresh()
        MiruLog.event(
            "opds",
            "opds_catalog_add",
            "host" to hostOf(normalized),
            "auth" to (login.isNotBlank() || password.isNotBlank()),
        )
        return Outcome.Ok(entity.toCatalog())
    }

    override suspend fun remove(id: String) {
        val url = library.opdsCatalogs().firstOrNull { it.id == id }?.url
        library.deleteOpdsCatalog(id)
        if (url != null) library.deleteOpdsEntries(url)
        withContext(Dispatchers.IO) { credentials.remove(id) }
        if (url != null) clearHost(id, url)
        resolvedOptions.clear()
        refresh()
    }

    override suspend fun updateCredentials(id: String, login: String, password: String) {
        withContext(Dispatchers.IO) { credentials.put(id, login, password) }
        // The options were resolved with the previous credentials.
        resolvedOptions.clear()
        // Covers are fetched by host, so the registry copy must follow the new
        // password or a private catalogue keeps serving 401s for its artwork.
        val url = library.opdsCatalogs().firstOrNull { it.id == id }?.url ?: return
        registerHost(id, url, authorizationFor(id))
    }

    override suspend fun page(catalogId: String, url: String?): Outcome<OpdsPage> {
        val catalog = library.opdsCatalogs().firstOrNull { it.id == catalogId }
            ?: return Outcome.Err(OpdsFailure.Unknown("unknown-catalog"))
        val target = url?.trim()?.takeIf { it.isNotBlank() } ?: catalog.url
        return when (val loaded = loadFeed(catalog, target)) {
            is Outcome.Err -> loaded
            is Outcome.Ok -> Outcome.Ok(loaded.value.toPage())
        }
    }

    /** One feed GET + parse, mapped onto the typed failure vocabulary. */
    private suspend fun loadFeed(
        catalog: OpdsCatalogEntity,
        target: String,
    ): Outcome<OpdsFeed> {
        val authorization = authorizationFor(catalog.id)
        registerHost(catalog.id, catalog.url, authorization)
        val fetch = try {
            transport.get(target, authHeaders(catalog.url, target, authorization))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            MiruLog.event(
                "opds",
                "opds_page",
                "host" to hostOf(target),
                "status" to 0,
                "error" to e::class.simpleName,
            )
            return Outcome.Err(OpdsFailure.Network(null))
        }
        MiruLog.event(
            "opds",
            "opds_page",
            "host" to hostOf(target),
            "status" to fetch.status,
        )
        if (fetch.status !in 200..299) return Outcome.Err(failureFor(fetch))

        val bytes = fetch.bytes ?: return Outcome.Err(OpdsFailure.Network(fetch.status))
        return when (val parsed = OpdsFeeds.parse(bytes, fetch.finalUrl)) {
            is Outcome.Ok -> parsed
            is Outcome.Err -> Outcome.Err(
                when (parsed.error) {
                    is OpdsError.JsonCatalogUnsupported -> OpdsFailure.JsonCatalog
                    is OpdsError.NotAFeed -> OpdsFailure.NotAFeed
                    is OpdsError.Malformed -> OpdsFailure.Malformed
                    else -> OpdsFailure.Unknown("parse-failed")
                },
            )
        }
    }

    override fun searchUrl(catalogId: String, page: OpdsPage, query: String): String? {
        val template = page.searchTemplate ?: return null
        return OpdsSearchTemplate.substitute(template, query)
    }

    override suspend fun text(catalogId: String, url: String): Outcome<String> {
        val catalog = library.opdsCatalogs().firstOrNull { it.id == catalogId }
            ?: return Outcome.Err(OpdsFailure.Unknown("unknown-catalog"))
        val authorization = authorizationFor(catalogId)
        registerHost(catalog.id, catalog.url, authorization)
        val fetch = try {
            transport.get(url, authHeaders(catalog.url, url, authorization))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            MiruLog.event(
                "opds",
                "opds_document",
                "host" to hostOf(url),
                "status" to 0,
                "error" to e::class.simpleName,
            )
            return Outcome.Err(OpdsFailure.Network(null))
        }
        MiruLog.event("opds", "opds_document", "host" to hostOf(url), "status" to fetch.status)
        if (fetch.status !in 200..299) return Outcome.Err(failureFor(fetch))
        val bytes = fetch.bytes ?: return Outcome.Err(OpdsFailure.Network(fetch.status))
        return Outcome.Ok(bytes.decodeToString())
    }

    override suspend fun download(catalogId: String, book: OpdsBook): Outcome<LocalImportResult> {
        val format = book.format
        val downloadUrl = book.downloadUrl
        if (format == null || downloadUrl == null) {
            return Outcome.Err(OpdsFailure.Unknown("not-downloadable"))
        }
        val catalog = library.opdsCatalogs().firstOrNull { it.id == catalogId }
            ?: return Outcome.Err(OpdsFailure.Unknown("unknown-catalog"))
        return downloadAndImport(catalog, format, downloadUrl, book.title, origin = null)
    }

    override suspend fun resolveEntry(
        catalogUrl: String,
        entryUrl: String,
    ): Outcome<List<OpdsBookOption>> {
        val catalog = library.opdsCatalogs().firstOrNull { it.url == catalogUrl }
            ?: return Outcome.Err(OpdsFailure.Unknown("unknown-catalog"))
        return resolveOptions(catalog, entryUrl)
    }

    /**
     * Options for [entryUrl], resolved without ever mistaking the entry URL for
     * a feed. A cached row remembers the page it was seen in: that page is
     * fetched once and the entry is looked up there. Only an entry with no
     * known feed page is fetched itself (an Atom publication feed).
     */
    private suspend fun resolveOptions(
        catalog: OpdsCatalogEntity,
        entryUrl: String,
    ): Outcome<List<OpdsBookOption>> {
        val cacheKey = optionsKey(catalog.url, entryUrl)
        resolvedOptions[cacheKey]?.let { return Outcome.Ok(it) }
        val stored = library.opdsEntry(catalog.url, entryUrl)
        // The stored page may list the publication as a link (Gutenberg), so an
        // empty result there is not final: the entry URL itself may be a feed.
        val fromPage = if (stored != null && stored.feedUrl.isNotBlank()) {
            optionsFromFeedPage(catalog, stored.feedUrl, entryUrl)
        } else {
            null
        }
        val pageOptions = (fromPage as? Outcome.Ok)?.value.orEmpty()
        val entryIndirect = fromPage is Outcome.Err &&
            (fromPage.error == OpdsFailure.NotDownloadable)
        val options = if (pageOptions.isNotEmpty()) {
            pageOptions
        } else if (fromPage == null || entryIndirect) {
            val feed = when (val loaded = loadFeed(catalog, entryUrl)) {
                // The page said "not here"; a real fetch failure is the truer
                // answer and must not be hidden behind NotDownloadable.
                is Outcome.Err -> return loaded
                is Outcome.Ok -> loaded.value
            }
            optionsForEntry(feed, entryUrl)
                ?: return (fromPage as? Outcome.Err) ?: Outcome.Err(OpdsFailure.NotDownloadable)
        } else {
            return (fromPage as Outcome.Err)
        }
        resolvedOptions[cacheKey] = options
        return Outcome.Ok(options)
    }

    /** Cached options are per catalogue: the same host may serve two of them. */
    private fun optionsKey(catalogUrl: String, entryUrl: String): String =
        canonicalCatalogUrl(catalogUrl) + "\n" + entryUrl

    /**
     * Options of the entry the user asked for: never silently another book's.
     * A bare feed with exactly one publication is the indirect-feed shape; more
     * than one is ambiguous and fails instead of guessing.
     */
    private fun optionsForEntry(feed: OpdsFeed, entryUrl: String): List<OpdsBookOption>? {
        val requested = feed.entries.firstOrNull { it.entryUrl() == entryUrl }
        val entry = requested ?: feed.entries
            .filter { it.acquisitionLinks.isNotEmpty() }
            .singleOrNull()
            ?: return null
        return entry.toOptions().takeIf { it.isNotEmpty() }
    }

    /** Fetches the page [entryUrl] was listed in and re-caches what it offers. */
    private suspend fun optionsFromFeedPage(
        catalog: OpdsCatalogEntity,
        feedUrl: String,
        entryUrl: String,
    ): Outcome<List<OpdsBookOption>> {
        val feed = when (val loaded = loadFeed(catalog, feedUrl)) {
            is Outcome.Err -> return loaded
            is Outcome.Ok -> loaded.value
        }
        // The entry may be an indirect publication (no acquisition links): it
        // exists in the page, but its files live one feed down.
        val entry = feed.entries.firstOrNull { it.entryUrl() == entryUrl }
            ?: return Outcome.Err(OpdsFailure.Unknown("entry-not-found"))
        val options = entry.toOptions()
        if (options.isEmpty()) return Outcome.Err(OpdsFailure.NotDownloadable)
        library.upsertOpdsEntries(
            listOf(
                OpdsEntryEntity(
                    catalogUrl = catalog.url,
                    entryUrl = entryUrl,
                    feedUrl = feed.baseUrl.ifBlank { feedUrl },
                    title = entry.title,
                    author = entry.author.orEmpty(),
                    description = entry.summary.orEmpty(),
                    coverUrl = entry.coverUrl,
                    formatsJson = OpdsEntryOptions.encode(options),
                    updatedAt = now(),
                ),
            ),
        )
        return Outcome.Ok(options)
    }

    /**
     * Explicit add: the stored entry is authoritative (it already carries the
     * options the user saw), the entry feed is the fallback for an indirect
     * publication. Membership is written by the importer, not here.
     */
    override suspend fun addEntry(
        catalogUrl: String,
        entryUrl: String,
        optionKey: String?,
        catalogName: String,
    ): Outcome<String> {
        val catalog = library.opdsCatalogs().firstOrNull { it.url == catalogUrl }
            ?: return Outcome.Err(OpdsFailure.Unknown("unknown-catalog"))
        val stored = library.opdsEntry(catalog.url, entryUrl)
        var options = stored?.formatsJson?.let(OpdsEntryOptions::decode).orEmpty()
        if (options.isEmpty()) {
            when (val resolved = resolveOptions(catalog, entryUrl)) {
                is Outcome.Err -> return resolved
                is Outcome.Ok -> options = resolved.value
            }
        }
        // A key that is no longer offered must not silently pick another file:
        // one re-resolve against the entry's feed, then a typed failure.
        val requested = optionKey?.let { key ->
            options.firstOrNull { it.key == key } ?: run {
                resolvedOptions.remove(optionsKey(catalog.url, entryUrl))
                val refreshed = when (val resolved = resolveOptions(catalog, entryUrl)) {
                    is Outcome.Err -> return resolved
                    is Outcome.Ok -> resolved.value
                }
                refreshed.firstOrNull { it.key == key }
                    ?: return Outcome.Err(OpdsFailure.Unknown("stale-option"))
            }
        }
        val chosen = requested ?: options.firstOrNull { it.isDownloadable }
        if (chosen == null) {
            val reason = options.firstOrNull()?.unavailableReason
            return Outcome.Err(
                reason?.let(OpdsFailure::Unavailable) ?: OpdsFailure.NotDownloadable,
            )
        }
        chosen.unavailableReason?.let { return Outcome.Err(OpdsFailure.Unavailable(it)) }
        val format = chosen.format ?: return Outcome.Err(OpdsFailure.NotDownloadable)
        val origin = ImportOrigin(
            catalogUrl = canonicalCatalogUrl(catalogUrl),
            catalogName = catalogName,
            entryUrl = entryUrl,
            sourceFileUrl = chosen.url,
        )
        val imported = downloadAndImport(
            catalog = catalog,
            format = format,
            downloadUrl = chosen.url,
            title = stored?.title?.takeIf { it.isNotBlank() } ?: "opds-book",
            origin = origin,
        )
        return when (imported) {
            is Outcome.Err -> imported
            is Outcome.Ok -> when (val result = imported.value) {
                is LocalImportResult.Success -> Outcome.Ok(result.bookUrl)
                is LocalImportResult.Failure -> Outcome.Err(OpdsFailure.Unknown(result.message))
            }
        }
    }

    private suspend fun downloadAndImport(
        catalog: OpdsCatalogEntity,
        format: OpdsFormat,
        downloadUrl: String,
        title: String,
        origin: ImportOrigin?,
    ): Outcome<LocalImportResult> {
        val importer = importer ?: return Outcome.Err(OpdsFailure.Unknown("importer-unavailable"))
        val authorization = authorizationFor(catalog.id)
        registerHost(catalog.id, catalog.url, authorization)
        val target = File(
            File(cacheDir, "opds"),
            sanitizeFileName(title) + format.fileNameSuffix,
        )
        try {
            val size = try {
                transport.download(
                    downloadUrl,
                    authHeaders(catalog.url, downloadUrl, authorization),
                    target,
                )
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: ResponseTooLargeException) {
                MiruLog.event(
                    "opds",
                    "opds_download",
                    "host" to hostOf(downloadUrl),
                    "bytes" to 0,
                    "error" to "too-large",
                )
                return Outcome.Err(OpdsFailure.TooLarge((e.error.limit / (1024 * 1024)).toInt()))
            } catch (e: Exception) {
                MiruLog.event(
                    "opds",
                    "opds_download",
                    "host" to hostOf(downloadUrl),
                    "bytes" to 0,
                    "error" to e::class.simpleName,
                )
                return Outcome.Err(OpdsFailure.Network(null))
            }
            MiruLog.event(
                "opds",
                "opds_download",
                "host" to hostOf(downloadUrl),
                "bytes" to size,
                "format" to format.name,
            )
            // Import parses the whole book and writes chapter files: keep it off
            // the caller's (main) thread. The stored local_book_files.uri points
            // at this cache file, deleted by the finally below; copyPath is what
            // the library reads.
            return Outcome.Ok(
                withContext(Dispatchers.IO) {
                    // An .epub.zip is "an epub packed once more"; unwrap before import.
                    if (format == OpdsFormat.EPUB) OpdsFileNormalizer.unwrapEpubZip(target)
                    importer.import(target, origin)
                },
            )
        } finally {
            // Cleanup must survive cancellation, and file I/O never belongs on
            // the caller's (main) thread.
            withContext(NonCancellable + Dispatchers.IO) { target.delete() }
        }
    }

    private suspend fun authorizationFor(catalogId: String): String? =
        // The credential file is plain file I/O: never on the caller's (usually
        // main) thread.
        withContext(Dispatchers.IO) {
            val stored = credentials.get(catalogId) ?: return@withContext null
            if (stored.login.isBlank()) return@withContext null
            basicAuthHeader(stored.login, stored.password)
        }

    /**
     * Basic auth is scoped to the catalogue's own origin: a feed is free to
     * link a file (or a search document) on another host, and the user's
     * password must not follow it there.
     */
    private fun authHeaders(
        catalogUrl: String,
        targetUrl: String,
        authorization: String?,
    ): Map<String, String> = when {
        authorization == null -> emptyMap()
        authOriginMatches(catalogUrl, targetUrl) -> mapOf("Authorization" to authorization)
        else -> emptyMap()
    }

    private fun failureFor(fetch: RemoteFetch): OpdsFailure {
        if (fetch.status == 400) {
            val body = fetch.bytes?.decodeToString().orEmpty()
            if (DIGEST_ONLY_MARKER in body) return OpdsFailure.DigestOnly
        }
        if (fetch.status == 401) return OpdsFailure.NeedsLogin
        if (fetch.status == 403) return OpdsFailure.Forbidden
        return OpdsFailure.Network(fetch.status)
    }

    private fun OpdsCatalogEntity.toCatalog() = OpdsCatalog(id, name, url, addedAt)

    private fun OpdsFeed.toPage(): OpdsPage {
        // A feed with navigation links is a menu. An OPDS 2 catalogue may attach
        // preview publications (groups) to it, and those must not turn the page
        // into a book list: the session drops sections as soon as books exist.
        val sections = entries.filter { !it.isPublication && it.acquisitions.isEmpty() }
        val books = if (sections.isEmpty()) {
            entries.filter { it.isPublication || it.acquisitions.isNotEmpty() }
        } else {
            entries.filter { !it.isPublication && it.acquisitions.isNotEmpty() }
        }
        return OpdsPage(
            url = baseUrl,
            title = title.ifBlank { baseUrl },
            links = sections.mapNotNull { it.toLink() },
            books = books.map { it.toBook() },
            nextUrl = nextUrl,
            searchTemplate = searchTemplate,
            facets = facets,
            sortLinks = sortLinks,
            searchLink = searchLink,
        )
    }

    private fun OpdsEntry.toLink(): OpdsCatalogLink? {
        val chosen = links.firstOrNull { link ->
            link.rel == "subsection" || link.rel == "element" ||
                link.rel.endsWith("/acquisition/subsection")
        }
            // A nav entry sometimes carries only rel-less links; skip artwork.
            ?: links.firstOrNull { "image" !in it.rel && "cover" !in it.rel }
            ?: links.firstOrNull()
            ?: return OpdsCatalogLink(
                title = title,
                url = id?.takeIf { it.startsWith("http") } ?: return null,
                subtitle = summary,
                author = author,
                coverUrl = coverUrl,
            )
        return OpdsCatalogLink(
            title = title,
            url = chosen.href,
            subtitle = summary,
            rel = chosen.rel.takeIf { it.isNotBlank() },
            author = author,
            coverUrl = coverUrl,
        )
    }

    private fun OpdsEntry.toBook(): OpdsBook {
        val options = toOptions()
        // An indirect-only entry still has a page to open; its default option
        // stays null so the detail page asks for the entry feed instead.
        val preferred = options.firstOrNull { it.isDownloadable }
        return OpdsBook(
            title = title,
            author = author,
            summary = summary,
            coverUrl = coverUrl,
            format = preferred?.format,
            downloadUrl = preferred?.url,
            categories = categories,
            entryUrl = entryUrl(),
            options = options,
        )
    }

    /**
     * Stable identity the catalogue itself uses: the Atom `id`, else the entry's
     * own feed (Gutenberg search lists publications as `subsection` links), else
     * the file it hands out.
     */
    private fun OpdsEntry.entryUrl(): String? =
        id?.takeIf { it.startsWith(OPDS_SCHEME_HTTP) || it.startsWith(OPDS_SCHEME_HTTPS) }
            ?: links.firstOrNull { it.rel == "subsection" }?.href
            ?: links.firstOrNull { it.rel == "alternate" }?.href
            ?: acquisitionLinks.firstOrNull()?.href

    /**
     * Every acquisition, direct formats first (reader preference order), then
     * the ones we list but never download, each with the reason.
     */
    private fun OpdsEntry.toOptions(): List<OpdsBookOption> = acquisitionLinks
        .map { link ->
            val format = OpdsMediaTypes.formatOf(
                dev.mirutime.core.opds.OpdsLink(
                    rel = link.rel,
                    href = link.href,
                    type = link.type,
                    title = null,
                ),
            )
            OpdsBookOption(
                format = format,
                url = link.href,
                rel = link.rel,
                unavailableReason = reasonFor(link.rel, format, link.indirect.isNotEmpty()),
            )
        }
        .sortedWith(
            compareBy({ it.unavailableReason != null }, { it.format?.ordinal ?: Int.MAX_VALUE }),
        )
        // An unreadable landing link ("text/html") is noise next to the real
        // files; keep it only when the entry offers nothing else.
        .let { all ->
            all.filter {
                it.format != null || it.unavailableReason != OpdsUnavailableReason.UnsupportedType
            }.ifEmpty { all }
        }

    private fun reasonFor(
        rel: String,
        format: OpdsFormat?,
        hasIndirect: Boolean,
    ): OpdsUnavailableReason? {
        if (format == null) {
            return if (hasIndirect) OpdsUnavailableReason.IndirectOnly else
                OpdsUnavailableReason.UnsupportedType
        }
        if (rel in DIRECT_ACQUISITION_RELS) return null
        return when (rel.substringAfterLast('/')) {
            "sample" -> OpdsUnavailableReason.Sample
            "buy" -> OpdsUnavailableReason.Buy
            "borrow" -> OpdsUnavailableReason.Borrow
            "subscribe" -> OpdsUnavailableReason.Subscribe
            else -> OpdsUnavailableReason.UnsupportedType
        }
    }
}

/** Relations we download without asking; the rest are listed with a reason. */
private val DIRECT_ACQUISITION_RELS = setOf(
    OpdsParser.ACQUISITION_REL,
    "${OpdsParser.ACQUISITION_REL}/open-access",
)

/** Root feed URL as entered: only the scheme and host are validated. */
internal fun normalizeUrl(raw: String): String? {
    val trimmed = raw.trim()
    val lower = trimmed.lowercase()
    if (!lower.startsWith(OPDS_SCHEME_HTTP) && !lower.startsWith(OPDS_SCHEME_HTTPS)) return null
    val host = runCatching { URI(trimmed).host }.getOrNull()
    if (host.isNullOrBlank()) return null
    return trimmed
}

/**
 * Catalogue identity for provenance and membership: scheme/host lowercased,
 * default port dropped, trailing `/` stripped. `/opds` and `/opds/` are one
 * catalogue; the stored row keeps whatever the user typed.
 */
internal fun canonicalCatalogUrl(url: String): String {
    val trimmed = url.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
    val scheme = uri.scheme?.lowercase() ?: return trimmed
    val host = uri.host?.lowercase() ?: return trimmed
    val port = uri.port.takeIf { it != -1 && it != defaultPort(scheme) }
    return buildString {
        append(scheme).append("://").append(host)
        port?.let { append(':').append(it) }
        append(uri.rawPath.orEmpty().trimEnd('/'))
        uri.rawQuery?.let { append('?').append(it) }
    }
}

/**
 * Basic auth scope: same host and port, and a scheme change is allowed only
 * upward. A catalogue that redirects `http://host/opds` to `https://host/opds`
 * keeps its credentials; a link that drops to plain http never receives them.
 */
internal fun authOriginMatches(catalogUrl: String, targetUrl: String): Boolean {
    val catalog = originOf(catalogUrl) ?: return false
    val target = originOf(targetUrl) ?: return false
    if (catalog.host != target.host) return false
    // Default ports differ per scheme: http:80 and https:443 are one host.
    val samePort = catalog.port == target.port ||
        (catalog.port == defaultPort(catalog.scheme) && target.port == defaultPort(target.scheme))
    if (!samePort) return false
    return !(catalog.scheme == "https" && target.scheme == "http")
}

private data class Origin(val scheme: String, val host: String, val port: Int)

private fun originOf(url: String): Origin? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = if (uri.port != -1) uri.port else defaultPort(scheme)
    return Origin(scheme, host, port)
}

private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

internal fun sanitizeFileName(title: String): String {
    val cleaned = title
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .take(MAX_NAME_LENGTH)
    return cleaned.ifBlank { "opds-book" }
}

private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()
