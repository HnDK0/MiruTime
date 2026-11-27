package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.translation.data.ChapterTranslationConfig
import dev.mirutime.app.features.translation.data.ChapterTranslationPlanner
import dev.mirutime.app.features.translation.data.TranslationMode
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.core.local.exportStreaming
import dev.mirutime.core.log.MiruLog
import dev.mirutime.core.reader.html.ReaderHtmlParser
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleTarget
import dev.mirutime.core.text.applyRulesFor
import dev.mirutime.core.text.normalizeAngles
import dev.mirutime.translator.PromptSpec
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.Locale
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
 * Only chapters the library holds locally (the `downloaded` chapter flag, which
 * covers downloaded and reader-cached bodies) are written; the source is never
 * asked for a chapter. The tool badge counts the same chapters, so the export is
 * offline, matches what the screen promises and has a real progress total.
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
    private val rulesFor: (bookUrl: String) -> List<RegexRule> = { emptyList() },
) {
    /**
     * [title] is the display name the caller chose (it carries the language and
     * "Original"/"Translated" tag exactly like the file name), [language] the
     * BCP47 code for dc:language. Both fall back to the book's own metadata so
     * a library without them still exports valid EPUB metadata.
     */
    suspend fun export(
        bookUrl: String,
        includeTranslation: Boolean,
        outputStream: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        title: String? = null,
        language: String? = null,
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

        val sourceId = sourceIdFor(bookUrl)
        // Only chapters the library already holds locally (downloaded, or cached
        // by the reader) are exported. The source is never asked for a chapter:
        // a fallback fetch turned export into a silent full-book download with
        // no progress, and the tile badge counts the same way.
        val chaptersToWrite = chapters.filter { it.downloaded }
        if (chaptersToWrite.isEmpty()) return@withContext EpubExportResult.NoChapters

        // Remote artwork is best-effort: an unreachable image host must not turn
        // a local export into hours of sequential 10s timeouts. Past the budget
        // the original src is kept, exactly like a failed download.
        val remoteImageDeadline = System.currentTimeMillis() + REMOTE_IMAGE_BUDGET_MS

        // Cleanup rules are applied per chapter here: to the chapter title and
        // to the paragraph text the core writer has already stripped and
        // entity-decoded, which is the same input the reader rules see.
        val rules = rulesFor(bookUrl)
        val rulesActive = rules.any { it.enabled && it.pattern.isNotBlank() }
        val originalTransform: (String) -> String = if (rulesActive) {
            // Same normalization the reader's parser applies before the rules:
            // angles folded, then space runs collapsed and trimmed per line.
            { text ->
                applyRulesFor(
                    rules,
                    normalizeAngles(text)
                        .lines()
                        .joinToString("\n") { SpaceRuns.replace(it, " ").trim() }
                        .trim(),
                    RegexRuleTarget.Original,
                )
            }
        } else {
            { text -> text }
        }

        var written = 0
        var missing = 0
        val chapterLoader: suspend (Int, Int) -> List<Pair<String, String>> = { offset, count ->
            val end = minOf(offset + count, chaptersToWrite.size)
            if (offset >= end) {
                emptyList()
            } else {
                chaptersToWrite.subList(offset, end).mapNotNull { chapter ->
                    val sourceText = try {
                        chapterBodyLoader(bookUrl, chapter.index)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    // A stale "downloaded" flag (file deleted) is skipped, never
                    // fetched: export stays offline.
                    if (sourceText.isNullOrBlank()) {
                        missing++
                        return@mapNotNull null
                    }
                    val chapterUrl = chapter.url ?: "$bookUrl#chapter=${chapter.index}"
                    val sourceHtml = resolveImageSources(sourceText, chapterUrl)
                    val html = if (includeTranslation) {
                        val translations = try {
                            // The resolver keys the cache off the ORIGINAL html:
                            // ReaderScreen stores sourceHash from this exact text,
                            // not from the image-resolved copy. The planner
                            // applies the Original rules itself.
                            translationsFor(bookUrl, chapterUrl, sourceText)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        // A chapter without a cached translation still gets the
                        // Original-stage cleanup: the fallback branch inside
                        // translatedChapterHtml applies it.
                        translatedChapterHtml(sourceText, chapterUrl, translations.orEmpty(), rules)
                    } else {
                        sourceHtml
                    }
                    val title = applyRulesFor(rules, chapter.title, RegexRuleTarget.Original)
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
                bookTitle = title?.trim()?.takeIf { it.isNotEmpty() }
                    ?: details.title.ifBlank { "book" },
                // dc:language must be a BCP47 code ("en"), never a display name:
                // normalize whatever the caller or the source metadata says.
                language = (languageTag(language) ?: languageTag(details.originLanguage))
                    ?.lowercase(Locale.US)
                    ?: "und",
                totalChapters = chaptersToWrite.size,
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
                                if (System.currentTimeMillis() > remoteImageDeadline) {
                                    null
                                } else {
                                    fetchImage(sourceId, src)
                                        ?.takeIf { it.size <= MAX_EXPORT_IMAGE_BYTES }
                                }
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
                // Every block is already rule-clean in translated mode (and
                // untranslated chapters are cleaned there too): the core
                // transform stays identity so rules never hit translated text.
                textTransform = if (includeTranslation) {
                    { text -> text }
                } else {
                    originalTransform
                },
            )
            if (missing > 0) {
                MiruLog.event(
                    "book",
                    "epub-skip",
                    "book" to MiruLog.idHash(bookUrl),
                    "missing" to missing,
                    "written" to written,
                )
            }
            EpubExportResult.Success(written)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // The core writer refuses a book with nothing written (stale
            // "downloaded" rows). Only that case is "nothing local to export";
            // any other argument failure stays a visible error.
            if (written > 0) {
                return@withContext EpubExportResult.Failure(e.message ?: "EPUB export failed")
            }
            MiruLog.event(
                "book",
                "epub-empty",
                "book" to MiruLog.idHash(bookUrl),
                "chapters" to chaptersToWrite.size,
                "missing" to missing,
            )
            EpubExportResult.NoChapters
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
    rules: List<RegexRule> = emptyList(),
): String {
    if (html.isBlank()) return html
    // Nothing to swap and nothing to clean: keep the source bytes as they are.
    if (translations.isEmpty() && rules.none { it.enabled && it.pattern.isNotBlank() }) return html
    // Text is parsed as-is (parseDocument cleans it exactly like the reader);
    // only relative image srcs are resolved up front, because the parser's own
    // resolveImageUrl handles http(s) chapter URLs only.
    val blocks = ReaderHtmlParser.parseDocument(resolveImageSources(html, chapterUrl), chapterUrl)
    if (blocks.isEmpty()) return html
    val body = buildString {
        for (block in blocks) {
            val translated = translations[block.id.index]?.takeIf { it.isNotBlank() }
            // parseDocument hands out cleaned text, so the Original rules run
            // on the same input as in the reader; the translated side gets only
            // Translation-stage rules.
            val text = if (translated != null) {
                applyRulesFor(rules, translated, RegexRuleTarget.Translation)
            } else {
                applyRulesFor(rules, block.original, RegexRuleTarget.Original)
            }
            when (block.kind) {
                BlockKind.Heading ->
                    append("<h2>").append(htmlEscape(text)).append("</h2>")
                BlockKind.Paragraph ->
                    append("<p>").append(htmlEscape(text)).append("</p>")
                BlockKind.Image -> block.imageRef?.takeIf { it.isNotBlank() }?.let { src ->
                    val alt = applyRulesFor(rules, block.original, RegexRuleTarget.Original)
                    append("<p><img src=\"").append(htmlEscape(src))
                        .append("\" alt=\"").append(htmlEscape(alt)).append("\"/></p>")
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

// Same whitespace run the reader's parser collapses inside a block, so rules
// match the reader's text and not the raw markup spacing.
private val SpaceRuns = Regex("[ \\t\\u00A0]+")

// Whole-export budget for remote chapter images (not per image).
private const val REMOTE_IMAGE_BUDGET_MS = 60_000L

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

/**
 * Human-safe EPUB file name: "<title>[<LANG> Translated|Original].epub" with
 * path/reserved chars cut. The language tag keeps the original and the
 * translated export of one book apart instead of overwriting each other.
 */
internal fun epubFileName(
    title: String,
    languageCode: String? = null,
    translated: Boolean = false,
): String {
    val base = title.trim()
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(120)
        .ifBlank { "book" }
    return "$base${epubLanguageTag(languageCode, translated)}.epub"
}

/**
 * Display title carried into the EPUB metadata, so a local reader shows
 * "Shadow Slave[RU Translated]" instead of a bare title that hides whether the
 * file is the original or a translation.
 */
internal fun epubDisplayTitle(
    title: String,
    languageCode: String? = null,
    translated: Boolean = false,
): String = title.trim().ifBlank { "book" } + epubLanguageTag(languageCode, translated)

private fun epubLanguageTag(languageCode: String?, translated: Boolean): String =
    languageCode?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { "[${it.uppercase(Locale.US)} ${if (translated) "Translated" else "Original"}]" }
        .orEmpty()

/**
 * ISO code for the language a book or a translation mode reports. Plugins send
 * either a code ("en", "eng") or a display name ("English", "Korean"); an
 * unknown value yields null and the file name just omits the tag.
 */
internal fun languageTag(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (raw.length in 2..3 && raw.all { it in 'a'..'z' || it in 'A'..'Z' }) {
        return raw.uppercase(Locale.US)
    }
    // shortcut: reverse lookup over the JDK's ISO table, enough for the display
    // names plugins send; extend with an explicit map if a source returns
    // regional names ("Brazilian Portuguese").
    return IsoLanguageCodes[raw.lowercase(Locale.US)]
}

private val IsoLanguageCodes: Map<String, String> by lazy {
    Locale.getISOLanguages().associate { code ->
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.US) to
            code.uppercase(Locale.US)
    }
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
