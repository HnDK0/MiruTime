package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.translation.data.ChapterTranslationConfig
import dev.mirutime.app.features.translation.data.ChapterTranslationPlanner
import dev.mirutime.app.features.translation.data.TranslationMode
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.core.local.exportStreaming
import dev.mirutime.core.reader.html.ReaderHtmlParser
import dev.mirutime.translator.PromptSpec
import java.io.File
import java.io.OutputStream
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of one EPUB export; [Failure.message] is diagnostic only. */
sealed interface EpubExportResult {
    data class Success(val chaptersWritten: Int) : EpubExportResult
    data object NoChapters : EpubExportResult
    data class Failure(val message: String) : EpubExportResult
}

/**
 * Writes a book through the core streaming EPUB writer.
 *
 * Dependencies stay lambdas so the exporter is testable without Room, the
 * plugin runtime or the reader graph:
 * - [coverLoader] reads the local cover cached by BookCoverStore;
 * - [fetchImage] fetches remote artwork with the plugin's sourceId/headers;
 * - [translationsFor] resolves already-cached translations for a chapter
 *   (reader L1 state or Room L2 cache); no translation is ever started here.
 */
class BookEpubExporter(
    private val provider: BookDataProvider,
    private val chapterBodyLoader: suspend (bookUrl: String, chapterIndex: Int) -> String? =
        { _, _ -> null },
    private val coverLoader: suspend (bookUrl: String) -> ByteArray? = { null },
    private val fetchImage: suspend (sourceId: String, url: String) -> ByteArray? = { _, _ -> null },
    private val translationsFor:
        suspend (bookUrl: String, chapterUrl: String, html: String) -> Map<Int, String>? =
        { _, _, _ -> null },
    private val sourceIdFor: (bookUrl: String) -> String = { "" },
) {
    suspend fun export(
        bookUrl: String,
        includeTranslation: Boolean,
        outputStream: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): EpubExportResult = withContext(Dispatchers.IO) {
        val details = try {
            provider.book(bookUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext EpubExportResult.Failure(e.message ?: "Failed to load book details")
        } ?: return@withContext EpubExportResult.Failure("Book details are unavailable")

        val chapters = try {
            provider.chapters(bookUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext EpubExportResult.Failure(e.message ?: "Failed to load chapters")
        }
        if (chapters.isEmpty()) return@withContext EpubExportResult.NoChapters

        val sourceId = sourceIdFor(bookUrl)
        var written = 0
        val chapterLoader: suspend (Int, Int) -> List<Pair<String, String>> = { offset, count ->
            val end = minOf(offset + count, chapters.size)
            if (offset >= end) {
                emptyList()
            } else {
                chapters.subList(offset, end).mapNotNull { chapter ->
                    val bodyHtml = try {
                        chapterBodyLoader(bookUrl, chapter.index)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val content = if (bodyHtml == null) {
                        try {
                            provider.chapterContent(bookUrl, chapter.index)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    // Persisted DB bodies win; otherwise use the provider.
                    val sourceText = bodyHtml ?: content?.html ?: return@mapNotNull null
                    val chapterUrl = content?.chapterUrl
                        ?: chapter.url
                        ?: "$bookUrl#chapter=${chapter.index}"
                    val sourceHtml = resolveImageSources(sourceText, chapterUrl)
                    val html = if (includeTranslation) {
                        val translations = try {
                            // The resolver keys the cache off the ORIGINAL html:
                            // ReaderScreen stores sourceHash from this exact text,
                            // not from the image-resolved copy used for output.
                            translationsFor(bookUrl, chapterUrl, sourceText)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        if (translations.isNullOrEmpty()) {
                            sourceHtml
                        } else {
                            translatedChapterHtml(sourceHtml, chapterUrl, translations)
                        }
                    } else {
                        sourceHtml
                    }
                    val title = (content?.title?.ifBlank { chapter.title } ?: chapter.title)
                        .ifBlank { "Chapter ${chapter.index}" }
                    title to html
                }
            }
        }

        val cover = try {
            coverLoader(bookUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        try {
            exportStreaming(
                outputStream = outputStream,
                bookTitle = details.title.ifBlank { "book" },
                language = details.originLanguage?.trim()?.takeIf { it.isNotEmpty() } ?: "und",
                totalChapters = chapters.size,
                chapterLoader = chapterLoader,
                coverBytes = cover,
                description = details.description,
                creator = details.author,
                subjects = details.genres,
                imageByteLoader = { src ->
                    try {
                        when {
                            src.startsWith("file:") -> readFileUrlBytes(src)
                            src.startsWith("http://") || src.startsWith("https://") ->
                                fetchImage(sourceId, src)
                                    ?.takeIf { it.size <= MAX_EXPORT_IMAGE_BYTES }
                            else -> null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                },
                onProgress = { done, total ->
                    written = done
                    onProgress(done, total)
                },
            )
            EpubExportResult.Success(written)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EpubExportResult.Failure(e.message ?: "EPUB export failed")
        }
    }
}

/**
 * Swaps text blocks for ready translations while preserving every image block
 * in its original position. Block indices are exactly the ones the reader's
 * TranslationController uses (ReaderHtmlParser.parseDocument), so only cached
 * results for the current chapter apply.
 */
internal fun translatedChapterHtml(
    html: String,
    chapterUrl: String,
    translations: Map<Int, String>,
): String {
    if (translations.isEmpty() || html.isBlank()) return html
    val blocks = ReaderHtmlParser.parseDocument(html, chapterUrl)
    if (blocks.isEmpty()) return html
    val body = buildString {
        for (block in blocks) {
            val translated = translations[block.id.index]?.takeIf { it.isNotBlank() }
            when (block.kind) {
                BlockKind.Heading ->
                    append("<h2>").append(htmlEscape(translated ?: block.original)).append("</h2>")
                BlockKind.Paragraph ->
                    append("<p>").append(htmlEscape(translated ?: block.original)).append("</p>")
                BlockKind.Image -> block.imageRef?.takeIf { it.isNotBlank() }?.let { src ->
                    append("<p><img src=\"").append(htmlEscape(src))
                        .append("\" alt=\"").append(htmlEscape(block.original)).append("\"/></p>")
                }
                BlockKind.Divider -> Unit
            }
        }
    }
    return body.ifBlank { html }
}

/**
 * Builds the exact TranslationRequest the reader saves to Room, from the
 * chapter HTML alone: same block parsing, sourceHash (document.blocks.hashCode)
 * and pipelineVersion "reader-1". Passing this request to RoomTranslationCache
 * only ever reads COMPLETE rows; it never submits a translation job.
 *
 * Returns null when translation is off/misconfigured or the chapter has no
 * translatable text blocks.
 */
internal fun cachedTranslationRequest(
    bookUrl: String,
    chapterUrl: String,
    html: String,
    mode: TranslationMode,
    prompt: PromptSpec?,
): TranslationRequest? = ChapterTranslationPlanner.requestFromHtml(
    bookUrl = bookUrl,
    chapterUrl = chapterUrl,
    html = html,
    config = ChapterTranslationConfig(mode = mode, prompt = prompt),
)

/** Reads a file:// image; anything else is left to the remote loader. */
internal fun readFileUrlBytes(src: String): ByteArray? {
    if (!src.startsWith("file:")) return null
    return runCatching {
        val file = File(URI(src))
        if (file.isFile && file.length() in 1..MAX_EXPORT_IMAGE_BYTES) file.readBytes() else null
    }.getOrNull()
}

private const val MAX_EXPORT_IMAGE_BYTES = 32L * 1024 * 1024

private val ImageSrcTag = Regex(
    """(?i)(<img\b[^>]*?\bsrc\s*=\s*["'])([^"']*)(["'])""",
)

/**
 * Resolves relative <img src> values against the chapter URL before the core
 * exporter sees them. The reader parser already does this for translated
 * blocks; the original-HTML export path needs the same treatment.
 */
internal fun resolveImageSources(html: String, chapterUrl: String): String {
    if (html.isBlank() || chapterUrl.isBlank()) return html
    return ImageSrcTag.replace(html) { match ->
        val raw = match.groupValues[2].trim()
        val resolved = when {
            raw.isBlank() -> raw
            raw.startsWith("http://") || raw.startsWith("https://") ||
                raw.startsWith("data:") || raw.startsWith("file:") ||
                raw.startsWith("content:") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> runCatching { URI(chapterUrl).resolve(raw).toString() }.getOrDefault(raw)
        }
        match.groupValues[1] + resolved + match.groupValues[3]
    }
}

/** Human-safe EPUB file name: "<title>.epub" with path/reserved chars cut. */
internal fun epubFileName(title: String): String {
    val base = title.trim()
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(120)
        .ifBlank { "book" }
    return "$base.epub"
}

private fun htmlEscape(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
