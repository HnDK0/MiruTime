package dev.mirutime.feature.reader

import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.reader.document.ReadingAnchor

data class ReaderError(val message: String)

// Content failure translated at the app boundary: feature-reader cannot import
// :mirutime-core, so the typed PluginError becomes plain strings here. The
// message is the redacted detail (source, operation, host+path), never a URL.
class ReaderLoadFailure(
    val title: String,
    override val message: String,
    val authUrl: String? = null,
) : Exception(message)

sealed interface ChapterLoadStatus {
    data object NotLoaded : ChapterLoadStatus
    data object Loading : ChapterLoadStatus
    data object Ready : ChapterLoadStatus
    data class Failed(
        val message: String,
        val title: String? = null,
        val authUrl: String? = null,
    ) : ChapterLoadStatus
}

sealed interface FeedEntry {
    val id: String
    val chapterIndex: Int

    data class Header(
        override val id: String,
        override val chapterIndex: Int,
        val title: String,
    ) : FeedEntry

    data class Block(
        override val id: String,
        override val chapterIndex: Int,
        val block: ReaderBlock,
    ) : FeedEntry

    data class Gap(
        override val id: String,
        override val chapterIndex: Int,
        val title: String,
        val status: ChapterLoadStatus,
        val top: Boolean,
    ) : FeedEntry
}

data class FeedPlan(
    val entries: List<FeedEntry>,
    val anchorEntryId: String?,
    val chapterEntryRanges: Map<Int, IntRange>,
    val lastChapterIndex: Int?,
) {
    companion object {
        val Empty = FeedPlan(emptyList(), null, emptyMap(), null)
    }
}

object FeedPlanner {
    fun plan(
        chapters: List<ReaderChapter>,
        documents: Map<Int, ReaderDocument>,
        statuses: Map<Int, ChapterLoadStatus>,
        anchor: ReadingAnchor?,
    ): FeedPlan {
        if (chapters.isEmpty()) return FeedPlan.Empty
        val loaded = documents.keys.sorted()
        if (loaded.isEmpty()) {
            val first = chapters.first()
            val gap = FeedEntry.Gap(
                id = "gap:${first.index}",
                chapterIndex = first.index,
                title = first.title,
                status = statuses[first.index] ?: ChapterLoadStatus.NotLoaded,
                top = false,
            )
            return FeedPlan(listOf(gap), null, emptyMap(), first.index)
        }

        val first = loaded.first()
        val last = loaded.last()
        val entries = mutableListOf<FeedEntry>()
        val ranges = mutableMapOf<Int, IntRange>()

        chapters.firstOrNull { it.index == first - 1 }?.let { previous ->
            entries += FeedEntry.Gap(
                id = "gap:top:${previous.index}",
                chapterIndex = previous.index,
                title = previous.title,
                status = statuses[previous.index] ?: ChapterLoadStatus.NotLoaded,
                top = true,
            )
        }

        for (chapter in chapters.filter { it.index in first..last }) {
            val document = documents[chapter.index]
            if (document == null) {
                entries += FeedEntry.Gap(
                    id = "gap:${chapter.index}",
                    chapterIndex = chapter.index,
                    title = chapter.title,
                    status = statuses[chapter.index] ?: ChapterLoadStatus.NotLoaded,
                    top = false,
                )
                continue
            }
            val start = entries.size
            entries += FeedEntry.Header("h:${chapter.index}", chapter.index, document.title)
            // The header carries the chapter title, so a body block that repeats
            // it is not rendered twice.
            document.bodyBlocks().forEach { block ->
                entries += FeedEntry.Block(
                    // Chapter index, not URL: duplicate URLs across chapters
                    // must not collide in the feed's LazyColumn.
                    id = "b:${chapter.index}:${block.id.index}",
                    chapterIndex = chapter.index,
                    block = block,
                )
            }
            ranges[chapter.index] = start..entries.lastIndex
        }

        chapters.firstOrNull { it.index == last + 1 }?.let { next ->
            entries += FeedEntry.Gap(
                id = "gap:${next.index}",
                chapterIndex = next.index,
                title = next.title,
                status = statuses[next.index] ?: ChapterLoadStatus.NotLoaded,
                top = false,
            )
        }

        val anchorChapterIndex = anchor?.let { value ->
            chapters.firstOrNull { it.chapterUrl == value.chapterUrl }?.index
        }
        val anchorEntryId = anchor?.let { value ->
            entries.firstOrNull { it is FeedEntry.Block && it.block.id == value.blockId }?.id
                ?: entries.firstOrNull {
                    it is FeedEntry.Header && it.chapterIndex == anchorChapterIndex
                }?.id
        }

        return FeedPlan(entries, anchorEntryId, ranges, last)
    }

    fun prune(
        documents: Map<Int, ReaderDocument>,
        anchorChapter: Int,
        back: Int = 2,
        ahead: Int = 2,
    ): Map<Int, ReaderDocument> =
        documents.filterKeys { it in (anchorChapter - back)..(anchorChapter + ahead) }
}
