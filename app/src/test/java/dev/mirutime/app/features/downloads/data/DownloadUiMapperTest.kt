package dev.mirutime.app.features.downloads.data

import dev.mirutime.data.library.DownloadTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUiMapperTest {
    private fun task(id: Long, state: DownloadState, error: String? = null) = DownloadTaskEntity(
        id = id,
        bookId = 1,
        chapterId = id,
        kind = DownloadKind.TEXT.wire,
        state = state.wire,
        createdAt = id,
        updatedAt = id,
        error = error,
    )

    @Test
    fun `running queue reports task progress and a real error`() {
        val ui = DownloadUiMapper.map(
            book = testBook(),
            tasks = listOf(
                task(1, DownloadState.COMPLETED),
                task(2, DownloadState.RUNNING),
                task(3, DownloadState.FAILED, error = "HTTP 503"),
            ),
            downloadedChapters = 1,
            chapterCount = 3,
        )!!

        assertEquals(DownloadState.RUNNING, ui.state)
        assertEquals(1, ui.completedTasks)
        assertEquals(3, ui.taskCount)
        assertEquals(1f / 3f, ui.progress, 0.0001f)
        assertEquals("HTTP 503", ui.error)
        assertEquals(1, ui.downloaded)
    }

    @Test
    fun `paused wins over failed until the user acts`() {
        val ui = DownloadUiMapper.map(
            book = testBook(),
            tasks = listOf(
                task(1, DownloadState.PAUSED),
                task(2, DownloadState.FAILED),
            ),
            downloadedChapters = 0,
            chapterCount = 3,
        )!!

        assertEquals(DownloadState.PAUSED, ui.state)
        assertTrue(ui.isPaused)
    }

    @Test
    fun `source action outranks running and keeps the bounded reason`() {
        val ui = DownloadUiMapper.map(
            book = testBook(),
            tasks = listOf(
                task(1, DownloadState.COMPLETED),
                task(2, DownloadState.RUNNING),
                task(3, DownloadState.NEEDS_SOURCE_ACTION, error = "Cloudflare verification required"),
            ),
            downloadedChapters = 1,
            chapterCount = 3,
        )!!

        assertEquals(DownloadState.NEEDS_SOURCE_ACTION, ui.state)
        assertTrue(ui.isNeedsSourceAction)
        assertEquals("Cloudflare verification required", ui.error)
        assertEquals(1, ui.completedTasks)
    }

    @Test
    fun `network wait outranks queued rows`() {
        val ui = DownloadUiMapper.map(
            book = testBook(),
            tasks = listOf(
                task(1, DownloadState.WAITING_NETWORK, error = "Network error"),
                task(2, DownloadState.QUEUED),
            ),
            downloadedChapters = 0,
            chapterCount = 3,
        )!!

        assertEquals(DownloadState.WAITING_NETWORK, ui.state)
        assertTrue(ui.isWaitingNetwork)
    }

    @Test
    fun `no tasks means no queue row`() {
        val ui = DownloadUiMapper.map(
            book = testBook(),
            tasks = emptyList(),
            downloadedChapters = 2,
            chapterCount = 3,
        )

        assertEquals(null, ui)
    }
}
