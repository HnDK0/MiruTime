package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.translation.domain.ChapterTranslationResult
import dev.mirutime.app.features.translation.domain.MetadataRequest
import dev.mirutime.app.features.translation.domain.MetadataTranslationResult
import dev.mirutime.app.features.translation.domain.TranslationEngine
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationRevision
import dev.mirutime.translator.TranslationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationControllerTest {

    @Test
    fun `success sets ready with mapped texts`() = runTest {
        val engine = FakeEngine { request -> ok(request, "translated") }
        val controller = TranslationController(engine, this)

        controller.submit(request())
        advanceUntilIdle()

        val entry = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Ready, entry?.status)
        assertEquals(mapOf(1 to "translated"), entry?.translations)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `disk cache is used without calling the engine`() = runTest {
        val engine = FakeEngine { request -> ok(request, "from-engine") }
        val cache = FakeCache(mapOf(1 to "from-disk"))
        val controller = TranslationController(engine, this, cache)

        controller.submit(request())
        advanceUntilIdle()

        assertEquals(ChapterTranslationStatus.Ready, controller.state.value[CHAPTER_URL]?.status)
        assertEquals(mapOf(1 to "from-disk"), controller.state.value[CHAPTER_URL]?.translations)
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `successful translation is written to disk cache`() = runTest {
        val engine = FakeEngine { request -> ok(request, "translated") }
        val cache = FakeCache(null)
        val controller = TranslationController(engine, this, cache)

        controller.submit(request())
        advanceUntilIdle()

        assertEquals(1, cache.saves)
        assertEquals(mapOf(1 to "translated"), cache.lastSaved)
    }

    @Test
    fun `cached chapter is ready without calling the engine again`() = runTest {
        val engine = FakeEngine { request -> ok(request, request.revision.sourceHash) }
        val controller = TranslationController(engine, this)

        controller.submit(request(sourceHash = "a"))
        advanceUntilIdle()
        controller.submit(request(sourceHash = "b"))
        advanceUntilIdle()
        controller.submit(request(sourceHash = "a"))
        advanceUntilIdle()

        val entry = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Ready, entry?.status)
        assertEquals(mapOf(1 to "a"), entry?.translations)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `error sets failed and keeps the request retryable`() = runTest {
        val engine = FakeEngine { Outcome.Err(PluginError.Network("offline")) }
        val controller = TranslationController(engine, this)

        controller.submit(request())
        advanceUntilIdle()

        assertEquals(ChapterTranslationStatus.Failed, controller.state.value[CHAPTER_URL]?.status)
        assertEquals(emptyMap<Int, String>(), controller.state.value[CHAPTER_URL]?.translations)

        controller.submit(request())
        advanceUntilIdle()

        assertEquals(ChapterTranslationStatus.Failed, controller.state.value[CHAPTER_URL]?.status)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `local network denial keeps an actionable failure reason`() = runTest {
        val engine = FakeEngine {
            Outcome.Err(TranslationError.LocalNetworkRequired(ProviderId.OPENAI_COMPATIBLE))
        }
        val controller = TranslationController(engine, this)

        controller.submit(request())
        advanceUntilIdle()

        val entry = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Failed, entry?.status)
        assertEquals(ChapterTranslationFailure.LocalNetworkRequired, entry?.failure)
    }

    @Test
    fun `timeout fails the chapter and the worker continues`() = runTest {
        val nextUrl = "https://example.com/book/chapter-2"
        val engine = FakeEngine { request ->
            if (request.chapterUrl == CHAPTER_URL) {
                awaitCancellation()
            } else {
                ok(request, "next")
            }
        }
        val cache = FakeCache(null)
        val controller = TranslationController(
            engine = engine,
            scope = this,
            cache = cache,
            chapterTimeout = 100.milliseconds,
        )

        controller.submit(request())
        controller.submit(request(chapterUrl = nextUrl))
        advanceUntilIdle()

        assertEquals(ChapterTranslationStatus.Failed, controller.state.value[CHAPTER_URL]?.status)
        assertEquals(ChapterTranslationStatus.Ready, controller.state.value[nextUrl]?.status)
        assertEquals(2, engine.requests.size)
        assertEquals(1, cache.failures)
        // The timed-out queue key was released: retry is accepted immediately.
        assertTrue(controller.submit(request()))
        advanceUntilIdle()
        assertEquals(ChapterTranslationStatus.Failed, controller.state.value[CHAPTER_URL]?.status)
    }

    @Test
    fun `outer cancellation is not converted into a timeout failure`() = runTest {
        val engine = FakeEngine { awaitCancellation() }
        val cache = FakeCache(null)
        val workerScope = CoroutineScope(coroutineContext + Job())
        val controller = TranslationController(
            engine = engine,
            scope = workerScope,
            cache = cache,
            chapterTimeout = 10.seconds,
        )

        controller.submit(request())
        runCurrent()
        workerScope.cancel()
        advanceUntilIdle()

        assertEquals(ChapterTranslationStatus.Loading, controller.state.value[CHAPTER_URL]?.status)
        assertEquals(0, cache.failures)
    }

    @Test
    fun `stale result does not overwrite a newer request`() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val engine = FakeEngine { request ->
            gate.receive()
            ok(request, request.revision.sourceHash)
        }
        val controller = TranslationController(engine, this)

        controller.submit(request(sourceHash = "a"))
        controller.submit(request(sourceHash = "b"))
        runCurrent()
        assertEquals(1, engine.requests.size)

        // First result belongs to A; B is already the chapter's current key.
        gate.trySend(Unit)
        runCurrent()
        assertEquals(ChapterTranslationStatus.Loading, controller.state.value[CHAPTER_URL]?.status)

        gate.trySend(Unit)
        advanceUntilIdle()

        val entry = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Ready, entry?.status)
        assertEquals(mapOf(1 to "b"), entry?.translations)
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun `preload publishes a disk hit without Loading or calling the engine`() = runTest {
        val engine = FakeEngine { request -> ok(request, "from-engine") }
        val cache = FakeCache(mapOf(1 to "from-disk"))
        val controller = TranslationController(engine, this, cache)

        assertTrue(controller.preload(request()))

        val entry = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Ready, entry?.status)
        assertEquals(mapOf(1 to "from-disk"), entry?.translations)
        advanceUntilIdle()
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `preload miss leaves state untouched and starts no engine`() = runTest {
        val engine = FakeEngine { request -> ok(request, "from-engine") }
        val controller = TranslationController(engine, this, FakeCache(null))

        assertFalse(controller.preload(request()))
        assertNull(controller.state.value[CHAPTER_URL])
        advanceUntilIdle()
        assertEquals(0, engine.requests.size)

        // The normal submit path still works after a miss.
        assertTrue(controller.submit(request()))
        advanceUntilIdle()
        assertEquals(ChapterTranslationStatus.Ready, controller.state.value[CHAPTER_URL]?.status)
    }

    @Test
    fun `preload does not reload a chapter it already published`() = runTest {
        val engine = FakeEngine { request -> ok(request, "from-engine") }
        val cache = FakeCache(mapOf(1 to "from-disk"))
        val controller = TranslationController(engine, this, cache)

        assertTrue(controller.preload(request()))
        assertTrue(controller.preload(request()))

        assertEquals(1, cache.loads)
        assertEquals(0, engine.requests.size)
        assertEquals(ChapterTranslationStatus.Ready, controller.state.value[CHAPTER_URL]?.status)
    }

    @Test
    fun `preload rethrows cancellation instead of publishing a miss`() = runTest {
        val controller = TranslationController(
            FakeEngine { request -> ok(request, "from-engine") },
            this,
            CancellingCache(),
        )

        var rethrown = false
        try {
            controller.preload(request())
        } catch (cancellation: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
        assertNull(controller.state.value[CHAPTER_URL])
    }

    private class CancellingCache : ChapterTranslationCache {
        override suspend fun load(request: TranslationRequest): Map<Int, String>? =
            throw CancellationException("preload cancelled")

        override suspend fun save(request: TranslationRequest, result: ChapterTranslationResult) =
            Unit

        override suspend fun markFailed(request: TranslationRequest) = Unit
    }

    private class FakeCache(
        private val loaded: Map<Int, String>?,
    ) : ChapterTranslationCache {
        var saves = 0
        var loads = 0
        var failures = 0
        var lastSaved: Map<Int, String>? = null

        override suspend fun load(request: TranslationRequest): Map<Int, String>? {
            loads++
            return loaded
        }

        override suspend fun save(request: TranslationRequest, result: ChapterTranslationResult) {
            saves++
            lastSaved = result.translations
        }

        override suspend fun markFailed(request: TranslationRequest) {
            failures++
        }
    }

    private fun request(
        sourceHash: String = "a",
        chapterUrl: String = CHAPTER_URL,
    ) = TranslationRequest(
        bookId = 1L,
        bookUrl = "https://example.com/book",
        chapterUrl = chapterUrl,
        units = listOf(TranslationUnit(id = "1", text = "Hello")),
        source = LanguageCode.parse("en"),
        target = LanguageCode.parse("ru"),
        provider = ProviderId.GEMINI,
        prompt = null,
        revision = TranslationRevision(sourceHash = sourceHash, pipelineVersion = "test"),
    )

    private fun ok(request: TranslationRequest, text: String) = Outcome.Ok(
        ChapterTranslationResult(
            translations = mapOf(1 to text),
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
        ): Outcome<MetadataTranslationResult> =
            error("chapter test engine has no metadata path")
    }

    private companion object {
        const val CHAPTER_URL = "https://example.com/book/chapter-1"
    }
}
