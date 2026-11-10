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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
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
    fun `forget clears the in-memory translation and forces a new request`() = runTest {
        val engine = FakeEngine { request -> ok(request, "first") }
        val controller = TranslationController(engine, this)

        controller.submit(request())
        advanceUntilIdle()
        assertEquals(ChapterTranslationStatus.Ready, controller.state.value[CHAPTER_URL]?.status)

        controller.forget(listOf(CHAPTER_URL))
        assertNull(controller.state.value[CHAPTER_URL])

        assertTrue(controller.submit(request()))
        advanceUntilIdle()
        assertEquals(2, engine.requests.size)
        assertEquals(mapOf(1 to "first"), controller.state.value[CHAPTER_URL]?.translations)
    }

    @Test
    fun `a forgotten in-flight result is neither saved nor published`() = runTest {
        val gate = Channel<Unit>(Channel.UNLIMITED)
        val engine = FakeEngine { request ->
            gate.receive()
            ok(request, "late")
        }
        val cache = FakeCache(null)
        val controller = TranslationController(engine, this, cache)

        controller.submit(request())
        runCurrent()
        controller.forget(listOf(CHAPTER_URL))
        gate.trySend(Unit)
        advanceUntilIdle()

        assertEquals(0, cache.saves)
        assertNull(controller.state.value[CHAPTER_URL])
    }

    @Test
    fun `forget cancels the in-flight translation and saves nothing`() = runTest {
        var cancelled = false
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine { request ->
            try {
                gate.await()
            } catch (cancel: CancellationException) {
                cancelled = true
                throw cancel
            }
            ok(request, "late")
        }
        val cache = FakeCache(null)
        val controller = TranslationController(engine, this, cache)

        controller.submit(request())
        runCurrent()
        controller.forget(listOf(CHAPTER_URL))
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(cancelled)
        assertEquals(0, cache.saves)
        assertEquals(0, cache.failures)
        assertNull(controller.state.value[CHAPTER_URL])
    }

    @Test
    fun `cancelling one chapter leaves the next one working`() = runTest {
        val nextUrl = "https://example.com/book/chapter-2"
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine { request ->
            if (request.chapterUrl == CHAPTER_URL) {
                gate.await()
                ok(request, "first")
            } else {
                ok(request, "next")
            }
        }
        val controller = TranslationController(engine, this)

        controller.submit(request())
        controller.submit(request(chapterUrl = nextUrl))
        runCurrent()
        controller.forget(listOf(CHAPTER_URL))
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(controller.state.value[CHAPTER_URL])
        assertEquals(ChapterTranslationStatus.Ready, controller.state.value[nextUrl]?.status)
        assertEquals(mapOf(1 to "next"), controller.state.value[nextUrl]?.translations)
    }

    @Test
    fun `a forced failure keeps the old translation and writes no failure row`() = runTest {
        val engine = FakeEngine { Outcome.Err(PluginError.Network("offline")) }
        val cache = FallbackCache(exact = null, latest = mapOf(1 to "old"))
        val controller = TranslationController(engine, this, cache)

        val result = async { controller.submitForced(request()) }
        advanceUntilIdle()

        assertFalse(result.await())
        val entry = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Ready, entry?.status)
        assertEquals(mapOf(1 to "old"), entry?.translations)
        assertEquals(0, cache.failures)
    }

    @Test
    fun `a forced success replaces the old translation`() = runTest {
        val engine = FakeEngine { request -> ok(request, "fresh") }
        val cache = FallbackCache(exact = mapOf(1 to "old"), latest = mapOf(1 to "old"))
        val controller = TranslationController(engine, this, cache)

        val result = async { controller.submitForced(request()) }
        advanceUntilIdle()

        assertTrue(result.await())
        assertEquals(mapOf(1 to "fresh"), controller.state.value[CHAPTER_URL]?.translations)
        assertEquals(1, cache.saves)
    }

    @Test
    fun `a forced attempt keeps the old text visible while the engine runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine { request ->
            gate.await()
            ok(request, "fresh")
        }
        val controller = TranslationController(engine, this, FakeCache(mapOf(1 to "old")))
        assertTrue(controller.preload(request()))

        val result = async { controller.submitForced(request()) }
        runCurrent()

        val during = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Ready, during?.status)
        assertEquals(mapOf(1 to "old"), during?.translations)
        assertFalse(result.isCompleted)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(result.await())
        assertEquals(mapOf(1 to "fresh"), controller.state.value[CHAPTER_URL]?.translations)
    }

    @Test
    fun `a forced retry with a changed config does not relabel the old text`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine { request ->
            if (request.revision.sourceHash == "a") {
                ok(request, "old")
            } else {
                gate.await()
                Outcome.Err(PluginError.Network("offline"))
            }
        }
        val cache = FallbackCache(exact = mapOf(1 to "old"), latest = mapOf(1 to "old"))
        val controller = TranslationController(engine, this, cache)
        controller.submit(request(sourceHash = "a"))
        advanceUntilIdle()
        assertEquals(mapOf(1 to "old"), controller.state.value[CHAPTER_URL]?.translations)

        val result = async { controller.submitForced(request(sourceHash = "b")) }
        runCurrent()

        val during = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Loading, during?.status)
        assertTrue(during?.translations?.isEmpty() == true)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(result.await())
        val after = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Failed, after?.status)
        assertTrue(after?.translations?.isEmpty() == true)
        assertEquals(0, cache.sameConfigLoads)
    }

    @Test
    fun `a same-key forced failure does not adopt a foreign persisted row`() = runTest {
        val engine = FakeEngine { Outcome.Err(PluginError.Network("offline")) }
        val cache = FallbackCache(exact = null, latest = null)
        val controller = TranslationController(engine, this, cache)

        controller.submit(request(sourceHash = "b"))
        advanceUntilIdle()
        assertEquals(ChapterTranslationStatus.Failed, controller.state.value[CHAPTER_URL]?.status)
        cache.latest = mapOf(1 to "foreign")
        val sameConfigLoads = cache.sameConfigLoads

        val result = async { controller.submitForced(request(sourceHash = "b")) }
        advanceUntilIdle()

        assertFalse(result.await())
        val after = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Failed, after?.status)
        assertTrue(after?.translations?.isEmpty() == true)
        // The forced path knows the config already: it must not look for a
        // persisted row of another prompt.
        assertEquals(sameConfigLoads, cache.sameConfigLoads)
    }

    @Test
    fun `a failed non-forced attempt does not invalidate a usable older translation`() = runTest {
        val engine = FakeEngine { Outcome.Err(PluginError.Network("offline")) }
        val cache = FallbackCache(exact = null, latest = mapOf(1 to "old"))
        val controller = TranslationController(engine, this, cache)

        controller.submit(request())
        advanceUntilIdle()

        val entry = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Failed, entry?.status)
        assertEquals(mapOf(1 to "old"), entry?.translations)
        assertEquals(0, cache.failures)
    }

    @Test
    fun `a non-forced failure does not publish the text of another prompt`() = runTest {
        val engine = FakeEngine { Outcome.Err(PluginError.Network("offline")) }
        val cache = PromptSwitchCache()
        val controller = TranslationController(engine, this, cache)

        controller.submit(request())
        advanceUntilIdle()

        val entry = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Failed, entry?.status)
        assertTrue(entry?.translations?.isEmpty() == true)
    }

    @Test
    fun `a forced failure that restores another revision does not cache it for the current one`() =
        runTest {
            val engine = FakeEngine { Outcome.Err(PluginError.Network("offline")) }
            val cache = FallbackCache(exact = null, latest = mapOf(1 to "old"))
            val controller = TranslationController(engine, this, cache)

            val forced = async { controller.submitForced(request(sourceHash = "b")) }
            advanceUntilIdle()
            assertFalse(forced.await())
            assertEquals(mapOf(1 to "old"), controller.state.value[CHAPTER_URL]?.translations)

            // Another revision takes the chapter and fails.
            controller.submit(request(sourceHash = "c"))
            advanceUntilIdle()
            assertEquals(ChapterTranslationStatus.Failed, controller.state.value[CHAPTER_URL]?.status)

            // The restored text belongs to another revision: it must not be
            // served from the memory cache instead of a new translation.
            controller.submit(request(sourceHash = "b"))
            advanceUntilIdle()

            assertEquals(2, engine.requests.count { it.revision.sourceHash == "b" })
        }

    @Test
    fun `a cancelled cache load fails the chapter instead of leaving it in Loading`() = runTest {
        val engine = FakeEngine { request -> ok(request, "translated") }
        val controller = TranslationController(engine, this, CancellingLoadCache(CHAPTER_URL))

        controller.submit(request())
        advanceUntilIdle()

        val entry = controller.state.value[CHAPTER_URL]
        assertEquals(ChapterTranslationStatus.Failed, entry?.status)
        assertTrue(entry?.translations?.isEmpty() == true)
        // A failed chapter stays retryable.
        assertTrue(controller.submit(request()))
    }

    @Test
    fun `a record queued while the worker stops is handed to a new worker`() = runTest {
        val nextUrl = "https://example.com/book/chapter-2"
        val dispatcher = JobCapturingDispatcher(StandardTestDispatcher(testScheduler))
        val workerScope = CoroutineScope(coroutineContext + Job() + dispatcher)
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine { request ->
            if (request.chapterUrl == CHAPTER_URL) {
                gate.await()
                ok(request, "first")
            } else {
                ok(request, "second")
            }
        }
        val controller = TranslationController(engine, workerScope)

        controller.submit(request())
        runCurrent()
        assertEquals(1, engine.requests.size)

        // The second chapter waits in the queue while the worker is busy.
        controller.submit(request(chapterUrl = nextUrl))
        assertEquals(1, engine.requests.size)

        // The worker stops right now: the queued record must survive it.
        dispatcher.workerJob.cancel()
        advanceUntilIdle()

        assertEquals(2, engine.requests.size)
        val entry = controller.state.value[nextUrl]
        assertEquals(ChapterTranslationStatus.Ready, entry?.status)
        assertEquals(mapOf(1 to "second"), entry?.translations)

        gate.complete(Unit)
        advanceUntilIdle()
        workerScope.cancel()
    }

    @Test
    fun `a cancelled cache load does not translate the chapter and the worker continues`() = runTest {
        val nextUrl = "https://example.com/book/chapter-2"
        val engine = FakeEngine { request -> ok(request, "next") }
        val cache = CancellingLoadCache(CHAPTER_URL)
        val controller = TranslationController(engine, this, cache)

        controller.submit(request())
        controller.submit(request(chapterUrl = nextUrl))
        advanceUntilIdle()

        assertEquals(0, engine.requests.count { it.chapterUrl == CHAPTER_URL })
        assertEquals(ChapterTranslationStatus.Ready, controller.state.value[nextUrl]?.status)
        assertEquals(0, cache.failures)
    }

    @Test
    fun `concurrent submits never start two workers`() = runTest {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val engine = FakeEngine { request ->
            val current = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            yield()
            active.decrementAndGet()
            ok(request, request.chapterUrl)
        }
        val controller = TranslationController(engine, this)

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(50) { index ->
                    launch {
                        controller.submit(
                            request(chapterUrl = "https://example.com/book/chapter-$index"),
                        )
                    }
                }
            }
        }
        advanceUntilIdle()

        assertEquals(1, peak.get())
        assertEquals(50, controller.state.value.size)
        assertTrue(controller.state.value.values.all { it.status == ChapterTranslationStatus.Ready })
        assertEquals(50, engine.requests.size)
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

    private class CancellingLoadCache(
        private val chapterUrl: String,
    ) : ChapterTranslationCache {
        var failures = 0

        override suspend fun load(request: TranslationRequest): Map<Int, String>? {
            if (request.chapterUrl == chapterUrl) throw CancellationException("cache cancelled")
            return null
        }

        override suspend fun loadLatest(request: TranslationRequest): Map<Int, String>? = null

        override suspend fun loadSameConfig(request: TranslationRequest): Map<Int, String>? = null

        override suspend fun save(request: TranslationRequest, result: ChapterTranslationResult) =
            Unit

        override suspend fun markFailed(request: TranslationRequest) {
            failures++
        }
    }

    private class CancellingCache : ChapterTranslationCache {
        override suspend fun load(request: TranslationRequest): Map<Int, String>? =
            throw CancellationException("preload cancelled")

        override suspend fun loadLatest(request: TranslationRequest): Map<Int, String>? = null

        override suspend fun loadSameConfig(request: TranslationRequest): Map<Int, String>? = null

        override suspend fun save(request: TranslationRequest, result: ChapterTranslationResult) =
            Unit

        override suspend fun markFailed(request: TranslationRequest) = Unit
    }

    private class PromptSwitchCache : ChapterTranslationCache {
        var failures = 0

        override suspend fun load(request: TranslationRequest): Map<Int, String>? = null

        // A row made with another prompt: it must never be served as this one.
        override suspend fun loadLatest(request: TranslationRequest): Map<Int, String>? =
            mapOf(1 to "other-prompt")

        override suspend fun loadSameConfig(request: TranslationRequest): Map<Int, String>? = null

        override suspend fun save(request: TranslationRequest, result: ChapterTranslationResult) =
            Unit

        override suspend fun markFailed(request: TranslationRequest) {
            failures++
        }
    }

    /** Captures the launched coroutines so a test can stop the worker. */
    private class JobCapturingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        private val captured = LinkedHashSet<Job>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            context[Job]?.let { captured += it }
            delegate.dispatch(context, block)
        }

        /** The controller launches its worker before anything else. */
        val workerJob: Job get() = captured.first()
    }

    private class FallbackCache(
        private val exact: Map<Int, String>?,
        var latest: Map<Int, String>?,
    ) : ChapterTranslationCache {
        var failures = 0
        var saves = 0
        var sameConfigLoads = 0
        override suspend fun load(request: TranslationRequest) = exact
        override suspend fun loadLatest(request: TranslationRequest) = latest
        override suspend fun loadSameConfig(request: TranslationRequest): Map<Int, String>? {
            sameConfigLoads++
            return latest
        }
        override suspend fun save(request: TranslationRequest, result: ChapterTranslationResult) {
            saves++
        }
        override suspend fun markFailed(request: TranslationRequest) {
            failures++
        }
    }

    private class FakeCache(
        private val loaded: Map<Int, String>?,
        private val latest: Map<Int, String>? = null,
    ) : ChapterTranslationCache {
        var saves = 0
        var loads = 0
        var failures = 0
        var lastSaved: Map<Int, String>? = null

        override suspend fun load(request: TranslationRequest): Map<Int, String>? {
            loads++
            return loaded
        }

        override suspend fun loadLatest(request: TranslationRequest): Map<Int, String>? = latest

        override suspend fun loadSameConfig(request: TranslationRequest): Map<Int, String>? = latest

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
