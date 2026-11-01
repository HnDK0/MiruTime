package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.book.data.BookDataProvider
import dev.mirutime.app.features.book.data.MembershipResult
import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.app.features.translation.domain.ChapterTranslationResult
import dev.mirutime.app.features.translation.domain.MetadataRequest
import dev.mirutime.app.features.translation.domain.MetadataTranslationResult
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.translator.ProviderId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterBulkTranslatorTest {
    private val bookUrl = "https://site/book"

    private fun config() = ChapterTranslationConfig(
        mode = TranslationMode(source = "en", target = "ru", provider = "GEMINI"),
        prompt = null,
    )

    private fun provider() = StubProvider()

    private fun document(index: Int, vararg kinds: Pair<BlockKind, String>): ReaderDocument {
        val chapterUrl = "$bookUrl/ch/$index"
        return ReaderDocument(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            chapterIndex = index,
            title = "Chapter $index",
            blocks = kinds.mapIndexed { blockIndex, (kind, text) ->
                ReaderBlock(BlockId(chapterUrl, blockIndex), kind, text)
            },
        )
    }

    @Test
    fun `bulk submit queues one request per chapter and skips duplicates`() = runTest {
        val facade = provider()
        val engine = FakeEngine { request -> ok(request) }
        val controller = TranslationController(engine, this)
        val documents = (1..3).associateWith { index ->
            document(index, BlockKind.Paragraph to "Text $index")
        }
        var loads = 0
        val translator = ChapterBulkTranslator(
            provider = facade,
            controller = controller,
            loadDocument = { _, index -> loads++; documents[index] },
            configFor = { config() },
        )

        val first = translator.translate(bookUrl, listOf(3, 1, 2, 2))
        assertEquals(3, first.enqueued)
        assertEquals(1, facade.ensureCalls)
        assertEquals(3, loads)
        advanceUntilIdle()
        assertEquals(3, engine.requests.size)
        assertTrue(controller.state.value.values.all { it.status == ChapterTranslationStatus.Ready })

        val second = translator.translate(bookUrl, listOf(1, 2, 3))
        assertEquals(0, second.enqueued)
        assertEquals(3, second.alreadyCurrent)
        advanceUntilIdle()
        assertEquals(3, engine.requests.size)
    }

    @Test
    fun `queued chapter stays Loading until the engine answers`() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val engine = FakeEngine { request ->
            gate.receive()
            ok(request)
        }
        val controller = TranslationController(engine, this)
        val doc = document(1, BlockKind.Paragraph to "Text")
        val translator = ChapterBulkTranslator(
            provider = provider(),
            controller = controller,
            loadDocument = { _, _ -> doc },
            configFor = { config() },
        )

        translator.translate(bookUrl, listOf(1))
        runCurrent()
        assertEquals(ChapterTranslationStatus.Loading, controller.state.value[doc.chapterUrl]?.status)

        gate.trySend(Unit)
        advanceUntilIdle()
        assertEquals(ChapterTranslationStatus.Ready, controller.state.value[doc.chapterUrl]?.status)
    }

    @Test
    fun `failure is visible and a retry submits again`() = runTest {
        var fail = true
        val engine = FakeEngine { request ->
            if (fail) Outcome.Err(PluginError.Network("offline")) else ok(request)
        }
        val controller = TranslationController(engine, this)
        val doc = document(1, BlockKind.Paragraph to "Text")
        val translator = ChapterBulkTranslator(
            provider = provider(),
            controller = controller,
            loadDocument = { _, _ -> doc },
            configFor = { config() },
        )

        translator.translate(bookUrl, listOf(1))
        advanceUntilIdle()
        assertEquals(ChapterTranslationStatus.Failed, controller.state.value[doc.chapterUrl]?.status)

        fail = false
        val retry = translator.translate(bookUrl, listOf(1))
        assertEquals(1, retry.enqueued)
        advanceUntilIdle()
        assertEquals(2, engine.requests.size)
        assertEquals(ChapterTranslationStatus.Ready, controller.state.value[doc.chapterUrl]?.status)
    }

    @Test
    fun `unconfigured book reports without ensuring or loading`() = runTest {
        val facade = provider()
        var loads = 0
        val translator = ChapterBulkTranslator(
            provider = facade,
            controller = TranslationController(FakeEngine { request -> ok(request) }, this),
            loadDocument = { _, _ -> loads++; null },
            configFor = { null },
        )

        val result = translator.translate(bookUrl, listOf(1, 2))

        assertFalse(result.configured)
        assertEquals(0, facade.ensureCalls)
        assertEquals(0, loads)
    }

    @Test
    fun `image-only chapter is skipped without a request`() = runTest {
        val engine = FakeEngine { request -> ok(request) }
        val doc = document(1, BlockKind.Image to "")
        val translator = ChapterBulkTranslator(
            provider = provider(),
            controller = TranslationController(engine, this),
            loadDocument = { _, _ -> doc },
            configFor = { config() },
        )

        val result = translator.translate(bookUrl, listOf(1))
        advanceUntilIdle()

        assertEquals(1, result.skipped)
        assertEquals(0, engine.requests.size)
    }

    private fun ok(request: TranslationRequest) = Outcome.Ok(
        ChapterTranslationResult(
            translations = request.units.associate { unit ->
                unit.id.toInt() to "translated"
            },
            provider = request.provider,
            model = null,
        ),
    )

    private class FakeEngine(
        private val respond: suspend (TranslationRequest) -> Outcome<ChapterTranslationResult>,
    ) : TranslationEngine {
        val requests = mutableListOf<TranslationRequest>()

        override suspend fun translateChapter(
            request: TranslationRequest,
        ): Outcome<ChapterTranslationResult> {
            requests += request
            return respond(request)
        }

        override suspend fun translateMetadata(
            request: MetadataRequest,
        ): Outcome<MetadataTranslationResult> = error("no metadata path")
    }

    private class StubProvider : BookDataProvider {
        var ensureCalls = 0

        override suspend fun book(bookUrl: String): BookDetails? = null
        override suspend fun ensureHistoryBook(bookUrl: String) {
            ensureCalls++
        }

        override suspend fun chapters(bookUrl: String): List<BookChapter> = emptyList()
        override suspend fun chapterContent(bookUrl: String, chapterIndex: Int): ChapterContent? = null
        override suspend fun moveShelf(bookUrl: String, shelfId: String) = Unit
        override suspend fun setInLibrary(bookUrl: String, inLibrary: Boolean) =
            MembershipResult(success = true)
        override fun setChapterDownloaded(
            bookUrl: String,
            range: IntRange,
            downloaded: Boolean,
        ) = Unit
        override suspend fun downloadNext100(bookUrl: String) = Unit
        override suspend fun markChapterRead(
            bookUrl: String,
            chapterIndex: Int,
            read: Boolean,
        ) = Unit
    }
}
