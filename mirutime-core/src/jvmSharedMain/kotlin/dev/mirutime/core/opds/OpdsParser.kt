package dev.mirutime.core.opds

import dev.mirutime.core.api.Outcome
import dev.mirutime.core.local.XmlElement
import dev.mirutime.core.local.parseXmlDocument
import java.net.URI

/**
 * OPDS 1.x (Atom) reader. Tolerant by design: real feeds omit the opds profile,
 * lie about media types, use legacy cover relations and relative hrefs.
 *
 * Not handled on purpose: xml:base, PSE page streaming. An indirect
 * acquisition (page -> file hop) is parsed and exposed, but never followed:
 * such an option is listed as `IndirectOnly` and stays non-downloadable — the
 * app never resolves the hop itself. OPDS 2 (JSON) is [Opds2Parser]'s job; this
 * reader only reports it as such for callers that bypass [OpdsFeeds].
 */
object OpdsParser {
    const val ACQUISITION_REL = "http://opds-spec.org/acquisition"
    private const val FACET_REL = "http://opds-spec.org/facet"
    private const val SORT_REL_PREFIX = "http://opds-spec.org/sort"

    private val COVER_RELS = listOf(
        "http://opds-spec.org/image/thumbnail",
        "http://opds-spec.org/image",
        "http://opds-spec.org/thumbnail",
        "http://opds-spec.org/cover",
        "x-stanza-cover-image-thumbnail",
        "x-stanza-cover-image",
    )

    fun parse(bytes: ByteArray, baseUrl: String): Outcome<OpdsFeed> {
        val head = preview(bytes)
        if (head.startsWith("{") || head.startsWith("[")) {
            return Outcome.Err(OpdsError.JsonCatalogUnsupported)
        }
        val root = runCatching { parseXmlDocument(bytes) }.getOrNull()
            ?: return Outcome.Err(OpdsError.Malformed)
        if (root.localName() != "feed") return Outcome.Err(OpdsError.NotAFeed)
        val feedLinks = root.childTags("link").mapNotNull { link(it, baseUrl) }
        return Outcome.Ok(
            OpdsFeed(
                title = root.child("title")?.textContent?.trim().orEmpty(),
                id = root.child("id")?.textContent?.trim()?.takeIf { it.isNotBlank() },
                baseUrl = baseUrl,
                links = feedLinks,
                entries = root.childTags("entry").mapNotNull { entry(it, baseUrl) },
                facets = facets(root.childTags("link"), baseUrl),
                sortLinks = feedLinks.filter { it.rel.startsWith(SORT_REL_PREFIX) },
                searchLink = feedLinks.firstOrNull { it.rel == "search" }
                    ?.let { OpdsSearchLink(href = it.href, type = it.type) },
            ),
        )
    }

    /** Resolves a feed href against the feed's final URL. Null when unusable. */
    fun resolveHref(baseUrl: String, href: String): String? {
        val raw = href.trim()
        if (raw.isEmpty()) return null
        // Search templates ({searchTerms}) and stray spaces are legal in feeds but not in URI.
        val escaped = raw
            .replace(" ", "%20")
            .replace("{", "%7B")
            .replace("}", "%7D")
        return runCatching { URI(baseUrl).resolve(URI(escaped)).toString() }
            .map { it.replace("%7B", "{").replace("%7D", "}") }
            .getOrNull()
    }

    // Enough bytes to tell JSON from XML, BOM included.
    internal fun preview(bytes: ByteArray): String {
        val limit = minOf(bytes.size, 256)
        val start = if (limit >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            3
        } else {
            0
        }
        return String(bytes, start, limit - start, Charsets.UTF_8).trimStart()
    }

    private fun entry(node: XmlElement, baseUrl: String): OpdsEntry? {
        val title = node.child("title")?.textContent?.trim()
        if (title.isNullOrBlank()) return null
        val links = node.childTags("link").mapNotNull { link(it, baseUrl) }
        val author = authorOf(node)
        return OpdsEntry(
            title = title,
            author = author,
            summary = descriptionOf(node, author),
            id = node.child("id")?.textContent?.trim()?.takeIf { it.isNotBlank() },
            coverUrl = links
                // An inline `data:` image is not something the image loader can fetch.
                .filterNot { it.href.startsWith("data:", ignoreCase = true) }
                .let { images ->
                    COVER_RELS.firstNotNullOfOrNull { rel ->
                        images.firstOrNull { it.rel == rel }?.href
                    }
                },
            links = links,
            categories = node.childTags("category").mapNotNull { category ->
                (category.attr("label") ?: category.attr("term"))
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.distinct(),
            acquisitionLinks = node.childTags("link")
                .filter { (it.attr("rel") ?: "").startsWith(ACQUISITION_REL) }
                .mapNotNull { acquisitionLink(it, baseUrl, fallbackHref = null) },
        )
    }

    /** Recursive: nested `indirectAcquisition` elements become the [OpdsAcquisitionLink.indirect] chain. */
    private fun acquisitionLink(
        node: XmlElement,
        baseUrl: String,
        fallbackHref: String?,
    ): OpdsAcquisitionLink? {
        val href = node.attr("href")?.let { resolveHref(baseUrl, it) } ?: fallbackHref ?: return null
        return OpdsAcquisitionLink(
            href = href,
            type = node.attr("type"),
            rel = node.attr("rel").orEmpty(),
            indirect = node.childTags("indirectAcquisition")
                .mapNotNull { acquisitionLink(it, baseUrl, fallbackHref = href) },
        )
    }

    /**
     * Feed-level facets. Attribute prefixes (`opds:facetGroup`, `thr:count`) are
     * not resolved — namespace processing is off — so match on the local name.
     */
    private fun facets(nodes: List<XmlElement>, baseUrl: String): List<OpdsFacetGroup> {
        val groups = mutableMapOf<String, MutableList<OpdsFacet>>()
        for (node in nodes) {
            if (node.attr("rel") != FACET_REL) continue
            val href = node.attr("href")?.let { resolveHref(baseUrl, it) } ?: continue
            val facet = OpdsFacet(
                label = node.attr("title")?.trim()?.takeIf { it.isNotBlank() } ?: href,
                href = href,
                // Real catalogs also write "True"; anything else means inactive.
                active = node.localAttr("activeFacet")?.trim()?.equals("true", ignoreCase = true) == true,
                count = node.localAttr("count")?.trim()?.toIntOrNull(),
            )
            groups.getOrPut(node.localAttr("facetGroup").orEmpty()) { mutableListOf() } += facet
        }
        return groups.map { (label, options) -> OpdsFacetGroup(label = label, options = options) }
    }

    /**
     * Feed descriptions are messy: Flibusta wraps the real annotation in an
     * escaped HTML paragraph and sometimes sends only "Формат/Язык/Размер",
     * Gutenberg puts the author into <content>. Keep readable text, drop the rest.
     */
    private fun descriptionOf(node: XmlElement, author: String?): String? {
        val raw = node.child("summary")?.textContent ?: node.child("content")?.textContent
        val cleaned = cleanDescription(raw) ?: return null
        if (author != null && cleaned.equals(author.trim(), ignoreCase = true)) return null
        return cleaned
    }

    /** OPDS 2 descriptions carry the same escaped markup, so both readers share it. */
    internal fun cleanDescription(raw: String?): String? {
        val source = raw?.takeIf { it.isNotBlank() } ?: return null
        // Feed bodies are escaped HTML, sometimes escaped twice.
        var text = source
        repeat(2) { text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&") }
        text = text
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("[ \\t\\u00A0]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
        if (text.isBlank()) return null
        // Catalogues append their own book card to the annotation ("Год издания /
        // Формат / Язык / Размер / Серия"); it is not prose and gets cut.
        val lines = text.lines().map { it.trim() }
        var end = lines.size
        while (end > 0 && (lines[end - 1].isEmpty() || METADATA_LINE.containsMatchIn(lines[end - 1]))) {
            end--
        }
        val kept = lines.subList(0, end).filterNot { isLabelledMetadata(it) }
        // Library collation ("xiv, 276 p. : 22 cm") opens some catalogue blurbs.
        val prose = kept.dropWhile { it.isEmpty() || COLLATION_LINE.containsMatchIn(it) }
        if (prose.isEmpty()) return null
        return prose.joinToString("\n").trim()
    }

    private val METADATA_LINE = Regex(
        "^(Формат|Язык|Размер|ISBN|Серия|Издательство|Год(\\s+издания)?|Страниц|Автор обложки)\\s*:",
        RegexOption.IGNORE_CASE,
    )

    /** Gutenberg and library-card labels, a whole line of them at a time. */
    private val LABEL_LINE = Regex(
        "^(Title|Author|Credits|Summary|Subject|Category|Reading Level|Downloads|" +
            "Ebook No\\.|LoCC|Note|Alternate Title|Publisher|Language)\\s*:",
        RegexOption.IGNORE_CASE,
    )

    /** Library collation at the head of an annotation: "xiv, 276 p. : 22 cm". */
    private val COLLATION_LINE = Regex(
        "^(?:[ivxlcdm\\d][\\divxlcdm,.:;\\s]*(?:\\bp\\.|\\bpages\\b)|\\d+\\s*p\\.)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A labelled line is metadata, unless what follows the colon reads like a
     * sentence ("Note: this edition keeps the original spelling.") or the line
     * is long enough to be prose. Here "sentence" means punctuation, not grammar.
     */
    private fun isLabelledMetadata(line: String): Boolean {
        if (line.length >= LABEL_LINE_LIMIT) return false
        val label = LABEL_LINE.find(line) ?: return false
        val rest = line.substring(label.range.last + 1)
        return rest.none { it == '.' || it == '!' || it == '?' }
    }

    private const val LABEL_LINE_LIMIT = 120

    private fun authorOf(node: XmlElement): String? {
        val named = node.child("author")?.child("name")?.textContent?.trim()
        if (!named.isNullOrBlank()) return named
        return (node.child("creator") ?: node.child("author"))
            ?.textContent?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun link(node: XmlElement, baseUrl: String): OpdsLink? {
        val href = node.attr("href") ?: return null
        val resolved = resolveHref(baseUrl, href) ?: return null
        return OpdsLink(
            rel = node.attr("rel").orEmpty(),
            href = resolved,
            type = node.attr("type"),
            title = node.attr("title"),
        )
    }

    private fun XmlElement.localName(): String = if (':' in name) name.substringAfter(':') else name

    /** Attribute by local name: accepts `opds:facetGroup`, `thr:count` and bare names. */
    private fun XmlElement.localAttr(name: String): String? =
        attr(name) ?: attrs.entries.firstOrNull { (key, _) ->
            if (':' in key) key.substringAfter(':') == name else false
        }?.value

    private fun XmlElement.child(tag: String): XmlElement? =
        children.firstOrNull { it.localName() == tag }

    private fun XmlElement.childTags(tag: String): List<XmlElement> =
        children.filter { it.localName() == tag }
}
