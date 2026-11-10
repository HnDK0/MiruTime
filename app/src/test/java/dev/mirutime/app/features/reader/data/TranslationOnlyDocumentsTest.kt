package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterTranslationEntity
import dev.mirutime.data.library.TranslationBlockEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationOnlyDocumentsTest {

    @Test
    fun `cached blocks become paragraph blocks in index order without an original`() {
        val document = translationOnlyDocument(
            bookUrl = "book",
            chapterIndex = 3,
            chapterUrl = "book#chapter=3",
            title = "Глава 3",
            blocks = listOf(block(2, "Third"), block(0, "First"), block(1, " ")),
        )

        requireNotNull(document)
        assertTrue(document.translationOnly)
        assertEquals(listOf(0, 2), document.blocks.map { it.id.index })
        assertEquals(listOf("First", "Third"), document.blocks.map { it.original })
        assertTrue(document.blocks.all { it.kind == BlockKind.Paragraph })
        assertTrue(document.blocks.all { it.sentences.isNotEmpty() })
    }

    @Test
    fun `blank-only cached blocks produce no document`() {
        assertNull(
            translationOnlyDocument(
                bookUrl = "book",
                chapterIndex = 1,
                chapterUrl = "book#chapter=1",
                title = "One",
                blocks = listOf(block(0, " "), block(1, "")),
            ),
        )
    }

    @Test
    fun `language codes keep the region and normalize case`() {
        assertEquals("en" to "ru-RU", normalizedPair("EN", "ru-RU"))
        assertEquals("en" to "ru", normalizedPair("en", "RU"))
    }

    @Test
    fun `cached blocks apply translation rules only`() = runTest {
        withRules(rule(RegexRuleScope.Translation, "AD"), rule(RegexRuleScope.Original, "TITLE")) {
            val cleaned = cleanTranslationOnlyBlocks("book", listOf(block(0, "AD TITLE")))
            assertEquals(" TITLE", cleaned.single().translatedText)
        }
    }

    @Test
    fun `load normalizes the query, cleans blocks, titles and revisions the document`() = runTest {
        withRules(rule(RegexRuleScope.Translation, "AD"), rule(RegexRuleScope.Original, "TITLE")) {
            var seenSource: String? = null
            var seenTarget: String? = null
            val documents = TranslationOnlyDocuments(
                bookById = { testBook() },
                bookIdByUrl = { 1L },
                chapterByIndex = { _, _ -> testChapter(7L, idx = 1, title = "TITLE title") },
                latest = { chapterId, source, target, provider ->
                    assertEquals(7L, chapterId)
                    seenSource = source
                    seenTarget = target
                    ChapterTranslationEntity(
                        chapterId = chapterId,
                        source = source,
                        target = target,
                        provider = provider,
                        model = null,
                        promptHash = "p",
                        sourceHash = "s",
                        pipelineVersion = "v",
                        status = "COMPLETE",
                        updatedAt = 42L,
                    )
                },
                blocks = { listOf(block(0, "AD TITLE")) },
            )

            val document = documents.load("book", 1, "EN", "ru-RU", "GEMINI")

            assertEquals("en", seenSource)
            assertEquals("ru-RU", seenTarget)
            assertEquals(" title", document?.title)
            assertEquals(" TITLE", document?.blocks?.single()?.original)
            assertEquals(42L, document?.revision)
        }
    }

    @Test
    fun `load returns null for a manga book`() = runTest {
        assertNull(
            documents(book = testBook(contentType = "Manga")).load("book", 1, "en", "ru", "GEMINI"),
        )
    }

    @Test
    fun `load returns null without a cached row`() = runTest {
        assertNull(documents(row = null).load("book", 1, "en", "ru", "GEMINI"))
    }

    @Test
    fun `load returns null for blank blocks`() = runTest {
        assertNull(
            documents(blocks = listOf(block(0, " "))).load("book", 1, "en", "ru", "GEMINI"),
        )
    }

    private fun documents(
        book: BookEntity = testBook(),
        chapter: ChapterEntity = testChapter(7L, idx = 1, title = "One"),
        row: ChapterTranslationEntity? = translationRow(),
        blocks: List<TranslationBlockEntity> = listOf(block(0, "text")),
    ) = TranslationOnlyDocuments(
        bookById = { book },
        bookIdByUrl = { 1L },
        chapterByIndex = { _, _ -> chapter },
        latest = { _, _, _, _ -> row },
        blocks = { blocks },
    )

    private fun translationRow() = ChapterTranslationEntity(
        chapterId = 7L,
        source = "en",
        target = "ru",
        provider = "GEMINI",
        model = null,
        promptHash = "p",
        sourceHash = "s",
        pipelineVersion = "v",
        status = "COMPLETE",
        updatedAt = 42L,
    )

    private fun testBook(contentType: String = "Novel") = BookEntity(
        bookId = 1L,
        installId = "test",
        url = "https://x.test/book",
        title = "Test",
        author = "Author",
        coverUrl = null,
        contentType = contentType,
        rating = 0.0,
        shelfId = "reading",
        totalChapters = 1,
        inLibrary = true,
        addedAt = 1L,
        updatedAt = 1L,
        lastReadAt = null,
        lastChapterId = null,
        lastChapterIndex = null,
        chapterListHash = null,
        lastUpdateAt = null,
        genresJson = "[]",
    )

    private fun testChapter(chapterId: Long, idx: Int, title: String) = ChapterEntity(
        chapterId = chapterId,
        bookId = 1L,
        idx = idx,
        url = "https://x.test/book/$idx",
        title = title,
        updatedAt = 1L,
    )

    private suspend fun withRules(vararg rules: RegexRule, block: suspend () -> Unit) {
        val previous = AppSettings.globalRegexRules.toList()
        try {
            AppSettings.replaceGlobalRules(rules.toList())
            block()
        } finally {
            AppSettings.replaceGlobalRules(previous)
        }
    }

    private fun rule(scope: RegexRuleScope, pattern: String) = RegexRule(
        id = pattern.hashCode().toLong(),
        pattern = pattern,
        replacement = "",
        description = "",
        enabled = true,
        wholeWords = false,
        scope = scope,
    )

    private fun block(index: Int, text: String) = TranslationBlockEntity(
        translationId = 1,
        blockIndex = index,
        sourceTextHash = "h",
        translatedText = text,
    )
}
