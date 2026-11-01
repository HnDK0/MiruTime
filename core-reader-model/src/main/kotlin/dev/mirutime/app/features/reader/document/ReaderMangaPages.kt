package dev.mirutime.app.features.reader.document

/**
 * Ordered image blocks a manga chapter renders as pages. Text-only blocks and
 * images without a usable reference are ignored.
 */
fun ReaderDocument.mangaPages(): List<ReaderBlock> =
    blocks.filter { it.kind == BlockKind.Image && !it.imageRef.isNullOrBlank() }

/**
 * Pager page for a stored anchor. A saved anchor may belong to another chapter
 * (the session restores before the document swaps), so anything that does not
 * resolve to a page in this chapter starts at the first page.
 */
fun ReaderDocument.pageIndexForAnchor(anchor: ReadingAnchor?): Int {
    if (anchor == null || anchor.chapterUrl != chapterUrl) return 0
    val index = mangaPages().indexOfFirst { it.id == anchor.blockId }
    return if (index >= 0) index else 0
}
