package dev.mirutime.app.features.downloads.data

import dev.mirutime.app.features.translation.data.DownloadTranslationQueue
import dev.mirutime.app.features.translation.data.FakeTranslationQueueStore
import dev.mirutime.app.features.translation.data.testChapterConfig
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadTranslateEnqueueTest {
    private lateinit var root: java.io.File
    private val bookUrl = "https://example.test/book/1"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("miru-translate-enqueue").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun engine(
        translationStore: FakeTranslationQueueStore = FakeTranslationQueueStore(),
        configured: Boolean = true,
        scheduled: MutableList<Long> = mutableListOf(),
    ) = DownloadEngine(
        store = FakeDownloadStore(),
        provider = FakeProvider(),
        files = LocalDownloadFileStore(root),
        scheduler = FakeScheduler(),
        translationQueue = DownloadTranslationQueue(
            store = translationStore,
            configFor = { if (configured) testChapterConfig() else null },
            now = { 1L },
        ),
        scheduleTranslation = { scheduled += it },
    ) to translationStore

    @Test
    fun `translate after download writes PENDING intents and schedules the worker`() = runTest {
        val scheduled = mutableListOf<Long>()
        val (engine, store) = engine(scheduled = scheduled)

        val result = engine.enqueueNow(bookUrl, listOf(1, 2), translateAfter = true)

        assertEquals(2, result.queued)
        assertTrue(result.translationRequested)
        assertTrue(result.translationConfigured)
        assertEquals(2, result.translationsQueued)
        assertEquals(2, store.rows.size)
        assertTrue(store.rows.all { it.status == DownloadTranslationQueue.STATUS_PENDING })
        assertTrue(store.rows.none { it.status == DownloadTranslationQueue.STATUS_COMPLETE })
        assertEquals(listOf(1L), scheduled)
    }

    @Test
    fun `duplicate download plus translate keeps one intent per chapter`() = runTest {
        val (engine, store) = engine()

        engine.enqueueNow(bookUrl, listOf(1), translateAfter = true)
        val second = engine.enqueueNow(bookUrl, listOf(1), translateAfter = true)

        assertEquals(1, store.rows.size)
        assertEquals(1, second.translationsAlreadyQueued)
        assertEquals(0, second.translationsQueued)
    }

    @Test
    fun `page chapters never get a translation intent`() = runTest {
        val translationStore = FakeTranslationQueueStore()
        translationStore.pageChapterIds += 2L
        val (engine, _) = engine(translationStore)

        val result = engine.enqueueNow(bookUrl, listOf(2), translateAfter = true)

        assertTrue(translationStore.rows.isEmpty())
        assertEquals(1, result.translationsSkipped)
    }

    @Test
    fun `unconfigured translation still downloads and reports it`() = runTest {
        val (engine, store) = engine(configured = false)

        val result = engine.enqueueNow(bookUrl, listOf(1), translateAfter = true)

        assertEquals(1, result.queued)
        assertTrue(result.translationRequested)
        assertFalse(result.translationConfigured)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `plain download does not write translation intents`() = runTest {
        val (engine, store) = engine()

        engine.enqueueNow(bookUrl, listOf(1))

        assertTrue(store.rows.isEmpty())
    }
}
