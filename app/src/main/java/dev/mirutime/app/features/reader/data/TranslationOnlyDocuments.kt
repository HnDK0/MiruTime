package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.SentenceSplitter
import dev.mirutime.core.log.MiruLog
import dev.mirutime.core.text.RegexRuleTarget
import dev.mirutime.core.text.applyRulesFor
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterTranslationEntity
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.TranslationBlockEntity
import dev.mirutime.feature.reader.api.ReaderTextRules
import dev.mirutime.translator.LanguageCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a readable document from cached translated blocks when the original
 * chapter cannot be loaded. Each stored block becomes a paragraph whose
 * "original" is the translated text, so selection, search and TTS have
 * something to point at.
 */
internal fun translationOnlyDocument(
    bookUrl: String,
    chapterIndex: Int,
    chapterUrl: String,
    title: String,
    blocks: List<TranslationBlockEntity>,
): ReaderDocument? {
    val readable = blocks
        .filter { it.translatedText.isNotBlank() }
        .sortedBy { it.blockIndex }
    if (readable.isEmpty()) return null
    return ReaderDocument(
        bookUrl = bookUrl,
        chapterUrl = chapterUrl,
        chapterIndex = chapterIndex,
        title = title,
        blocks = readable.map { block ->
            ReaderBlock(
                id = BlockId(chapterUrl, block.blockIndex),
                kind = BlockKind.Paragraph,
                original = block.translatedText,
                sentences = SentenceSplitter.spans(block.translatedText),
            )
        },
        translationOnly = true,
    )
}

/** Translation-stage cleanup applied before a synthetic document is built. */
internal fun cleanTranslationOnlyBlocks(
    bookUrl: String,
    blocks: List<TranslationBlockEntity>,
): List<TranslationBlockEntity> {
    if (blocks.isEmpty()) return blocks
    val cleaned = cleanTranslatedBlocks(
        bookUrl,
        blocks.associate { it.blockIndex to it.translatedText },
    )
    return blocks.map { block ->
        block.copy(translatedText = cleaned[block.blockIndex] ?: block.translatedText)
    }
}

/** Same value the planner stores, so a cached query matches exactly. */
internal fun normalizedPair(source: String, target: String): Pair<String, String> =
    normalized(source) to normalized(target)

private fun normalized(value: String): String =
    runCatching { LanguageCode.parse(value).value }.getOrElse { "" }

internal class TranslationOnlyDocuments(
    private val bookById: suspend (Long) -> BookEntity?,
    private val bookIdByUrl: suspend (String) -> Long?,
    private val chapterByIndex: suspend (Long, Int) -> ChapterEntity?,
    private val latest: suspend (Long, String, String, String) -> ChapterTranslationEntity?,
    private val blocks: suspend (Long) -> List<TranslationBlockEntity>,
    private val clean: (String, List<TranslationBlockEntity>) -> List<TranslationBlockEntity> =
        ::cleanTranslationOnlyBlocks,
    private val textRules: ReaderTextRules = AppReaderTextRules(),
) {
    constructor(library: LibraryRepository) : this(
        bookById = { library.book(it) },
        bookIdByUrl = library::bookIdByUrlUnique,
        chapterByIndex = library::chapterByIndex,
        latest = library::latestCompleteChapterTranslation,
        blocks = library::translationBlocks,
    )

    suspend fun load(
        bookUrl: String,
        chapterIndex: Int,
        source: String,
        target: String,
        provider: String,
    ): ReaderDocument? {
        val (sourceCode, targetCode) = normalizedPair(source, target)
        val bookId = bookIdByUrl(bookUrl) ?: return null
        val book = bookById(bookId) ?: return null
        if (book.contentType.equals(ContentType.Manga.name, ignoreCase = true) ||
            book.contentType.equals(ContentType.Video.name, ignoreCase = true)
        ) {
            return null
        }
        val chapter = chapterByIndex(bookId, chapterIndex) ?: return null
        val row = latest(chapter.chapterId, sourceCode, targetCode, provider) ?: return null
        val rawBlocks = blocks(row.translationId)
        if (rawBlocks.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            val cleanedBlocks = clean(bookUrl, rawBlocks)
            val title = applyRulesFor(
                textRules.effectiveRules(bookUrl),
                chapter.title,
                RegexRuleTarget.Original,
            )
            val document = translationOnlyDocument(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                chapterUrl = chapter.url,
                title = title,
                blocks = cleanedBlocks,
            ) ?: return@withContext null
            val span = rawBlocks.maxOf { it.blockIndex } - rawBlocks.minOf { it.blockIndex } + 1
            if (rawBlocks.size < span) {
                MiruLog.w(
                    "translation",
                    "translation-only sparse chapterIndex=$chapterIndex " +
                        "blocks=${document.blocks.size} raw=${rawBlocks.size} span=$span",
                )
            } else {
                MiruLog.d(
                    "translation",
                    "translation-only chapterIndex=$chapterIndex blocks=${document.blocks.size}",
                )
            }
            // Row write time; only a pagination cache key, not a source revision.
            document.copy(revision = row.updatedAt)
        }
    }
}
