package dev.mirutime.app.features.downloads.data

import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.libraryBookKey
import dev.mirutime.data.library.DownloadTaskEntity

/** Per-book queue card. Built from Room tasks + the real stored-body count. */
data class DownloadBookUi(
    val bookId: Long,
    val bookUrl: String,
    val title: String,
    val coverUrl: String?,
    val kind: DownloadKind,
    val downloaded: Int,
    val chapterCount: Int,
    val completedTasks: Int,
    val taskCount: Int,
    val state: DownloadState,
    val error: String?,
) {
    val progress: Float
        get() = if (taskCount <= 0) 0f else completedTasks.toFloat() / taskCount
    val isActive: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING
    val isPaused: Boolean get() = state == DownloadState.PAUSED
    val isFailed: Boolean get() = state == DownloadState.FAILED
    val isWaitingNetwork: Boolean get() = state == DownloadState.WAITING_NETWORK
    val isNeedsSourceAction: Boolean get() = state == DownloadState.NEEDS_SOURCE_ACTION
    val isBlocked: Boolean get() = isWaitingNetwork || isNeedsSourceAction
}

object DownloadUiMapper {
    fun map(
        book: BookEntity,
        tasks: List<DownloadTaskEntity>,
        downloadedChapters: Int,
        chapterCount: Int,
    ): DownloadBookUi? {
        if (tasks.isEmpty()) return null
        val states = tasks.mapNotNull { DownloadState.from(it.state) }
        // A book-wide source block or a network wait outranks a single failed
        // chapter; the card must never hide it behind "running/failed".
        val firstError = tasks.firstOrNull {
            DownloadState.from(it.state) in setOf(
                DownloadState.NEEDS_SOURCE_ACTION,
                DownloadState.WAITING_NETWORK,
                DownloadState.FAILED,
            )
        }?.error
        val state = when {
            states.any { it == DownloadState.NEEDS_SOURCE_ACTION } ->
                DownloadState.NEEDS_SOURCE_ACTION
            states.any { it == DownloadState.WAITING_NETWORK } -> DownloadState.WAITING_NETWORK
            states.any { it == DownloadState.RUNNING } -> DownloadState.RUNNING
            states.any { it == DownloadState.QUEUED } -> DownloadState.QUEUED
            states.any { it == DownloadState.PAUSED } -> DownloadState.PAUSED
            states.any { it == DownloadState.FAILED } -> DownloadState.FAILED
            states.any { it == DownloadState.COMPLETED } -> DownloadState.COMPLETED
            else -> DownloadState.CANCELLED
        }
        return DownloadBookUi(
            bookId = book.bookId,
            bookUrl = libraryBookKey(book.installId, book.url),
            title = book.title,
            coverUrl = book.coverUrl,
            kind = DownloadKind.from(tasks.first().kind) ?: DownloadKind.TEXT,
            downloaded = downloadedChapters,
            chapterCount = chapterCount.coerceAtLeast(tasks.size),
            completedTasks = states.count { it == DownloadState.COMPLETED },
            taskCount = tasks.size,
            state = state,
            error = firstError,
        )
    }
}
