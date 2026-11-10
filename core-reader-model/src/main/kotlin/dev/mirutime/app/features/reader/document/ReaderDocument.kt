package dev.mirutime.app.features.reader.document

import dev.mirutime.core.text.normalizeAngles

data class BlockId(val chapterUrl: String, val index: Int)

enum class BlockKind { Heading, Paragraph, Image, Divider }

data class SentenceSpan(val start: Int, val end: Int)

data class ReaderBlock(
    val id: BlockId,
    val kind: BlockKind,
    val original: String,
    val translation: String? = null,
    val sentences: List<SentenceSpan> = emptyList(),
    val imageRef: String? = null,
)

data class ReaderDocument(
    val bookUrl: String,
    val chapterUrl: String,
    val chapterIndex: Int,
    val title: String,
    val blocks: List<ReaderBlock>,
    val revision: Long = 0,
    // Built from cached translation blocks because the original source is
    // unavailable. It must never be submitted to the translator again.
    val translationOnly: Boolean = false,
) {
    fun blockAt(blockId: BlockId): ReaderBlock? = blocks.firstOrNull { it.id == blockId }

    fun firstAnchor(): ReadingAnchor? =
        blocks.firstOrNull()?.let { ReadingAnchor(chapterUrl, it.id, 0) }

    /**
     * Leading heading that repeats the chapter title. Rendered as a shared header instead of a body block.
     * Blocks are never removed, so stored anchors keep pointing at the same [BlockId].
     */
    fun titleBlock(): ReaderBlock? = blocks.firstOrNull()?.takeIf {
        it.kind == BlockKind.Heading &&
            normalizeAngles(it.original).trim() == normalizeAngles(title).trim()
    }

    /** Blocks to render as chapter body: everything except the duplicated [titleBlock]. */
    fun bodyBlocks(): List<ReaderBlock> =
        titleBlock()?.let { title -> blocks.filterNot { it.id == title.id } } ?: blocks
}

data class ReadingAnchor(
    val chapterUrl: String,
    val blockId: BlockId,
    val charOffset: Int = 0,
    val sentenceIndex: Int? = null,
)

data class ReaderChapter(
    val chapterUrl: String,
    val index: Int,
    val title: String,
    val isRead: Boolean,
)

data class ReaderBook(
    val bookUrl: String,
    val title: String,
    val chapters: List<ReaderChapter>,
    val isManga: Boolean = false,
    val isVideo: Boolean = false,
    // Cover is only carried for the playback notification; reading itself
    // never needs the image. Null keeps the notification's app icon.
    val coverUrl: String? = null,
)

fun chapterKey(bookUrl: String, chapterIndex: Int): String =
    "$bookUrl#chapter=$chapterIndex"
