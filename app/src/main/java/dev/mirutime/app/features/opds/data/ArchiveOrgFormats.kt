package dev.mirutime.app.features.opds.data

import dev.mirutime.app.features.opds.domain.OpdsBookOption
import dev.mirutime.core.opds.OpdsFormat
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * archive.org advertises a text item with a `?glob_pattern=*pdf` link even when
 * the item has a public EPUB: its OPDS never mentions the epub. The book
 * endpoint answers `?glob_pattern=*epub` with a redirect envelope, and that is
 * the only place the file shows up. A probe is best effort: its failure just
 * leaves the entry as the catalogue served it.
 */
internal object ArchiveOrgFormats {
    private const val BOOK_PATH = "/services/opds/book/"
    private const val OPEN_ACCESS_REL = "http://opds-spec.org/acquisition/open-access"

    /** Item id of an archive.org book-endpoint link, or null for any other URL. */
    fun isArchiveBookLink(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "archive.org" && host != "www.archive.org") return null
        val path = uri.path.orEmpty()
        if (!path.startsWith(BOOK_PATH)) return null
        if (uri.rawQuery?.contains("glob_pattern=") != true) return null
        return path.removePrefix(BOOK_PATH).trim('/').takeIf { it.isNotBlank() }
    }

    /** The epub behind [itemId], or null when the endpoint has none. */
    suspend fun epubOption(itemId: String, transport: OpdsTransport): OpdsBookOption? {
        val fetch = try {
            transport.get(epubUrl(itemId), emptyMap())
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return null
        }
        val location = fetch.bytes?.let { location(it) } ?: return null
        // Only a real file URL is usable: `"[]"` (no match) and inline markup are not.
        if (!location.startsWith("http://") && !location.startsWith("https://")) return null
        val path = runCatching { URI(location).path }.getOrNull().orEmpty().lowercase()
        if (!path.endsWith(".epub")) return null
        return OpdsBookOption(format = OpdsFormat.EPUB, url = location, rel = OPEN_ACCESS_REL)
    }

    internal fun epubUrl(itemId: String): String =
        "https://archive.org$BOOK_PATH$itemId?glob_pattern=*epub"

    /** `_headers.location` of the redirect envelope, often served with status 200. */
    private fun location(bytes: ByteArray): String? {
        val root = runCatching {
            Json.parseToJsonElement(bytes.decodeToString().removePrefix("\uFEFF").trim())
        }.getOrNull() as? JsonObject ?: return null
        val headers = root["_headers"] as? JsonObject
        return (headers?.get("location") as? JsonPrimitive)?.contentOrNull
            ?: (root["location"] as? JsonPrimitive)?.contentOrNull
    }
}
