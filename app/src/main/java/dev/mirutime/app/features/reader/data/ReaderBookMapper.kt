package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.book.domain.BookDetails
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.app.features.reader.document.ReaderBook
import dev.mirutime.app.features.reader.document.ReaderChapter
import dev.mirutime.app.features.reader.document.chapterKey

fun BookDetails.toReaderBook(): ReaderBook = ReaderBook(
    bookUrl = bookUrl,
    title = title,
    chapters = chapters.map { chapter ->
        ReaderChapter(
            chapterUrl = chapter.url ?: chapterKey(bookUrl, chapter.index),
            index = chapter.index,
            title = chapter.title,
            isRead = chapter.isRead,
        )
    },
    isManga = contentType == ContentType.Manga,
    isVideo = contentType == ContentType.Video || contentType == ContentType.Audio,
    coverUrl = coverUrl,
)
