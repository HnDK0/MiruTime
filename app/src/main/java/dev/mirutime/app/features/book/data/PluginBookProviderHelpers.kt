package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.finder.data.isLocalFileBook
import dev.mirutime.app.features.finder.data.resolveCoverUrl
import dev.mirutime.app.features.library.data.BookCoverStore
import dev.mirutime.app.features.library.data.LocalBookProvider
import dev.mirutime.app.features.library.data.resolveStoredCover
import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.reader.ChapterSource
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterStatusSnapshot
import dev.mirutime.data.library.bookKeyUrl
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

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

internal fun shortHash(value: String): String =
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

internal fun hostOf(url: String): String =
    runCatching { URI(url).host.orEmpty() }.getOrNull().orEmpty()

// shortcut: 1s TTL, replace with a status Flow if downloads need push updates.
internal const val ChapterStatusTtlMs = 1_000L

// A repaired cover is re-resolved at most once per this window per book: a
// dead hitomi token must not turn a grid of failed covers into a request storm.
internal const val COVER_REFRESH_COOLDOWN_MS = 10 * 60 * 1000L

internal data class CachedChapterStatus(
    val snapshot: ChapterStatusSnapshot?,
    val generation: Long,
    val at: Long,
)

// A route passes an opaque (installId + URL) key; the database stores the real
// URL only. Kept top-level and pure so the row builder is unit-testable.
internal fun persistedBookUrl(bookKey: String): String = bookKeyUrl(bookKey)

internal fun normalizeTag(raw: String): String =
    raw.trim().lowercase().replace(Regex("\\s+"), " ")

internal fun extraTags(extras: Map<String, String>): List<String> =
    extras.entries
        .filter { it.key.lowercase() in setOf("tags", "tag", "warnings", "warning") }
        .flatMap { it.value.split(',', ';', '\n') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }

internal fun extraAltTitles(extras: Map<String, String>): String {
    val values = listOfNotNull(
        extras["altTitle"] ?: extras["altTitles"],
        extras["alternativeTitle"],
    ).flatMap { it.split('|', '\n') }.map { it.trim() }.filter { it.isNotEmpty() }
    return buildJsonArray { values.forEach { add(it) } }.toString()
}

internal fun extraAuthors(author: String?): String =
    buildJsonArray {
        author?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.toString()

// Pure-JVM author JSON for the direct row (no Android JSONObject stub in tests).
internal fun jsonAuthors(author: String): String {
    val value = author.trim()
    if (value.isEmpty()) return "[]"
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "[\"$escaped\"]"
}

internal fun normalizeStatus(raw: String?): String {
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

internal fun metadataHash(meta: BookMeta): String {
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

