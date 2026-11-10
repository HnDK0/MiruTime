package dev.mirutime.feature.reader.api

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderBook
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.feature.reader.MangaReaderStyle
import dev.mirutime.feature.reader.ReaderConfig
import dev.mirutime.feature.reader.ReaderConfigStore
import dev.mirutime.feature.reader.ReaderMode
import dev.mirutime.feature.reader.ReaderStyle
import dev.mirutime.feature.reader.SavedReading
import dev.mirutime.feature.reader.session.ReadTracker
import dev.mirutime.feature.reader.session.ReaderSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRulesReloadTest {
    private val chapters = listOf(ReaderChapter("chapter-1", 1, "Chapter 1", false))

    private fun document(text: String) = ReaderDocument(
        bookUrl = "book",
        chapterUrl = "chapter-1",
        chapterIndex = 1,
        title = "Chapter 1",
        blocks = listOf(ReaderBlock(BlockId("chapter-1", 0), BlockKind.Paragraph, text)),
    )

    private class Source(
        private val chapters: List<ReaderChapter>,
        private val initial: ReaderDocument,
        private val reloaded: ReaderDocument?,
    ) : ReaderContentSource {
        override suspend fun book(bookUrl: String): ReaderBook =
            ReaderBook(bookUrl, "Book", chapters)

        override suspend fun document(bookUrl: String, chapterIndex: Int): ReaderDocument = initial

        override suspend fun documentForRulesReload(
            bookUrl: String,
            chapterIndex: Int,
            allowTranslationOnly: Boolean,
        ): ReaderDocument? = reloaded

        override suspend fun markRead(bookUrl: String, chapterIndex: Int, read: Boolean) = Unit
    }

    private class RecordingTranslationPort : ReaderTranslationPort {
        val ensured = mutableListOf<Pair<Int, String>>()
        override val state = MutableStateFlow<Map<String, ReaderChapterTranslation>>(emptyMap())
        override val metadata = MutableStateFlow<Map<String, ReaderBookMetadata>>(emptyMap())

        override fun activeMode(bookUrl: String): ReaderTranslationMode =
            ReaderTranslationMode(source = "en", target = "ru", provider = "GEMINI")

        override fun providerLabel(provider: String): String = provider

        override fun ensureChapter(bookUrl: String, chapterIndex: Int, document: ReaderDocument) {
            ensured += chapterIndex to document.chapterUrl
        }

        override fun forget(chapterUrls: Collection<String>) = Unit
        override fun metadataConfigKey(bookUrl: String): String? = null
        override suspend fun hydrateMetadata(bookUrl: String) = Unit
        override fun submitChapterTitles(
            bookUrl: String,
            title: String,
            chapters: List<Pair<Int, String>>,
        ) = Unit
    }

    private class Positions : ReaderPositionStore {
        override suspend fun load(bookUrl: String): SavedReading? = null
        override suspend fun save(bookUrl: String, saved: SavedReading) = Unit
        override suspend fun clear(bookUrl: String) = Unit
    }

    private class Config : ReaderConfigStore {
        private val _config = MutableStateFlow(ReaderConfig(style = ReaderStyle.Default))
        override val config: StateFlow<ReaderConfig> = _config.asStateFlow()

        override fun setMode(mode: ReaderMode) = Unit
        override fun setStyle(style: ReaderStyle) = Unit
        override fun setMangaStyle(style: MangaReaderStyle) = Unit
    }

    private fun store(source: Source, scope: kotlinx.coroutines.CoroutineScope) = ReaderSessionStore(
        content = source,
        positions = Positions(),
        config = Config(),
        readTracker = ReadTracker(source),
        scope = scope,
    )

    @Test
    fun `rules reload re-requests the active chapter translation`() = runTest {
        val source = Source(chapters, document("before"), document("after"))
        val session = store(source, this)
        session.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val translation = RecordingTranslationPort()

        assertTrue(reloadActiveChapterForRules(session, translation, "book"))

        assertEquals("after", session.state.value.documents.getValue(1).blocks.first().original)
        assertEquals(listOf(1 to "chapter-1"), translation.ensured)
    }

    @Test
    fun `a failed rules reload requests no translation`() = runTest {
        val source = Source(chapters, document("before"), reloaded = null)
        val session = store(source, this)
        session.open("book", 1, restoreSaved = false)
        advanceUntilIdle()
        val translation = RecordingTranslationPort()

        assertFalse(reloadActiveChapterForRules(session, translation, "book"))

        assertEquals("before", session.state.value.documents.getValue(1).blocks.first().original)
        assertTrue(translation.ensured.isEmpty())
    }
}
