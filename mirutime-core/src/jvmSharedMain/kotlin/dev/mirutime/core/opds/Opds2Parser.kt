package dev.mirutime.core.opds

import dev.mirutime.core.api.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * OPDS 2.0 (Readium webpub catalogue) reader. The JSON document is mapped onto
 * the same [OpdsFeed] the Atom reader produces, so navigation, facets, paging,
 * search and the book cards need no JSON of their own.
 *
 * A `groups[]` entry is a browse view of its own: it becomes a section (its
 * title, item count and link), and its publications are flattened into book
 * entries as well. `navigation[]` and `publications[]` map one to one.
 *
 * `properties.availability.state` is a label, not a filter: a publication is
 * listed whatever its state (Readium and Thorium do the same). We expose no
 * state of our own, so nothing is hidden here.
 */
object Opds2Parser {

    private const val CONTEXT = "@context"
    private const val PUBLICATION_TYPE = "application/opds-publication+json"

    fun parse(bytes: ByteArray, baseUrl: String): Outcome<OpdsFeed> {
        val root = runCatching {
            // A served body may still start with a UTF-8 BOM, which the JSON
            // parser (rightly) rejects as trailing garbage.
            Json.parseToJsonElement(bytes.decodeToString().removePrefix("\uFEFF"))
        }.getOrNull()
        if (root !is JsonObject) return Outcome.Err(OpdsError.Malformed)
        val navigation = root.objects("navigation")
        val groups = root.objects("groups")
        val publications = root.objects("publications") +
            groups.flatMap { it.objects("publications") }
        // No catalogue key and no manifest context: this is some other JSON
        // payload served under an OPDS URL, not a feed we can open.
        if (root[CONTEXT] == null && navigation.isEmpty() && publications.isEmpty()) {
            return Outcome.Err(OpdsError.NotAFeed)
        }
        val links = root.objects("links").mapNotNull { link(it, baseUrl) }
        return Outcome.Ok(
            OpdsFeed(
                title = root.obj("metadata")?.string("title").orEmpty(),
                id = null,
                baseUrl = baseUrl,
                links = links,
                entries = navigation.mapNotNull { navigationEntry(it, baseUrl) } +
                    groups.mapNotNull { groupSection(it, baseUrl) } +
                    publications.mapNotNull { publication(it, baseUrl) },
                facets = facets(root, baseUrl),
                searchLink = links.firstOrNull { it.rel == "search" }
                    ?.let { OpdsSearchLink(href = it.href, type = it.type) },
            ),
        )
    }

    /** A navigation item is a link to another feed, shaped like a section entry. */
    private fun navigationEntry(node: JsonObject, baseUrl: String): OpdsEntry? {
        val title = node.obj("metadata")?.string("title") ?: node.string("title")
        if (title.isNullOrBlank()) return null
        val link = link(node, baseUrl) ?: return null
        return OpdsEntry(
            title = title.trim(),
            author = null,
            summary = null,
            id = null,
            coverUrl = null,
            links = listOf(link),
        )
    }

    /**
     * A `groups[]` entry names a browse view of its own in `links[]`: it is a
     * section, with the group's item count as the section's blurb.
     */
    private fun groupSection(node: JsonObject, baseUrl: String): OpdsEntry? {
        val metadata = node.obj("metadata") ?: return null
        val title = metadata.string("title")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val link = node.objects("links")
            .mapNotNull { link(it, baseUrl) }
            .firstOrNull { "image" !in it.rel && it.type?.startsWith("image/") != true }
            ?: return null
        return OpdsEntry(
            title = title,
            author = null,
            summary = metadata.int("numberOfItems")?.toString(),
            id = null,
            coverUrl = null,
            links = listOf(link),
        )
    }

    private fun publication(node: JsonObject, baseUrl: String): OpdsEntry? {
        val metadata = node.obj("metadata") ?: return null
        val title = metadata.string("title")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val links = node.objects("links").mapNotNull { link(it, baseUrl) }
        return OpdsEntry(
            title = title,
            author = author(metadata),
            summary = OpdsParser.cleanDescription(metadata.string("description")),
            id = metadata.string("identifier")?.trim()?.takeIf { it.startsWith("http") },
            coverUrl = cover(node, links, baseUrl),
            links = links,
            categories = categories(metadata),
            acquisitionLinks = node.objects("links")
                .filter { it.string("rel").orEmpty().startsWith(OpdsParser.ACQUISITION_REL) }
                .mapNotNull { acquisition(it, baseUrl) },
            isPublication = true,
        )
    }

    private fun acquisition(node: JsonObject, baseUrl: String): OpdsAcquisitionLink? {
        val href = node.string("href")?.let { OpdsParser.resolveHref(baseUrl, it) } ?: return null
        val type = node.string("type")
        // A Readium publication manifest is a landing document, never a file.
        if (type?.substringBefore(';')?.trim()?.equals(PUBLICATION_TYPE, ignoreCase = true) == true) {
            return null
        }
        return OpdsAcquisitionLink(
            href = href,
            type = type,
            rel = node.string("rel").orEmpty(),
            indirect = node.obj("properties")?.objects("indirectAcquisition")
                .orEmpty()
                .map { indirect -> indirect(link = href, node = indirect) },
        )
    }

    /** OPDS 2 nests the readable format one level down (`properties.indirectAcquisition`). */
    private fun indirect(link: String, node: JsonObject): OpdsAcquisitionLink =
        OpdsAcquisitionLink(
            href = link,
            type = node.string("type"),
            rel = "",
            indirect = node.objects("child").map { indirect(link = link, node = it) },
        )

    /**
     * The catalogue's own artwork: `images[]` first (a thumbnail, then a cover,
     * then whatever came first), then a link that names itself as one. A
     * `data:` URL is inline markup, never something the image loader can fetch.
     */
    private fun cover(node: JsonObject, links: List<OpdsLink>, baseUrl: String): String? {
        val images = node.objects("images")
            .mapNotNull { link(it, baseUrl) }
            .filterNot { it.href.startsWith("data:", ignoreCase = true) }
        val preferred = images.firstOrNull {
            it.rel.substringAfterLast('/') == "thumbnail" || it.type == "image/thumbnail"
        }
            ?: images.firstOrNull {
                it.rel.substringAfterLast('/') == "cover" || it.type == "image"
            }
            ?: images.firstOrNull()
        return preferred?.href
            ?: links.firstOrNull {
                !it.href.startsWith("data:", ignoreCase = true) &&
                    ("image" in it.rel || "thumbnail" in it.rel)
            }?.href
    }

    /** `subject` entries are either plain strings or `{name}` objects. */
    private fun categories(metadata: JsonObject): List<String> {
        val subject = metadata["subject"] ?: return emptyList()
        val values = when (subject) {
            is JsonArray -> subject.mapNotNull { (it as? JsonObject)?.string("name") ?: it.text() }
            else -> listOfNotNull((subject as? JsonObject)?.string("name") ?: subject.text())
        }
        return values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    /** `author` is an array of names, an array of `{name}` objects, or one of either. */
    private fun author(metadata: JsonObject): String? {
        val author = metadata["author"] ?: metadata["creator"] ?: return null
        val name = when (author) {
            is JsonArray -> author.firstNotNullOfOrNull {
                (it as? JsonObject)?.string("name") ?: it.text()
            }
            is JsonObject -> author.string("name")
            else -> author.text()
        }
        return name?.trim()?.takeIf { it.isNotBlank() }
    }

    /** `facets[]` carry no `rel`: every link in a group is one option of it. */
    private fun facets(root: JsonObject, baseUrl: String): List<OpdsFacetGroup> =
        root.objects("facets").mapNotNull { group ->
            val options = group.objects("links").mapNotNull { node ->
                val href = node.string("href")?.let { OpdsParser.resolveHref(baseUrl, it) }
                    ?: return@mapNotNull null
                OpdsFacet(
                    label = node.string("title")?.trim()?.takeIf { it.isNotBlank() } ?: href,
                    href = href,
                    active = false,
                    count = node.obj("properties")?.int("numberOfItems"),
                )
            }
            if (options.isEmpty()) return@mapNotNull null
            OpdsFacetGroup(
                label = group.obj("metadata")?.string("title").orEmpty(),
                options = options,
            )
        }

    private fun link(node: JsonObject, baseUrl: String): OpdsLink? {
        // Search templates keep their `{...}`: resolveHref preserves the braces.
        val href = node.string("href")?.let { OpdsParser.resolveHref(baseUrl, it) } ?: return null
        return OpdsLink(
            rel = node.string("rel").orEmpty(),
            href = href,
            type = node.string("type"),
            title = node.string("title"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
}

/**
 * The one place that decides which OPDS dialect a body speaks: a JSON document
 * is OPDS 2, everything else goes to the Atom reader.
 */
object OpdsFeeds {
    fun parse(bytes: ByteArray, baseUrl: String): Outcome<OpdsFeed> =
        if (OpdsParser.preview(bytes).startsWith("{")) {
            Opds2Parser.parse(bytes, baseUrl)
        } else {
            OpdsParser.parse(bytes, baseUrl)
        }
}

