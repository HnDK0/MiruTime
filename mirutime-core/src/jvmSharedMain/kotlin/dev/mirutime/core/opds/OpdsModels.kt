package dev.mirutime.core.opds

import dev.mirutime.core.api.MiruError

/** Formats an OPDS catalog can hand us that our local readers already open. */
enum class OpdsFormat {
    EPUB,
    FB2_ZIP,
    FB2,
    CBZ,
    PDF,
    ;

    /** Suffix for the temp file name; matches LocalImportFormat's extensions. */
    val fileNameSuffix: String
        get() = when (this) {
            EPUB -> ".epub"
            FB2_ZIP, FB2 -> ".fb2"
            CBZ -> ".cbz"
            PDF -> ".pdf"
        }

    internal val priority: Int
        get() = when (this) {
            EPUB -> 0
            FB2_ZIP -> 1
            FB2 -> 2
            CBZ -> 3
            PDF -> 4
        }
}

sealed class OpdsError : MiruError {
    /** Body is not an OPDS/Atom feed (HTML login page, RSS, empty). */
    data object NotAFeed : OpdsError()

    /** Body is an OPDS 2 / JSON catalog; only Atom (1.x) is supported. */
    data object JsonCatalogUnsupported : OpdsError()

    /** XML is unreadable or the nesting cap tripped. */
    data object Malformed : OpdsError()
}

data class OpdsLink(
    val rel: String,
    val href: String,
    val type: String?,
    val title: String?,
)

data class OpdsFacet(val label: String, val href: String, val active: Boolean, val count: Int?)

data class OpdsFacetGroup(val label: String, val options: List<OpdsFacet>)

data class OpdsSearchLink(val href: String, val type: String?)

/** One acquisition link, including its relation and any indirect chain. */
data class OpdsAcquisitionLink(
    val href: String,
    val type: String?,
    val rel: String,
    val indirect: List<OpdsAcquisitionLink> = emptyList(),
)

private fun OpdsAcquisitionLink.toLink(): OpdsLink =
    OpdsLink(rel = rel, href = href, type = type, title = null)

data class OpdsEntry(
    val title: String,
    val author: String?,
    val summary: String?,
    val id: String?,
    val coverUrl: String?,
    val links: List<OpdsLink>,
    /** Atom categories: label when present, otherwise term. */
    val categories: List<String> = emptyList(),
    /** Every acquisition link of the entry, whatever its relation. */
    val acquisitionLinks: List<OpdsAcquisitionLink> = emptyList(),
    /**
     * The entry was listed under OPDS 2 `publications`: it is a book even when
     * the catalogue offers no file we can open, so it must never render as a
     * section link.
     */
    val isPublication: Boolean = false,
) {
    /** Downloads the catalog offers for this book, in feed order. */
    val acquisitions: List<OpdsLink>
        get() = acquisitionLinks.map { it.toLink() }

    /**
     * Best supported format, or null when the book has only pdf/mobi/text, only
     * sample/buy/borrow links, or only an indirect (page -> file) chain we do
     * not follow here.
     */
    fun bestAcquisition(): Pair<OpdsFormat, OpdsLink>? = acquisitionLinks
        .filter { it.rel in SELECTABLE_ACQUISITION_RELS }
        .mapNotNull { link ->
            link.toLink().let { OpdsMediaTypes.formatOf(it)?.let { format -> format to it } }
        }
        .minByOrNull { it.first.priority }
}

/**
 * Relations we download without asking; sample/preview and buy/borrow/subscribe
 * stay in [OpdsEntry.acquisitionLinks] so the app can explain why.
 */
private val SELECTABLE_ACQUISITION_RELS = setOf(
    OpdsParser.ACQUISITION_REL,
    "${OpdsParser.ACQUISITION_REL}/open-access",
)

data class OpdsFeed(
    val title: String,
    val id: String?,
    val baseUrl: String,
    val links: List<OpdsLink>,
    val entries: List<OpdsEntry>,
    val facets: List<OpdsFacetGroup> = emptyList(),
    val sortLinks: List<OpdsLink> = emptyList(),
    val searchLink: OpdsSearchLink? = null,
) {
    /** Navigation feed = no entry is a publication. */
    val isNavigation: Boolean
        get() = entries.none { it.isPublication || it.acquisitions.isNotEmpty() }

    val nextUrl: String?
        get() = links.firstOrNull { it.rel == "next" }?.href

    /** Raw search template, usually containing `{searchTerms}` or `{?query}`. */
    val searchTemplate: String?
        get() = links
            .filter { it.rel == "search" }
            .map { it.href }
            .firstOrNull { OpdsSearchTemplate.isTemplated(it) }
            ?: links.firstOrNull { it.rel == "search" }?.href

    fun searchUrl(query: String): String? {
        val template = searchTemplate ?: return null
        return OpdsSearchTemplate.substitute(template, query)
    }
}
