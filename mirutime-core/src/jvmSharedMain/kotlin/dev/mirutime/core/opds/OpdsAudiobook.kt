package dev.mirutime.core.opds

import dev.mirutime.core.api.Outcome
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One playable track of a remote audiobook. */
data class OpdsAudioTrack(
    val href: String,
    val title: String,
    /** Seconds; null when the manifest omitted or mangled it. */
    val durationSeconds: Double?,
)

/** A Readium / archive.org audiobook manifest. */
data class OpdsAudiobook(
    val title: String,
    val authors: List<String>,
    /** Total runtime in seconds; null when absent. */
    val durationSeconds: Double?,
    val coverUrl: String?,
    val tracks: List<OpdsAudioTrack>,
)

/**
 * Reader for an OPDS audiobook manifest (`application/audiobook+json`, served
 * as `application/json`). Catalogues are inconsistent: durations come as
 * `"29:09"`, `"1:02:03"`, a number or not at all, and a `readingOrder` entry
 * may lack its media type. A malformed track is skipped; a body with no
 * playable track is an error, never an empty book.
 */
object OpdsAudiobookParser {
    fun parse(bytes: ByteArray, baseUrl: String = ""): Outcome<OpdsAudiobook> {
        val root = runCatching {
            Json.parseToJsonElement(bytes.decodeToString().removePrefix("\uFEFF"))
        }.getOrNull()
        if (root !is JsonObject) return Outcome.Err(OpdsError.Malformed)
        val metadata = root["metadata"] as? JsonObject
        val tracks = root.array("readingOrder").mapNotNull { node ->
            (node as? JsonObject)?.let { track(it, baseUrl) }
        }.distinctBy { it.href }
        if (tracks.isEmpty()) return Outcome.Err(OpdsError.NoTracks)
        return Outcome.Ok(
            OpdsAudiobook(
                title = metadata?.string("title").orEmpty(),
                authors = authors(metadata),
                durationSeconds = metadata?.seconds("duration"),
                coverUrl = cover(root, baseUrl),
                tracks = tracks,
            ),
        )
    }

    private fun track(node: JsonObject, baseUrl: String): OpdsAudioTrack? {
        val href = node.string("href")?.let { resolve(baseUrl, it) } ?: return null
        if (!href.startsWith("http://") && !href.startsWith("https://")) return null
        val type = node.string("type")?.substringBefore(';')?.trim()?.lowercase()
        if (type == null || !type.startsWith("audio/")) return null
        return OpdsAudioTrack(
            href = href,
            title = node.string("title")?.trim()?.takeIf { it.isNotBlank() }
                ?: fileNameTitle(href),
            durationSeconds = node.seconds("duration"),
        )
    }

    private fun authors(metadata: JsonObject?): List<String> {
        when (val raw = metadata?.get("author")) {
            is JsonPrimitive -> {
                val name = raw.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                return listOfNotNull(name)
            }
            is JsonObject -> {
                val name = raw.string("name")?.trim()?.takeIf { it.isNotBlank() }
                return listOfNotNull(name)
            }
            else -> Unit
        }
        val nodes = metadata?.get("author") as? JsonArray ?: return emptyList()
        return nodes.mapNotNull { node ->
            when (node) {
                is JsonPrimitive -> node.contentOrNull
                is JsonObject -> node.string("name")
                else -> null
            }?.trim()?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    private fun cover(root: JsonObject, baseUrl: String): String? {
        val links = root.array("links") + root.array("images")
        return links
            .filterIsInstance<JsonObject>()
            .mapNotNull { node ->
                val href = node.string("href") ?: return@mapNotNull null
                val rel = node.string("rel").orEmpty()
                val type = node.string("type").orEmpty()
                val isCover = rel.substringAfterLast('/') == "cover" ||
                    (rel.isBlank() && type.startsWith("image/"))
                if (!isCover) return@mapNotNull null
                resolve(baseUrl, href)
            }
            .firstOrNull { !it.startsWith("data:", ignoreCase = true) }
    }

    private fun resolve(baseUrl: String, href: String): String =
        if (baseUrl.isBlank()) href.trim() else OpdsParser.resolveHref(baseUrl, href) ?: href.trim()

    /** `viedesabeilles_01_maeterlinck_64kb.mp3` -> `viedesabeilles 01 maeterlinck 64kb`. */
    private fun fileNameTitle(href: String): String {
        val path = href.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val stem = path.substringBeforeLast('.', path)
        val decoded = runCatching { URLDecoder.decode(stem, "UTF-8") }.getOrDefault(stem)
        return decoded.replace('_', ' ').replace('-', ' ').trim().ifBlank { path }
    }

    /** `"29:09"` / `"1:02:03"` / `1749` / `1749.5` in seconds; null otherwise. */
    private fun JsonObject.seconds(key: String): Double? {
        val node = this[key] as? JsonPrimitive ?: return null
        node.doubleOrNull?.let { return it.takeIf { value -> value >= 0 } }
        val raw = node.contentOrNull?.trim() ?: return null
        if (raw.isEmpty()) return null
        val parts = raw.split(':')
        if (parts.size > 3) return null
        var total = 0.0
        for (part in parts) {
            val value = part.trim().toDoubleOrNull() ?: return null
            if (value < 0) return null
            total = total * 60 + value
        }
        return total
    }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
