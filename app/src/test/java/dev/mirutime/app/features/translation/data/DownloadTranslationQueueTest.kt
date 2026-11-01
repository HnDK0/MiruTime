package dev.mirutime.app.features.translation.data

import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.data.library.ChapterBodyEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTranslationQueueTest {
    private val config = testChapterConfig()

    private fun queue(store: FakeTranslationQueueStore) = DownloadTranslationQueue(
        store = store,
        configFor = { config },
        now = { 10L },
    )

    @Test
    fun `enqueue writes PENDING rows and never marks them translated`() = runTest {
        val store = FakeTranslationQueueStore()
        val result = queue(store).enqueue(
            store.bookRow!!.url,
            store.chapterRows,
        )

        assertTrue(result.configured)
        assertEquals(3, result.queued)
        assertEquals(3, store.rows.size)
        assertTrue(store.rows.all { it.status == DownloadTranslationQueue.STATUS_PENDING })
        assertTrue(store.rows.none { it.status == DownloadTranslationQueue.STATUS_COMPLETE })
        assertEquals(
            ChapterTranslationPlanner.PENDING_SOURCE_HASH,
            store.rows.first().sourceHash,
        )
        assertEquals("GOOGLE_FREE", store.rows.first().provider)
        assertEquals("ru", store.rows.first().target)
    }

    @Test
    fun `duplicate enqueue is idempotent`() = runTest {
        val store = FakeTranslationQueueStore()
        val queue = queue(store)

        queue.enqueue(store.bookRow!!.url, store.chapterRows)
        val second = queue.enqueue(store.bookRow!!.url, store.chapterRows)

        assertEquals(0, second.queued)
        assertEquals(3, second.alreadyQueued)
        assertEquals(3, store.rows.size)
    }

    @Test
    fun `page chapters are skipped`() = runTest {
        val store = FakeTranslationQueueStore()
        store.pageChapterIds += 2L

        val result = queue(store).enqueue(store.bookRow!!.url, store.chapterRows)

        assertEquals(2, result.queued)
        assertEquals(1, result.skippedPages)
        assertEquals(listOf(1L, 3L), store.rows.map { it.chapterId }.sorted())
    }

    @Test
    fun `complete translations are not duplicated`() = runTest {
        val store = FakeTranslationQueueStore()
        store.addRow(
            chapterId = 1L,
            status = DownloadTranslationQueue.STATUS_COMPLETE,
            sourceHash = "real-hash",
        )

        val result = queue(store).enqueue(store.bookRow!!.url, listOf(store.chapterRows.first()))

        assertEquals(1, result.alreadyTranslated)
        assertEquals(1, store.rows.size)
        assertEquals(DownloadTranslationQueue.STATUS_COMPLETE, store.rows.single().status)
    }

    @Test
    fun `unconfigured translation writes no intent`() = runTest {
        val store = FakeTranslationQueueStore()
        val result = DownloadTranslationQueue(store, configFor = { null })
            .enqueue(store.bookRow!!.url, store.chapterRows)

        assertFalse(result.configured)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `switching config cancels the old pending intent`() = runTest {
        val store = FakeTranslationQueueStore()
        queue(store).enqueue(store.bookRow!!.url, listOf(store.chapterRows.first()))
        val pending = store.rows.single()

        val other = config.copy(mode = config.mode.copy(target = "de"))
        DownloadTranslationQueue(store, configFor = { other }).enqueue(
            store.bookRow!!.url,
            listOf(store.chapterRows.first()),
        )

        assertEquals(
            DownloadTranslationQueue.STATUS_CANCELLED,
            store.rows.first { it.target == "ru" }.status,
        )
        assertTrue(store.rows.any { it.target == "de" && it.status == DownloadTranslationQueue.STATUS_PENDING })
        assertTrue(pending.translationId != 0L)
    }
}

class DownloadTranslationRunnerTest {
    private val config = testChapterConfig()

    private fun setup(
        chapters: Int = 1,
    ): Triple<FakeTranslationQueueStore, FakeFileStore, FakeTranslationEngine> {
        val store = FakeTranslationQueueStore(
            chapters = (1..chapters).map { translationTestChapter(it.toLong(), it) },
        )
        val files = FakeFileStore()
        val engine = FakeTranslationEngine()
        return Triple(store, files, engine)
    }

    private fun runner(
        store: FakeTranslationQueueStore,
        files: FakeFileStore,
        engine: FakeTranslationEngine,
        delays: MutableList<Long> = mutableListOf(),
        attempt: Int = 1,
    ) = DownloadTranslationRunner(
        store = store,
        engine = engine,
        cache = FakeTranslationCache(store),
        files = files,
        configFor = { config },
        now = { 20L },
        chapterDelayMs = { 1_000L },
        sleep = { delays += it },
        chapterTimeout = kotlin.time.Duration.parse("5s"),
    ) to attempt

    private fun addDownloadedBody(
        store: FakeTranslationQueueStore,
        files: FakeFileStore,
        chapterId: Long,
        html: String = testDocumentHtml(),
    ): ChapterBodyEntity {
        val path = files.put(html)
        val body = ChapterBodyEntity(
            chapterId = chapterId,
            format = "html",
            path = path,
            bytes = html.length.toLong(),
            sha256 = "sha",
            fetchedAt = 1,
        )
        store.bodies[chapterId] = body
        captureActualHash(store, chapterId, html)
        return body
    }

    private fun captureActualHash(
        store: FakeTranslationQueueStore,
        chapterId: Long,
        html: String,
    ): String {
        val chapter = store.chapterRows.first { it.chapterId == chapterId }
        val request = ChapterTranslationPlanner.requestFromHtml(
            bookUrl = store.bookRow!!.url,
            chapterUrl = chapter.url,
            html = html,
            config = config,
        )!!
        return request.revision.sourceHash
    }

    @Test
    fun `downloaded body translates, saves blocks and completes the row`() = runTest {
        val (store, files, engine) = setup()
        store.addRow(1L, DownloadTranslationQueue.STATUS_PENDING)
        addDownloadedBody(store, files, 1L)

        val outcome = runner(store, files, engine).first.run(1, 1)

        assertEquals(1, outcome.translated)
        assertEquals(0, outcome.failed)
        assertEquals(1, engine.requests.size)
        assertEquals(3, engine.requests.single().units.size)
        val row = store.rows.single()
        assertEquals(DownloadTranslationQueue.STATUS_COMPLETE, row.status)
        assertTrue(store.blocks.getValue(row.translationId).isNotEmpty())
    }

    @Test
    fun `missing body waits without calling the provider`() = runTest {
        val (store, files, engine) = setup()
        store.addRow(1L, DownloadTranslationQueue.STATUS_PENDING)

        val outcome = runner(store, files, engine).first.run(1, 1)

        assertEquals(1, outcome.waiting)
        assertEquals(0, engine.requests.size)
        assertEquals(DownloadTranslationQueue.STATUS_PENDING, store.rows.single().status)
    }

    @Test
    fun `paused download does not translate`() = runTest {
        val (store, files, engine) = setup()
        store.addRow(1L, DownloadTranslationQueue.STATUS_PENDING)
        addDownloadedBody(store, files, 1L)
        store.downloadStates[1L] = "PAUSED"

        val outcome = runner(store, files, engine).first.run(1, 1)

        assertEquals(1, outcome.waiting)
        assertEquals(0, engine.requests.size)
    }

    @Test
    fun `cached translation makes no provider call`() = runTest {
        val (store, files, engine) = setup()
        val html = testDocumentHtml()
        val hash = captureActualHash(store, 1L, html)
        store.addRow(1L, DownloadTranslationQueue.STATUS_PENDING)
        val cachedRow = store.addRow(1L, DownloadTranslationQueue.STATUS_COMPLETE, sourceHash = hash)
        store.blocks[cachedRow.translationId] = mutableListOf()
        addDownloadedBody(store, files, 1L, html)
        store.blocks[cachedRow.translationId] = mutableListOf(
            dev.mirutime.data.library.TranslationBlockEntity(
                translationId = cachedRow.translationId,
                blockIndex = 0,
                sourceTextHash = "h",
                translatedText = "t",
            ),
        )

        val outcome = runner(store, files, engine).first.run(1, 1)

        assertEquals(1, outcome.cached)
        assertEquals(0, engine.requests.size)
        assertEquals(0, store.rows.count { it.status == DownloadTranslationQueue.STATUS_PENDING })
    }

    @Test
    fun `terminal failure marks FAILED`() = runTest {
        val (store, files, engine) = setup()
        store.addRow(1L, DownloadTranslationQueue.STATUS_PENDING)
        addDownloadedBody(store, files, 1L)
        engine.outcome = { Outcome.Err(PluginError.ScriptError("bad response")) }

        val outcome = runner(store, files, engine).first.run(1, 1)

        assertEquals(1, outcome.failed)
        assertEquals(DownloadTranslationQueue.STATUS_FAILED, store.rows.single().status)
    }

    @Test
    fun `network failure retries then fails on the last attempt`() = runTest {
        val (store, files, engine) = setup()
        store.addRow(1L, DownloadTranslationQueue.STATUS_PENDING)
        addDownloadedBody(store, files, 1L)
        engine.outcome = { Outcome.Err(PluginError.Network("offline")) }
        val runner = runner(store, files, engine).first

        val first = runner.run(1, 1)
        assertEquals(1, first.retryable)
        assertEquals(DownloadTranslationQueue.STATUS_PENDING, store.rows.single().status)

        val last = runner.run(1, DownloadTranslationRunner.MAX_ATTEMPTS)
        assertEquals(1, last.failed)
        assertEquals(DownloadTranslationQueue.STATUS_FAILED, store.rows.single().status)
    }

    @Test
    fun `second run after process death does not duplicate completed work`() = runTest {
        val (store, files, engine) = setup()
        store.addRow(1L, DownloadTranslationQueue.STATUS_PENDING)
        addDownloadedBody(store, files, 1L)
        val runner = runner(store, files, engine).first

        runner.run(1, 1)
        val second = runner.run(1, 2)

        assertEquals(0, second.translated)
        assertEquals(1, engine.requests.size)
        assertEquals(1, store.rows.count { it.status == DownloadTranslationQueue.STATUS_COMPLETE })
    }

    @Test
    fun `per chapter delay runs between chapters only`() = runTest {
        val (store, files, engine) = setup(chapters = 2)
        store.addRow(1L, DownloadTranslationQueue.STATUS_PENDING)
        store.addRow(2L, DownloadTranslationQueue.STATUS_PENDING)
        addDownloadedBody(store, files, 1L)
        addDownloadedBody(store, files, 2L)
        val delays = mutableListOf<Long>()

        runner(store, files, engine, delays = delays).first.run(1, 1)

        assertEquals(listOf(1_000L), delays)
        assertEquals(2, engine.requests.size)
    }
}
