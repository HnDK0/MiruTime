package dev.mirutime.app.features.opds.domain

import dev.mirutime.app.features.library.data.LocalImportResult
import dev.mirutime.core.api.MiruError
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.opds.OpdsFacetGroup
import dev.mirutime.core.opds.OpdsFormat
import dev.mirutime.core.opds.OpdsLink
import dev.mirutime.core.opds.OpdsSearchLink
import kotlinx.coroutines.flow.StateFlow

/** A saved OPDS endpoint. Credentials live outside the database (see OpdsCredentialStore). */
data class OpdsCatalog(
    val id: String,
    val name: String,
    val url: String,
    val addedAt: Long,
)

/** An entry of a navigation feed: another feed to open. */
data class OpdsCatalogLink(
    val title: String,
    val url: String,
    /** Server-provided blurb, usually a count ("729 новых книг"). */
    val subtitle: String? = null,
    /** Atom link relation, kept so "new arrivals" can be preferred on entry. */
    val rel: String? = null,
    /** Entry author/cover, used when a paged feed lists publications as links. */
    val author: String? = null,
    val coverUrl: String? = null,
)

/** Why an acquisition link is listed but cannot be added to the library. */
enum class OpdsUnavailableReason { Sample, Buy, Borrow, Subscribe, UnsupportedType, IndirectOnly }

/**
 * One acquisition the entry offers. [key] is what the detail page passes back;
 * the URL is the only stable identifier a feed gives us.
 */
data class OpdsBookOption(
    /** Null when the media type/href is not one of our readers. */
    val format: OpdsFormat?,
    val url: String,
    val rel: String,
    /** Non-null = listed for information, never downloaded. */
    val unavailableReason: OpdsUnavailableReason? = null,
) {
    val key: String get() = url
    val isDownloadable: Boolean get() = unavailableReason == null

    /** Chip text: the readable format, else the relation a feed named it by. */
    val label: String
        get() = when (format) {
            OpdsFormat.EPUB -> "EPUB"
            OpdsFormat.FB2, OpdsFormat.FB2_ZIP -> "FB2"
            OpdsFormat.CBZ -> "CBZ"
            null -> rel.substringAfterLast('/').uppercase().ifBlank { "FILE" }
        }
}

data class OpdsBook(
    val title: String,
    val author: String?,
    val summary: String?,
    val coverUrl: String?,
    /** Default option's format; null when nothing is downloadable. */
    val format: OpdsFormat?,
    /** Default option's URL; null when nothing is downloadable. */
    val downloadUrl: String?,
    /** Atom categories (label, else term). */
    val categories: List<String> = emptyList(),
    /**
     * Stable entry identity for `opds_entries` and later re-opens. Derived from
     * the Atom `id` or the entry's own feed link, so it survives a feed rename.
     */
    val entryUrl: String? = null,
    /** Every acquisition, direct formats first. Empty when none was parsed. */
    val options: List<OpdsBookOption> = emptyList(),
) {
    val isDownloadable: Boolean get() = downloadUrl != null && format != null
}

/** The subset of a catalogue the book layer needs to add an entry. */
interface OpdsAddPort {
    suspend fun addEntry(
        catalogUrl: String,
        entryUrl: String,
        optionKey: String?,
        catalogName: String,
    ): Outcome<String>

    suspend fun resolveEntry(catalogUrl: String, entryUrl: String): Outcome<List<OpdsBookOption>>
}

data class OpdsPage(
    val url: String,
    val title: String,
    val links: List<OpdsCatalogLink>,
    val books: List<OpdsBook>,
    val nextUrl: String?,
    /** Feed search template; null when the catalog has no search link. */
    val searchTemplate: String?,
    /** Feed navigation facets (subject/language/...), already grouped. */
    val facets: List<OpdsFacetGroup> = emptyList(),
    /** `rel=http://opds-spec.org/sort/...` links: the feed's own ordering. */
    val sortLinks: List<OpdsLink> = emptyList(),
    /**
     * The feed's search link. [OpdsSearchLink.type] tells whether the href is a
     * template or an OpenSearch description document to resolve first.
     */
    val searchLink: OpdsSearchLink? = null,
    /** Entry URL -> library key, from one batch lookup. Empty = nothing known. */
    val membership: Map<String, String> = emptyMap(),
)

sealed class OpdsFailure : MiruError {
    /** 401 with a Basic challenge: credentials are missing or stale. */
    data object NeedsLogin : OpdsFailure()

    /**
     * 400 `Unsupported authentication method`: calibre in its default digest
     * mode, which a Basic-only client cannot satisfy.
     */
    data object DigestOnly : OpdsFailure()

    /** 401/403 without a usable challenge: logged in, but not allowed. */
    data object Forbidden : OpdsFailure()

    data class Network(val status: Int?) : OpdsFailure()

    /** Server file is bigger than the download budget. */
    data class TooLarge(val limitMb: Int) : OpdsFailure()

    /** The feed entry offers no epub/fb2/cbz, only pdf/djvu/mobi/rtf/txt. */
    data object NotDownloadable : OpdsFailure()

    /** The chosen acquisition is a sample/buy/borrow link, not the book. */
    data class Unavailable(val reason: OpdsUnavailableReason) : OpdsFailure()

    data object NotAFeed : OpdsFailure()

    data object JsonCatalog : OpdsFailure()

    data object Malformed : OpdsFailure()

    data class Unknown(val detail: String) : OpdsFailure()
}

interface OpdsController {
    val catalogs: StateFlow<List<OpdsCatalog>>

    /** Re-reads saved catalogs from storage. */
    suspend fun refresh()

    suspend fun add(name: String, url: String, login: String, password: String): Outcome<OpdsCatalog>

    suspend fun remove(id: String)

    /** Replaces the saved login/password without touching the catalog row. */
    suspend fun updateCredentials(id: String, login: String, password: String)

    /** [url] null opens the catalog root. */
    suspend fun page(catalogId: String, url: String?): Outcome<OpdsPage>

    /** Resolves the feed search template for [query]; null when unsupported. */
    fun searchUrl(catalogId: String, page: OpdsPage, query: String): String?

    /**
     * Raw GET of a non-feed document. A feed's search link may point at an
     * OpenSearch description instead of a template; parsing that is the
     * caller's job.
     */
    suspend fun text(catalogId: String, url: String): Outcome<String> =
        Outcome.Err(OpdsFailure.Unknown("unsupported"))

    /** Downloads the chosen format and imports it into the local library. */
    suspend fun download(catalogId: String, book: OpdsBook): Outcome<LocalImportResult>

    /**
     * Resolves a publication that carries no acquisition of its own (Gutenberg
     * lists books as links to their own Atom feed).
     */
    suspend fun resolveEntry(catalogUrl: String, entryUrl: String): Outcome<List<OpdsBookOption>>

    /**
     * Downloads the chosen option and imports it as a library book carrying
     * catalogue provenance. Returns the local `local://` book URL.
     */
    suspend fun addEntry(
        catalogUrl: String,
        entryUrl: String,
        optionKey: String?,
        catalogName: String,
    ): Outcome<String>
}
