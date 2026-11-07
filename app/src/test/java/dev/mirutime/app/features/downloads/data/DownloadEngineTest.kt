package dev.mirutime.app.features.downloads.data

import dev.mirutime.app.features.book.data.ChapterDownloadException
import dev.mirutime.app.features.book.data.ChapterFailureKind
import dev.mirutime.core.api.PluginError
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadEngineTest {
    private lateinit var root: File
    private lateinit var store: FakeDownloadStore
    private lateinit var provider: FakeProvider
    private lateinit var scheduler: FakeScheduler
    private lateinit var files: LocalDownloadFileStore
    private val logs = mutableListOf<String>()

    private val bookUrl = "https://example.test/book/1"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("miru-downloads").toFile()
        store = FakeDownloadStore()
        provider = FakeProvider()
        scheduler = FakeScheduler()
        files = LocalDownloadFileStore(root)
        logs.clear()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun engine(
        pageFetcher: suspend (String, String) -> ByteArray? = { _, _ -> null },
        chapterDelayMs: () -> Long = { 0L },
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        fileStore: DownloadFileStore = files,
    ) = DownloadEngine(
        store = store,
        provider = provider,
        files = fileStore,
        scheduler = scheduler,
        fetchPage = pageFetcher,
        clock = { 100L },
        log = { logs += it },
        chapterDelayMs = chapterDelayMs,
        sleep = sleep,
    )

    @Test
    fun `a book deleted during fetch is not committed`() = runTest {
        provider.contents[1] = FakeProvider.Update("<p>Hello</p>")
        provider.onContent = { store.bookRow = null }
        val engine = engine()

        engine.enqueueNow(bookUrl, listOf(1))
        val outcome = engine.run(1)

        assertEquals(0, outcome.completed)
        assertTrue(store.bodies.isEmpty())
        assertTrue(store.downloaded.isEmpty())
    }

    @Test
    fun `text download writes a body and never fakes completion`() = runTest {
        provider.contents[1] = FakeProvider.Update("<p>Hello</p>")
        val engine = engine()

        val queued = engine.enqueueNow(bookUrl, listOf(1, 1, 9))
        assertEquals(1, queued.queued)
        assertEquals(1, queued.unknown)
        assertEquals(listOf(1L), scheduler.enqueued)

        val outcome = engine.run(1)
        assertEquals(1, outcome.completed)
        assertEquals(0, outcome.failed)

        val task = store.tasksById.values.single()
        assertEquals(DownloadState.COMPLETED.wire, task.state)
        val body = store.bodies.values.single()
        assertTrue(body.bytes > 0)
        assertTrue(File(body.path).isFile)
        assertTrue(store.downloaded.contains(1L))
    }

    @Test
    fun `missing content fails the task and leaves no body row`() = runTest {
        val engine = engine()

        engine.enqueueNow(bookUrl, listOf(2))
        val outcome = engine.run(1)

        assertEquals(1, outcome.failed)
        val task = store.tasksById.values.single()
        assertEquals(DownloadState.FAILED.wire, task.state)
        assertNotNull(task.error)
        assertTrue(store.bodies.isEmpty())
        assertTrue(store.downloaded.isEmpty())
        assertFalse(outcome.shouldRetry)
    }

    @Test
    fun `already completed work is deduped on a second enqueue`() = runTest {
        provider.contents[1] = FakeProvider.Update("<p>One</p>")
        val engine = engine()
        engine.enqueueNow(bookUrl, listOf(1))
        engine.run(1)

        val again = engine.enqueueNow(bookUrl, listOf(1, 2))

        assertEquals(1, again.queued)
        assertEquals(1, again.skipped)
        assertEquals(2, store.tasksById.size)
    }

    @Test
    fun `manga download stores every page with progress`() = runTest {
        store.bookRow = testBook(contentType = "Manga")
        provider.pageUrls[1] = listOf("https://cdn.test/1.jpg", "https://cdn.test/2.png")
        val engine = engine(pageFetcher = { _, url -> url.toByteArray() })

        engine.enqueueNow(bookUrl, listOf(1))
        val outcome = engine.run(1)

        assertEquals(1, outcome.completed)
        val page = store.pages.values.single()
        val paths = files.pagePaths(page.pagePathsJson)
        assertEquals(2, paths.size)
        assertTrue(paths.all { File(it).isFile })
        assertEquals(DownloadState.COMPLETED.wire, store.tasksById.values.single().state)
    }

    @Test
    fun `failed manga page leaves no chapter page row`() = runTest {
        store.bookRow = testBook(contentType = "Manga")
        provider.pageUrls[1] = listOf("https://cdn.test/1.jpg")
        val engine = engine(pageFetcher = { _, _ -> null })

        engine.enqueueNow(bookUrl, listOf(1))
        val outcome = engine.run(1)

        assertEquals(1, outcome.failed)
        assertTrue(store.pages.isEmpty())
        assertTrue(store.downloaded.isEmpty())
    }

    @Test
    fun `pause stops work and resume re-queues the same rows`() = runTest {
        provider.contents[1] = FakeProvider.Update("<p>One</p>")
        val engine = engine()
        engine.enqueueNow(bookUrl, listOf(1, 2))

        engine.pauseNow(1)
        assertTrue(store.tasksById.values.all { it.state == DownloadState.PAUSED.wire })
        assertEquals(listOf(1L), scheduler.cancelled)
        assertEquals(0, engine.run(1).completed)

        engine.resumeNow(1)
        assertTrue(store.tasksById.values.all { it.state == DownloadState.QUEUED.wire })
        assertEquals(1, engine.run(1).completed)
    }

    @Test
    fun `delete chapters removes cache-on-read output without a task row`() = runTest {
        val engine = engine()
        val chapter = store.chapterRows.first { it.idx == 1 }
        val stored = files.writeText(1, chapter.chapterId, "<p>cached</p>")
        store.bodies[chapter.chapterId] = dev.mirutime.data.library.ChapterBodyEntity(
            chapterId = chapter.chapterId,
            format = "html",
            path = stored.path,
            bytes = stored.bytes,
            sha256 = stored.sha256,
            fetchedAt = 1,
        )
        store.pages[chapter.chapterId] = dev.mirutime.data.library.ChapterPageEntity(
            chapterId = chapter.chapterId,
            format = "images",
            pagePathsJson = "[]",
        )
        store.downloaded += chapter.chapterId

        val result = engine.deleteChaptersNow(bookUrl, setOf(1))

        assertEquals(1, result.deleted)
        assertEquals(0, result.failed)
        assertTrue(store.bodies.isEmpty())
        assertTrue(store.pages.isEmpty())
        assertTrue(store.downloaded.isEmpty())
        assertFalse(File(stored.path).exists())
        assertTrue(store.statusSnapshot(1)!!.downloadedIndexes.isEmpty())
        assertEquals(0, engine.deleteChaptersNow(bookUrl, setOf(1)).deleted)
    }

    @Test
    fun `delete chapters removes task rows and counts a chapter once`() = runTest {
        val engine = engine()
        val chapter = store.chapterRows.first { it.idx == 1 }
        val stored = files.writeText(1, chapter.chapterId, "<p>cached</p>")
        store.bodies[chapter.chapterId] = dev.mirutime.data.library.ChapterBodyEntity(
            chapterId = chapter.chapterId,
            format = "html",
            path = stored.path,
            bytes = stored.bytes,
            sha256 = stored.sha256,
            fetchedAt = 1,
        )
        store.downloaded += chapter.chapterId
        store.addTask(chapter.chapterId, DownloadState.COMPLETED)

        val result = engine.deleteChaptersNow(bookUrl, setOf(1))

        assertEquals(1, result.deleted)
        assertTrue(store.bodies.isEmpty())
        assertTrue(store.tasksById.isEmpty())
        assertFalse(File(stored.path).exists())
        assertEquals(0, engine.deleteChaptersNow(bookUrl, setOf(1)).deleted)
    }

    @Test
    fun `delete chapters ignores the un-downloaded majority of a large selection`() = runTest {
        val chapters = (1..1000).map { testChapter(it.toLong(), idx = it) }
        store = FakeDownloadStore(bookRow = testBook(totalChapters = 1000), chapters = chapters)
        val engine = engine()
        val target = chapters.first { it.idx == 700 }
        val stored = files.writeText(1, target.chapterId, "<p>cached</p>")
        store.bodies[target.chapterId] = dev.mirutime.data.library.ChapterBodyEntity(
            chapterId = target.chapterId,
            format = "html",
            path = stored.path,
            bytes = stored.bytes,
            sha256 = stored.sha256,
            fetchedAt = 1,
        )
        store.pages[target.chapterId] = dev.mirutime.data.library.ChapterPageEntity(
            chapterId = target.chapterId,
            format = "images",
            pagePathsJson = "[]",
        )

        val result = engine.deleteChaptersNow(bookUrl, (1..1000).toSet())

        assertEquals(1, result.deleted)
        // Only the cached chapter's rows were touched, not the whole selection.
        assertEquals(1, store.deleteThreads.size)
        assertTrue(store.bodies.isEmpty())
        assertTrue(store.pages.isEmpty())
        assertFalse(File(stored.path).exists())
        assertEquals(700..700, provider.chapterDownloadedCalls.single().second)
        assertEquals(0, engine.deleteChaptersNow(bookUrl, (1..1000).toSet()).deleted)
        assertEquals(1, provider.chapterDownloadedCalls.size)
    }

    @Test
    fun `delete chapters runs row and file deletion off the caller thread`() = runTest {
        val engine = engine()
        val chapter = store.chapterRows.first { it.idx == 1 }
        val stored = files.writeText(1, chapter.chapterId, "<p>cached</p>")
        store.bodies[chapter.chapterId] = dev.mirutime.data.library.ChapterBodyEntity(
            chapterId = chapter.chapterId,
            format = "html",
            path = stored.path,
            bytes = stored.bytes,
            sha256 = stored.sha256,
            fetchedAt = 1,
        )
        val callerThread = Thread.currentThread().name

        engine.deleteChaptersNow(bookUrl, setOf(1))

        assertTrue(store.deleteThreads.isNotEmpty())
        assertFalse(store.deleteThreads.any { it == callerThread })
        assertFalse(File(stored.path).exists())
    }

    @Test
    fun `partial delete failure still invalidates provider status`() = runTest {
        val engine = engine()
        val first = store.chapterRows.first { it.idx == 1 }
        val second = store.chapterRows.first { it.idx == 2 }
        listOf(first, second).forEach { chapter ->
            val stored = files.writeText(1, chapter.chapterId, "<p>cached</p>")
            store.bodies[chapter.chapterId] = dev.mirutime.data.library.ChapterBodyEntity(
                chapterId = chapter.chapterId,
                format = "html",
                path = stored.path,
                bytes = stored.bytes,
                sha256 = stored.sha256,
                fetchedAt = 1,
            )
        }
        store.failDeleteBodyChapterId = first.chapterId

        val result = engine.deleteChaptersNow(bookUrl, setOf(1, 2, 99))

        assertEquals(1, result.deleted)
        assertEquals(1, result.failed)
        assertTrue(store.bodies.containsKey(first.chapterId))
        assertFalse(store.bodies.containsKey(second.chapterId))
        assertEquals(1, provider.chapterDownloadedCalls.size)
        assertFalse(provider.chapterDownloadedCalls.single().third)
        assertTrue(logs.any { it.contains("delete-chapters-failed") })

        // Retry the same selection: already-deleted chapters are not counted
        // again, only the failed one is removed.
        store.failDeleteBodyChapterId = null
        val retry = engine.deleteChaptersNow(bookUrl, setOf(1, 2, 99))
        assertEquals(1, retry.deleted)
        assertEquals(0, retry.failed)
        assertTrue(store.bodies.isEmpty())
    }

    @Test
    fun `concurrent deletes count a chapter once and stay idempotent`() = runTest {
        val chapter = store.chapterRows.first { it.idx == 1 }
        val stored = files.writeText(1, chapter.chapterId, "<p>cached</p>")
        store.bodies[chapter.chapterId] = dev.mirutime.data.library.ChapterBodyEntity(
            chapterId = chapter.chapterId,
            format = "html",
            path = stored.path,
            bytes = stored.bytes,
            sha256 = stored.sha256,
            fetchedAt = 1,
        )
        store.downloaded += chapter.chapterId
        store.addTask(chapter.chapterId, DownloadState.COMPLETED)
        // Both calls read the pre-delete snapshot before either one deletes.
        val barrier = CyclicBarrier(2)
        store.chaptersGate = { barrier.await(5, TimeUnit.SECONDS) }
        val engine = engine()

        val results = listOf(
            async(Dispatchers.IO) { engine.deleteChaptersNow(bookUrl, setOf(1)) },
            async(Dispatchers.IO) { engine.deleteChaptersNow(bookUrl, setOf(1)) },
        ).awaitAll()

        assertEquals(1, results.sumOf { it.deleted })
        assertTrue(results.all { it.failed == 0 })
        assertTrue(store.bodies.isEmpty())
        assertTrue(store.pages.isEmpty())
        assertTrue(store.tasksById.isEmpty())
        assertFalse(File(stored.path).exists())
        assertEquals(0, engine.deleteChaptersNow(bookUrl, setOf(1)).deleted)
    }

    @Test
    fun `failed row delete keeps its file until the retry succeeds`() = runTest {
        val chapter = store.chapterRows.first { it.idx == 1 }
        val stored = files.writeText(1, chapter.chapterId, "<p>cached</p>")
        store.bodies[chapter.chapterId] = dev.mirutime.data.library.ChapterBodyEntity(
            chapterId = chapter.chapterId,
            format = "html",
            path = stored.path,
            bytes = stored.bytes,
            sha256 = stored.sha256,
            fetchedAt = 1,
        )
        store.failDeleteBodyChapterId = chapter.chapterId
        val engine = engine()

        val failed = engine.deleteChaptersNow(bookUrl, setOf(1))
        assertEquals(0, failed.deleted)
        assertEquals(1, failed.failed)
        // The row still references the file, so the file must survive.
        assertTrue(store.bodies.containsKey(chapter.chapterId))
        assertTrue(File(stored.path).isFile)

        store.failDeleteBodyChapterId = null
        val retried = engine.deleteChaptersNow(bookUrl, setOf(1))
        assertEquals(1, retried.deleted)
        assertEquals(0, retried.failed)
        assertTrue(store.bodies.isEmpty())
        assertFalse(File(stored.path).exists())
    }

    @Test
    fun `a fresh download cannot commit a row whose file a stale delete removes`() = runTest {
        val chapter = store.chapterRows.first { it.idx == 1 }
        val old = files.writeText(1, chapter.chapterId, "<p>old</p>")
        store.bodies[chapter.chapterId] = dev.mirutime.data.library.ChapterBodyEntity(
            chapterId = chapter.chapterId,
            format = "html",
            path = old.path,
            bytes = old.bytes,
            sha256 = old.sha256,
            fetchedAt = 1,
        )
        store.downloaded += chapter.chapterId
        provider.contents[1] = FakeProvider.Update("<p>new</p>")

        val deleteStarted = CountDownLatch(1)
        val allowDelete = CountDownLatch(1)
        val writeDone = CountDownLatch(1)
        val gated = GatedFileStore(files, chapter.chapterId, deleteStarted, allowDelete, writeDone)
        val engine = engine(fileStore = gated)

        val deleteJob = async(Dispatchers.IO) { engine.deleteChaptersNow(bookUrl, setOf(1)) }
        assertTrue(deleteStarted.await(5, TimeUnit.SECONDS))

        val downloadJob = async(Dispatchers.IO) {
            engine.enqueueNow(bookUrl, listOf(1))
            engine.run(1)
        }
        // A download that can write while delete is between rows and files is
        // exactly the old inconsistency window; give it time to hit it.
        writeDone.await(500, TimeUnit.MILLISECONDS)
        allowDelete.countDown()
        deleteJob.await()
        downloadJob.await()

        val body = store.bodies[chapter.chapterId]
        assertNotNull(body)
        assertTrue(File(body!!.path).isFile)
    }

    @Test
    fun `cancel removes partial output and marks the task cancelled`() = runTest {
        val engine = engine()
        engine.enqueueNow(bookUrl, listOf(1))
        val chapter = store.chapterRows.first { it.idx == 1 }
        val stored = files.writeText(1, chapter.chapterId, "<p>partial</p>")
        store.bodies[chapter.chapterId] = dev.mirutime.data.library.ChapterBodyEntity(
            chapterId = chapter.chapterId,
            format = "html",
            path = stored.path,
            bytes = stored.bytes,
            sha256 = stored.sha256,
            fetchedAt = 1,
        )
        store.downloaded += chapter.chapterId

        engine.cancelNow(1)

        assertEquals(DownloadState.CANCELLED.wire, store.tasksById.values.single().state)
        assertTrue(store.bodies.isEmpty())
        assertFalse(File(stored.path).exists())
        assertTrue(store.downloaded.isEmpty())
        assertTrue(scheduler.cancelled.contains(1L))
    }

    @Test
    fun `a fresh engine resumes tasks persisted by the previous process`() = runTest {
        provider.contents[1] = FakeProvider.Update("<p>One</p>")
        engine().enqueueNow(bookUrl, listOf(1))
        assertEquals(1, store.tasksById.size)

        // Process death: new engine instance, same Room rows and files.
        val restarted = engine()
        val outcome = restarted.run(1)

        assertEquals(1, outcome.completed)
        assertEquals(DownloadState.COMPLETED.wire, store.tasksById.values.single().state)
    }

    @Test
    fun `failed task can be retried after the source recovers`() = runTest {
        val engine = engine()
        engine.enqueueNow(bookUrl, listOf(1))
        engine.run(1)
        assertEquals(DownloadState.FAILED.wire, store.tasksById.values.single().state)

        provider.contents[1] = FakeProvider.Update("<p>Recovered</p>")
        engine.retryNow(1)
        assertEquals(DownloadState.QUEUED.wire, store.tasksById.values.single().state)
        engine.run(1)
        assertEquals(DownloadState.COMPLETED.wire, store.tasksById.values.single().state)
    }

    @Test
    fun `local books are never download targets`() = runTest {
        store.bookRow = testBook(url = "local://book/1")

        val result = engine().enqueueNow("local://book/1", listOf(1))

        assertEquals(EnqueueFailure.LOCAL_BOOK, result.reason)
        assertTrue(store.tasksById.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `large enqueue upserts in bounded chunks`() = runTest {
        val chapters = (1..450).map { testChapter(it.toLong(), idx = it) }
        store = FakeDownloadStore(bookRow = testBook(totalChapters = 450), chapters = chapters)

        engine().enqueueNow(bookUrl, null)

        assertEquals(listOf(200, 200, 50), store.saveTaskChunks)
        assertEquals(450, store.tasksById.size)
    }

    @Test
    fun `one middle chapter failure does not abort the remaining queue`() = runTest {
        val chapters = (1..100).map { testChapter(it.toLong(), idx = it) }
        store = FakeDownloadStore(bookRow = testBook(totalChapters = 100), chapters = chapters)
        (1..100).forEach { provider.contents[it] = FakeProvider.Update("<p>$it</p>") }
        provider.failures[50] = IllegalStateException("boom https://secret.test/x?token=1")
        val engine = engine()

        engine.enqueueNow(bookUrl, (1..100).toList())
        val outcome = engine.run(1, attempt = 1)

        assertEquals(99, outcome.completed)
        assertEquals(1, outcome.failed)
        assertEquals(0, outcome.retryable)
        assertEquals(0, outcome.pending)
        val failed = store.tasksById.values.first { it.chapterId == 50L }
        assertEquals(DownloadState.FAILED.wire, failed.state)
        assertTrue(store.tasksById.values.count { it.state == DownloadState.COMPLETED.wire } == 99)
        val summary = logs.last()
        assertTrue(summary.contains("completed=99"))
        assertTrue(summary.contains("failed=1"))
        assertTrue(summary.contains("retryable=0"))
        val failureLog = logs.first { it.contains("chapterId=50 ") && it.contains("state=FAILED") }
        // Shared redaction: the URL becomes host+path, the query never survives.
        assertTrue(failureLog.contains("secret.test/x"))
        assertFalse(failureLog.contains("https://"))
        assertFalse(failureLog.contains("token=1"))
    }

    @Test
    fun `typed chapter failure stores the shared report, not the raw cause`() = runTest {
        provider.failures[1] = ChapterDownloadException(
            ChapterFailureKind.AUTH_REQUIRED,
            retryable = false,
            message = "source=test op=download HTTP 403 Forbidden",
            pluginError = PluginError.Network(
                cause = "network",
                status = 403,
                reason = "Forbidden https://site.example/ch/7?token=SECRET",
                host = "site.example",
                path = "/ch/7",
            ),
        )

        engine().apply {
            enqueueNow(bookUrl, listOf(1))
            run(1)
        }

        val error = store.tasksById.values.single().error.orEmpty()
        assertTrue(error, "HTTP 403 Forbidden" in error)
        assertTrue(error, "request=site.example/ch/7" in error)
        assertTrue(error, "code=PLG-403" in error)
        assertFalse(error, "https://" in error)
        assertFalse(error, "SECRET" in error)
    }

    @Test
    fun `cloudflare pauses the book and keeps completed chapters`() = runTest {
        val engine = engine()
        provider.contents[1] = FakeProvider.Update("<p>ok</p>")
        provider.failures[2] = ChapterDownloadException(
            ChapterFailureKind.CLOUDFLARE,
            retryable = false,
            message = "Cloudflare verification required",
        )

        engine.enqueueNow(bookUrl, listOf(1, 2, 3))
        val outcome = engine.run(1, attempt = 1)

        assertEquals(1, outcome.completed)
        assertEquals(0, outcome.failed)
        assertEquals(0, outcome.retryable)
        assertTrue(outcome.needsSourceAction)
        assertFalse(outcome.shouldRetry)
        val byChapter = store.tasksById.values.associateBy { it.chapterId }
        assertEquals(DownloadState.COMPLETED.wire, byChapter[1L]!!.state)
        assertEquals(DownloadState.NEEDS_SOURCE_ACTION.wire, byChapter[2L]!!.state)
        // The unattempted sibling is paused too, not mass-failed.
        assertEquals(DownloadState.NEEDS_SOURCE_ACTION.wire, byChapter[3L]!!.state)
        assertTrue(
            byChapter.values.filter { it.state == DownloadState.NEEDS_SOURCE_ACTION.wire }
                .all { it.error?.contains("required") == true },
        )
        assertTrue(logs.any { it.contains("kind=CLOUDFLARE retryable=false") })
        assertTrue(logs.any { it.contains("result=needs-source-action") })
    }

    @Test
    fun `auth and rate limit also pause the book`() = runTest {
        val engine = engine()
        provider.failures[1] = ChapterDownloadException(
            ChapterFailureKind.AUTH_REQUIRED,
            retryable = false,
            message = "Source login required",
        )
        provider.failures[2] = ChapterDownloadException(
            ChapterFailureKind.RATE_LIMITED,
            retryable = false,
            message = "Rate limited",
        )

        engine.enqueueNow(bookUrl, listOf(1, 2))
        val outcome = engine.run(1, attempt = 1)

        assertTrue(outcome.needsSourceAction)
        assertTrue(
            store.tasksById.values.all {
                it.state == DownloadState.NEEDS_SOURCE_ACTION.wire
            },
        )
    }

    @Test
    fun `retry after source action requeues only unfinished chapters`() = runTest {
        val engine = engine()
        provider.contents[1] = FakeProvider.Update("<p>ok</p>")
        provider.failures[2] = ChapterDownloadException(
            ChapterFailureKind.CLOUDFLARE,
            retryable = false,
            message = "Cloudflare verification required",
        )
        engine.enqueueNow(bookUrl, listOf(1, 2, 3))
        engine.run(1, attempt = 1)

        provider.failures.clear()
        provider.contents[2] = FakeProvider.Update("<p>two</p>")
        provider.contents[3] = FakeProvider.Update("<p>three</p>")
        engine.retryNow(1)

        val byChapter = store.tasksById.values.associateBy { it.chapterId }
        assertEquals(DownloadState.COMPLETED.wire, byChapter[1L]!!.state)
        assertEquals(DownloadState.QUEUED.wire, byChapter[2L]!!.state)
        assertEquals(DownloadState.QUEUED.wire, byChapter[3L]!!.state)
        assertEquals(listOf(1L), scheduler.enqueued.takeLast(1))
        // Retry on a source-blocked book goes through the source-action bridge.
        assertTrue(logs.any { it.contains("action=resume-source bookId=1 queued=2") })

        val outcome = engine.run(1, attempt = 1)
        assertEquals(2, outcome.completed)
        assertEquals(
            3,
            store.tasksById.values.count { it.state == DownloadState.COMPLETED.wire },
        )
    }

    @Test
    fun `source action resume keeps completed chapters and re-arms parked rows`() = runTest {
        val engine = engine()
        provider.contents[1] = FakeProvider.Update("<p>ok</p>")
        provider.failures[2] = ChapterDownloadException(
            ChapterFailureKind.CLOUDFLARE,
            retryable = false,
            message = "Cloudflare verification required",
        )
        engine.enqueueNow(bookUrl, listOf(1, 2, 3))
        engine.run(1, attempt = 1)

        engine.resumeAfterSourceAction(1)

        val byChapter = store.tasksById.values.associateBy { it.chapterId }
        assertEquals(DownloadState.COMPLETED.wire, byChapter[1L]!!.state)
        assertEquals(DownloadState.QUEUED.wire, byChapter[2L]!!.state)
        assertEquals(DownloadState.QUEUED.wire, byChapter[3L]!!.state)
        assertTrue(byChapter[2L]!!.error == null)
        assertTrue(logs.any { it.contains("action=resume-source bookId=1 queued=2") })
        assertEquals(1L, scheduler.enqueued.last())
    }

    @Test
    fun `source action resume re-arms legacy auth failures but not terminal ones`() = runTest {
        store.addTask(
            chapterId = 1,
            state = DownloadState.COMPLETED,
        )
        store.addTask(
            chapterId = 2,
            state = DownloadState.FAILED,
            error = "Cloudflare verification required",
        )
        store.addTask(
            chapterId = 3,
            state = DownloadState.FAILED,
            error = "Chapter not found",
        )
        val engine = engine()

        engine.resumeAfterSourceAction(1)

        val byChapter = store.tasksById.values.associateBy { it.chapterId }
        assertEquals(DownloadState.COMPLETED.wire, byChapter[1L]!!.state)
        assertEquals(DownloadState.QUEUED.wire, byChapter[2L]!!.state)
        assertEquals(DownloadState.FAILED.wire, byChapter[3L]!!.state)
        assertEquals(listOf(1L), scheduler.enqueued)
    }

    @Test
    fun `network failure parks as waiting and retries without failing the book`() = runTest {
        val engine = engine()
        provider.failures[1] = ChapterDownloadException(
            ChapterFailureKind.NETWORK,
            retryable = true,
            message = "Network error while fetching the chapter",
        )

        engine.enqueueNow(bookUrl, listOf(1, 2))
        val first = engine.run(1, attempt = 1)

        assertEquals(0, first.retryable)
        assertTrue(first.waitingNetwork)
        assertTrue(first.shouldRetry)
        val byChapter = store.tasksById.values.associateBy { it.chapterId }
        assertEquals(DownloadState.WAITING_NETWORK.wire, byChapter[1L]!!.state)
        assertEquals(DownloadState.QUEUED.wire, byChapter[2L]!!.state)
        assertTrue(logs.any { it.contains("result=waiting-network") })

        // The bounded WorkManager attempt reruns the parked row directly.
        provider.failures.clear()
        provider.contents[1] = FakeProvider.Update("<p>one</p>")
        provider.contents[2] = FakeProvider.Update("<p>two</p>")
        val second = engine.run(1, attempt = 2)

        assertEquals(2, second.completed)
        assertEquals(0, second.failed)
        assertTrue(
            store.tasksById.values.all { it.state == DownloadState.COMPLETED.wire },
        )
    }

    @Test
    fun `pause and cancel stay responsive while waiting for network`() = runTest {
        val engine = engine()
        provider.failures[1] = ChapterDownloadException(
            ChapterFailureKind.NETWORK,
            retryable = true,
            message = "Network error",
        )
        engine.enqueueNow(bookUrl, listOf(1))
        engine.run(1, attempt = 1)
        assertEquals(
            DownloadState.WAITING_NETWORK.wire,
            store.tasksById.values.single().state,
        )

        engine.pauseNow(1)
        assertEquals(DownloadState.PAUSED.wire, store.tasksById.values.single().state)
        engine.resumeNow(1)
        assertEquals(DownloadState.QUEUED.wire, store.tasksById.values.single().state)

        // Cancel is still allowed before the retry actually succeeds.
        engine.cancelNow(1)
        assertEquals(DownloadState.CANCELLED.wire, store.tasksById.values.single().state)
        assertTrue(store.bodies.isEmpty())
    }

    @Test
    fun `final attempt parks network leftovers instead of leaving queued rows`() = runTest {
        val engine = engine()
        provider.failures[1] = ChapterDownloadException(
            ChapterFailureKind.NETWORK,
            retryable = true,
            message = "Network error",
        )
        engine.enqueueNow(bookUrl, listOf(1, 2, 3))
        val outcome = engine.run(1, attempt = DownloadRetryPolicy.MAX_ATTEMPTS)

        assertEquals(
            DownloadWorkDecision.EXHAUSTED,
            DownloadWorkPolicy.decide(outcome, DownloadRetryPolicy.MAX_ATTEMPTS),
        )
        engine.parkWaitingNetwork(1, "No network; retry when connected")

        assertTrue(
            store.tasksById.values.none {
                it.state == DownloadState.QUEUED.wire ||
                    it.state == DownloadState.RUNNING.wire
            },
        )
        assertTrue(
            store.tasksById.values.all { it.state == DownloadState.WAITING_NETWORK.wire },
        )
        assertTrue(logs.any { it.contains("pending-parked bookId=1 state=WAITING_NETWORK count=3") })
    }

    @Test
    fun `failPending never turns a source block into FAILED`() = runTest {
        val engine = engine()
        provider.failures[1] = ChapterDownloadException(
            ChapterFailureKind.CLOUDFLARE,
            retryable = false,
            message = "Cloudflare verification required",
        )
        engine.enqueueNow(bookUrl, listOf(1, 2))
        engine.run(1, attempt = 1)

        engine.failPending(1, "Retry attempts exhausted")

        assertTrue(
            store.tasksById.values.all {
                it.state == DownloadState.NEEDS_SOURCE_ACTION.wire
            },
        )
    }

    @Test
    fun `chapter delay runs between successes and pause stops the next chapter`() = runTest {
        provider.contents[1] = FakeProvider.Update("<p>one</p>")
        provider.contents[2] = FakeProvider.Update("<p>two</p>")
        val delays = mutableListOf<Long>()
        lateinit var engine: DownloadEngine
        engine = engine(
            chapterDelayMs = { 2_500L },
            sleep = { millis ->
                delays += millis
                engine.pauseNow(1)
            },
        )
        engine.enqueueNow(bookUrl, listOf(1, 2))

        val outcome = engine.run(1, attempt = 1)

        assertEquals(listOf(2_500L), delays)
        assertEquals(1, outcome.completed)
        val byChapter = store.tasksById.values.associateBy { it.chapterId }
        assertEquals(DownloadState.COMPLETED.wire, byChapter[1L]!!.state)
        assertEquals(DownloadState.PAUSED.wire, byChapter[2L]!!.state)
        assertTrue(logs.any { it.contains("chapterDelayMs=2500") })
    }

    @Test
    fun `no delay after the last successful chapter`() = runTest {
        provider.contents[1] = FakeProvider.Update("<p>one</p>")
        var delays = 0
        val engine = engine(
            chapterDelayMs = { 2_000L },
            sleep = { delays++ },
        )
        engine.enqueueNow(bookUrl, listOf(1))

        assertEquals(1, engine.run(1, attempt = 1).completed)
        assertEquals(0, delays)
    }

    @Test
    fun `cancel keeps a cancelled task out of the run`() = runTest {
        val engine = engine()
        engine.enqueueNow(bookUrl, listOf(1))
        engine.cancelNow(1)

        val outcome = engine.run(1)

        assertEquals(0, outcome.completed)
        assertEquals(0, outcome.failed)
        assertEquals(0, outcome.retryable)
        assertEquals(DownloadState.CANCELLED.wire, store.tasksById.values.single().state)
    }
}

/** Blocks the first chapter-file delete so a delete/download race is testable. */
private class GatedFileStore(
    private val delegate: DownloadFileStore,
    private val gatedChapterId: Long,
    private val deleteStarted: CountDownLatch,
    private val allowDelete: CountDownLatch,
    private val writeDone: CountDownLatch,
) : DownloadFileStore by delegate {
    private val gated = AtomicBoolean(false)

    override fun writeText(bookId: Long, chapterId: Long, html: String): StoredText {
        val stored = delegate.writeText(bookId, chapterId, html)
        if (chapterId == gatedChapterId) writeDone.countDown()
        return stored
    }

    override fun deleteChapterFiles(bookId: Long, chapterId: Long) {
        if (chapterId == gatedChapterId && gated.compareAndSet(false, true)) {
            deleteStarted.countDown()
            allowDelete.await(5, TimeUnit.SECONDS)
        }
        delegate.deleteChapterFiles(bookId, chapterId)
    }
}
