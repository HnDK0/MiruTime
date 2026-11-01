package dev.mirutime.app.features.translation.data

import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.ChapterTranslationEntity

/** What one "download + translate" request wrote to the durable queue. */
data class TranslationEnqueueResult(
    val configured: Boolean,
    val queued: Int = 0,
    val alreadyQueued: Int = 0,
    val alreadyTranslated: Int = 0,
    val skippedPages: Int = 0,
)

/**
 * Durable translation intent. The existing `chapter_translations` table is the
 * queue: a PENDING row is work, COMPLETE is a cache hit, and FAILED can be
 * retried by enqueueing again. No new table/column/schema version.
 */
class DownloadTranslationQueue(
    private val store: TranslationQueueStore,
    private val configFor: (bookUrl: String) -> ChapterTranslationConfig? =
        { ChapterTranslationPlanner.config(it, activeTranslationMode(it)) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(
        bookUrl: String,
        chapters: Collection<ChapterEntity>,
    ): TranslationEnqueueResult {
        val config = configFor(bookUrl)
            ?: return TranslationEnqueueResult(configured = false)
        val source = config.mode.source
        val target = config.mode.target
        val providerName = config.mode.provider
        val promptHash = ChapterTranslationPlanner.promptHash(config.prompt)
        val existing = chapters
            .map { it.chapterId }
            .toSet()
            .let { ids -> store.queueRows(bookIdOf(chapters)).filter { it.chapterId in ids } }
            .groupBy { it.chapterId }

        var queued = 0
        var alreadyQueued = 0
        var alreadyTranslated = 0
        var skippedPages = 0
        for (chapter in chapters.distinctBy { it.chapterId }) {
            if (store.hasPages(chapter.chapterId)) {
                skippedPages++
                continue
            }
            val latest = store.latest(
                chapterId = chapter.chapterId,
                source = source,
                target = target,
                provider = providerName,
                promptHash = promptHash,
                pipelineVersion = ChapterTranslationPlanner.PIPELINE_VERSION,
            )
            when (latest?.status?.uppercase()) {
                STATUS_COMPLETE -> {
                    alreadyTranslated++
                    continue
                }
                STATUS_PENDING -> {
                    cancellingOtherPending(
                        rows = existing[chapter.chapterId].orEmpty(),
                        source = source,
                        target = target,
                        provider = providerName,
                        promptHash = promptHash,
                    )
                    alreadyQueued++
                    continue
                }
            }
            cancellingOtherPending(
                rows = existing[chapter.chapterId].orEmpty(),
                source = source,
                target = target,
                provider = providerName,
                promptHash = promptHash,
            )
            val base = latest ?: ChapterTranslationEntity(
                chapterId = chapter.chapterId,
                source = source,
                target = target,
                provider = providerName,
                model = null,
                promptHash = promptHash,
                sourceHash = ChapterTranslationPlanner.PENDING_SOURCE_HASH,
                pipelineVersion = ChapterTranslationPlanner.PIPELINE_VERSION,
                status = STATUS_PENDING,
                updatedAt = now(),
            )
            store.save(
                base.copy(
                    source = source,
                    target = target,
                    provider = providerName,
                    model = null,
                    promptHash = promptHash,
                    pipelineVersion = ChapterTranslationPlanner.PIPELINE_VERSION,
                    status = STATUS_PENDING,
                    updatedAt = now(),
                ),
            )
            queued++
        }
        return TranslationEnqueueResult(
            configured = true,
            queued = queued,
            alreadyQueued = alreadyQueued,
            alreadyTranslated = alreadyTranslated,
            skippedPages = skippedPages,
        )
    }

    // One pending config per chapter: switching provider/target must not leave
    // an old intent that can never run (the row itself is the only queue key).
    private suspend fun cancellingOtherPending(
        rows: List<dev.mirutime.data.library.ChapterTranslationQueueRow>,
        source: String,
        target: String,
        provider: String,
        promptHash: String,
    ) {
        for (row in rows) {
            val current = row.status.equals(STATUS_PENDING, ignoreCase = true)
            val same = row.source == source &&
                row.target == target &&
                row.provider == provider &&
                row.promptHash == promptHash
            if (current && !same) {
                store.save(
                    ChapterTranslationEntity(
                        translationId = row.translationId,
                        chapterId = row.chapterId,
                        source = row.source,
                        target = row.target,
                        provider = row.provider,
                        model = null,
                        promptHash = row.promptHash,
                        sourceHash = row.sourceHash,
                        pipelineVersion = row.pipelineVersion,
                        status = STATUS_CANCELLED,
                        updatedAt = now(),
                    ),
                )
            }
        }
    }

    private fun bookIdOf(chapters: Collection<ChapterEntity>): Long =
        chapters.firstOrNull()?.bookId ?: 0L

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_COMPLETE = "COMPLETE"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}
