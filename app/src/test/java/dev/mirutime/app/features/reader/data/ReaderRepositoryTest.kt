package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.FakeBookDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.reader.document.chapterKey
import dev.mirutime.app.features.reader.document.mangaPages
import dev.mirutime.app.features.reader.document.SentenceSplitter
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.text.RegexRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRepositoryTest {
    private val bookUrl = "fake://toonsource/mage-ten-thousand-rebirths"

    @Test
    fun `book maps chapters and document parses blocks`() = runBlocking {
        val repository = DefaultReaderRepository(FakeBookDataProvider())
        val book = repository.book(bookUrl)
        assertNotNull(book)
        assertTrue(book!!.chapters.isNotEmpty())
        assertTrue(book.isManga)

        val chapter = book.chapters.first()
        val document = repository.document(bookUrl, chapter.index)
        assertNotNull(document)
        assertEquals(chapter.title, document!!.title)
        assertEquals(chapterKey(bookUrl, chapter.index), document.chapterUrl)
        assertTrue(document.blocks.isNotEmpty())
        assertEquals(0, document.blocks.first().id.index)
    }

    @Test
    fun `remote manga chapter opens as ordered image pages`() = runBlocking {
        val url = "https://plugin.example/manga"
        val chapterUrl = "https://plugin.example/manga/chapter-1"
        val provider = StubProvider(
            contentType = ContentType.Manga,
            content = ChapterContent(
                bookUrl = url,
                chapterIndex = 1,
                title = "Chapter 1",
                html = "<p><img src=\"https://cdn.example/1.jpg\"/></p>" +
                    "<p><img src=\"https://cdn.example/2.jpg\"/></p>",
                chapterUrl = chapterUrl,
            ),
        )
        val repository = DefaultReaderRepository(provider)

        val book = repository.book(url)
        val document = repository.document(url, 1)

        assertTrue(book!!.isManga)
        assertEquals(
            listOf("https://cdn.example/1.jpg", "https://cdn.example/2.jpg"),
            document!!.mangaPages().map { it.imageRef },
        )
    }

    @Test
    fun `book maps manga content type`() = runBlocking {
        val manga = DefaultReaderRepository(
            StubProvider(contentType = ContentType.Manga),
        ).book("https://plugin.example/manga")
        val novel = DefaultReaderRepository(
            StubProvider(contentType = ContentType.Novel),
        ).book("https://plugin.example/novel")

        assertTrue(manga!!.isManga)
        assertFalse(novel!!.isManga)
    }

    @Test
    fun `book maps the real plugin chapter url`() = runBlocking {
        val chapterUrl = "https://plugin.example/book/1/chapter-1"
        val provider = StubProvider(
            chapters = listOf(
                BookChapter(
                    index = 1,
                    title = "Chapter 1",
                    isRead = false,
                    downloaded = false,
                    translated = false,
                    downloading = false,
                    url = chapterUrl,
                ),
            ),
        )

        val book = DefaultReaderRepository(provider).book("https://plugin.example/book/1")

        assertEquals(chapterUrl, book!!.chapters.single().chapterUrl)
    }

    @Test
    fun `document uses the plugin chapter url and never asks for metadata`() = runBlocking {
        val chapterUrl = "https://plugin.example/book/1/chapter-2"
        val provider = StubProvider(
            content = ChapterContent(
                bookUrl = "https://plugin.example/book/1",
                chapterIndex = 2,
                title = "Chapter 2",
                html = "<p>Text</p>",
                chapterUrl = chapterUrl,
            ),
        )

        val document = DefaultReaderRepository(provider)
            .document("https://plugin.example/book/1", 2)

        assertEquals(chapterUrl, document!!.chapterUrl)
        assertFalse(provider.bookRequested)
    }

    @Test
    fun `document dedups a title heading after angle normalization`() = runBlocking {
        val url = "https://plugin.example/angles"
        val provider = StubProvider(
            content = ChapterContent(
                bookUrl = url,
                chapterIndex = 1,
                title = "<System> Awakening",
                html = "<h2>&lt;System&gt; Awakening</h2><p>Text</p>",
                chapterUrl = "$url/chapter-1",
            ),
        )

        val document = DefaultReaderRepository(provider).document(url, 1)!!

        assertEquals("<System> Awakening", document.title)
        assertEquals("⟨System⟩ Awakening", document.blocks.first().original)
        assertNotNull(document.titleBlock())
        assertEquals(listOf("Text"), document.bodyBlocks().map { it.original })
    }

    @Test
    fun `regex rules see typographic angles after conversion`() = runBlocking {
        val url = "https://plugin.example/angles-rule"
        val provider = StubProvider(
            content = ChapterContent(
                bookUrl = url,
                chapterIndex = 1,
                title = "Chapter 1",
                html = "<p>&lt;System&gt; hi</p>",
            ),
        )
        AppSettings.novelRegexRules[url] = listOf(
            RegexRule(
                id = 1,
                pattern = "<System>",
                replacement = "NO",
                description = "ascii angle must not match",
                enabled = true,
                wholeWords = false,
            ),
            RegexRule(
                id = 2,
                pattern = "⟨System⟩",
                replacement = "YES",
                description = "converted angle matches",
                enabled = true,
                wholeWords = false,
            ),
        )
        try {
            val document = DefaultReaderRepository(provider).document(url, 1)!!

            assertEquals(listOf("YES hi"), document.blocks.map { it.original })
        } finally {
            AppSettings.novelRegexRules.remove(url)
        }
    }

    @Test
    fun `document applies enabled regex rules and ignores disabled ones`() = runBlocking {
        val url = "https://plugin.example/regex"
        val provider = StubProvider(
            content = ChapterContent(
                bookUrl = url,
                chapterIndex = 1,
                title = "Chapter 1",
                html = "<p>foo baz</p><p>bar</p>",
            ),
        )
        AppSettings.novelRegexRules[url] = listOf(
            RegexRule(
                id = 1,
                pattern = "foo",
                replacement = "bar",
                description = "rename",
                enabled = true,
                wholeWords = false,
            ),
            RegexRule(
                id = 2,
                pattern = "bar",
                replacement = "NO",
                description = "disabled",
                enabled = false,
                wholeWords = false,
            ),
        )
        try {
            val document = DefaultReaderRepository(provider).document(url, 1)

            assertEquals(
                listOf("bar baz", "bar"),
                document!!.blocks.map { it.original },
            )
        } finally {
            AppSettings.novelRegexRules.remove(url)
        }
    }

    @Test
    fun `regex rule that shortens text recomputes in-bounds sentence spans`() = runBlocking {
        val url = "https://plugin.example/regex-shorten"
        val text = "This first sentence is long enough to exceed the minimum splitting threshold even after the cleanup rule removes a few words from it. This second sentence is also long enough to keep the paragraph above one hundred characters in total."
        val provider = StubProvider(
            content = ChapterContent(
                bookUrl = url,
                chapterIndex = 1,
                title = "Chapter 1",
                html = "<p>$text</p>",
            ),
        )
        AppSettings.novelRegexRules[url] = listOf(
            RegexRule(
                id = 1,
                pattern = "a few words ",
                replacement = "",
                description = "shorten",
                enabled = true,
                wholeWords = false,
            ),
        )
        try {
            val block = DefaultReaderRepository(provider).document(url, 1)!!.blocks.single()

            assertTrue(block.original.length < text.length)
            assertEquals(SentenceSplitter.spans(block.original), block.sentences)
            assertEquals(2, block.sentences.size)
            assertEquals(block.original.length, block.sentences.last().end)
            assertTrue(block.sentences.all { it.end <= block.original.length })
        } finally {
            AppSettings.novelRegexRules.remove(url)
        }
    }

    private class StubProvider(
        private val chapters: List<BookChapter> = emptyList(),
        private val content: ChapterContent? = null,
        private val contentType: ContentType = ContentType.Novel,
    ) : BookDataProvider {
        var bookRequested = false

        override suspend fun book(bookUrl: String): BookDetails {
            bookRequested = true
            return BookDetails(
                bookUrl = bookUrl,
                title = "Book",
                source = "Plugin",
                author = "Author",
                originLanguage = null,
                contentType = contentType,
                rating = 0f,
                ongoing = false,
                updatedLabel = "",
                totalChapters = chapters.size,
                readCount = 0,
                readMinutes = 0,
                ttsMinutes = 0,
                inLibrary = false,
                shelfId = "",
                genres = emptyList(),
                description = "",
                sourceUrl = bookUrl,
                authUrl = null,
                chapters = emptyList(),
                reviews = emptyList(),
            )
        }

        override suspend fun chapters(bookUrl: String): List<BookChapter> = chapters

        override suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? =
            content

        override suspend fun moveShelf(bookUrl: String, shelfId: String) = Unit
        override suspend fun ensureHistoryBook(bookUrl: String) = Unit
        override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean) =
            MembershipResult(success = true)
        override fun setChapterDownloaded(bookUrl: String, range: IntRange, downloaded: Boolean) = Unit
        override suspend fun downloadNext100(bookUrl: String) = Unit
        override suspend fun markChapterRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
    }
}
