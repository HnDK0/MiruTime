package dev.mirutime.app.features.book.data

/** Counters shown in the diagnostic chapter-status line. */
internal data class ChapterStatusCounters(
    val bookId: Long,
    val count: Int,
    val read: Int,
    val downloaded: Int,
    val translated: Int,
    val last: Int?,
)

/**
 * The book page polls chapters while a queue is active; an unchanged snapshot
 * must not be logged on every poll. Log on a real change or once per window.
 */
internal fun shouldLogChapterStatus(
    previous: ChapterStatusCounters?,
    current: ChapterStatusCounters,
    now: Long,
    lastLoggedAt: Long,
    minIntervalMs: Long = 30_000L,
): Boolean = previous != current || now - lastLoggedAt >= minIntervalMs
