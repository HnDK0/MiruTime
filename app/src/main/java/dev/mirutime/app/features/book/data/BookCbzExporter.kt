package dev.mirutime.app.features.book.data

import dev.mirutime.app.features.book.domain.BookChapter
import dev.mirutime.core.local.CbzPage
import dev.mirutime.core.local.ComicInfoMeta
import dev.mirutime.core.local.buildComicInfoXml
import dev.mirutime.core.local.writeCbzPages
import dev.mirutime.core.log.MiruLog
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fields a CBZ needs from the book page; the screen already has them loaded. */
internal data class CbzBookInfo(
    val title: String,
    val description: String = "",
    val author: String = "",
    val genres: List<String> = emptyList(),
)

internal sealed interface CbzExportResult {
    data class Success(
        val chaptersWritten: Int,
        val chaptersSkipped: Int,
    ) : CbzExportResult

    data object NoChapters : CbzExportResult

    data class Failure(val message: String) : CbzExportResult
}

/** Destination of one export (SAF in the app, an in-memory map in tests). */
internal interface CbzExportTarget {
    /** Creates the manga folder and returns a handle used by the writes. */
    suspend fun beginFolder(suggestedName: String): String

    suspend fun writeMetadata(folderHandle: String, xml: ByteArray)

    suspend fun writeChapter(folderHandle: String, fileName: String, file: File)
}

/**
 * Exports downloaded remote manga chapters as one CBZ per chapter. Local books
 * are already files on the device and are deliberately rejected. Pages come
 * from the download store (`chapter_pages.pagePathsJson`), never from a network
 * fetch, so an export works offline and cannot silently produce empty chapters.
 */
internal class BookCbzExporter(
    private val isLocalBook: (String) -> Boolean,
    private val bookInfo: suspend (String) -> CbzBookInfo?,
    private val chapters: suspend (String) -> List<BookChapter>,
    private val pagePaths: suspend (String, Int) -> List<String>,
    private val tempDir: File,
) {
    suspend fun export(bookUrl: String, target: CbzExportTarget): CbzExportResult {
        if (isLocalBook(bookUrl)) return CbzExportResult.NoChapters
        val info = bookInfo(bookUrl) ?: return CbzExportResult.Failure("Book details are unavailable")

        var skipped = 0
        val exportable = mutableListOf<Pair<BookChapter, List<String>>>()
        for (chapter in chapters(bookUrl).sortedBy { it.index }) {
            val files = if (chapter.downloaded) {
                pagePaths(bookUrl, chapter.index)
            } else {
                emptyList()
            }
            // A chapter with even one missing page is skipped: a truncated
            // archive is worse than an honest "not exported".
            if (files.isEmpty() || files.any { !File(it).isFile }) {
                skipped++
                continue
            }
            exportable += chapter to files
        }
        if (exportable.isEmpty()) return CbzExportResult.NoChapters

        val title = info.title.ifBlank { "Manga" }
        val handle = target.beginFolder(safeName(title, "Manga"))
        target.writeMetadata(
            handle,
            buildComicInfoXml(
                meta = ComicInfoMeta(
                    title = title,
                    series = title,
                    summary = info.description.takeIf { it.isNotBlank() },
                    author = info.author.takeIf { it.isNotBlank() && it != "Unknown" },
                    genres = info.genres,
                ),
                pageCount = exportable.sumOf { it.second.size },
            ),
        )

        var written = 0
        for ((chapter, files) in exportable) {
            currentCoroutineContext().ensureActive()
            try {
                val context = currentCoroutineContext()
                // Lazy sequence: one page is read, zipped and released before
                // the next, so a long chapter cannot exhaust the heap.
                val pages = files.asSequence()
                    .mapIndexed { index, path ->
                        context.ensureActive()
                        CbzPage(pageName(index, path), readPage(path))
                    }
                    .iterator()
                val temp = File(tempDir, "miru-cbz-${chapter.index}-${System.nanoTime()}.cbz")
                try {
                    temp.outputStream().use { stream ->
                        writeCbzPages(
                            output = stream,
                            comicInfo = ComicInfoMeta(title = chapter.title, series = title),
                            number = chapter.index,
                            pageCount = files.size,
                            pages = pages,
                        )
                    }
                    target.writeChapter(handle, chapterFileName(chapter), temp)
                    written++
                } finally {
                    temp.delete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                skipped++
                // The user only sees a count; the reason belongs in the log.
                MiruLog.w(
                    "book",
                    "cbz chapter skipped chapter=${chapter.index} " +
                        "error=${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }
        return when {
            written > 0 -> CbzExportResult.Success(written, skipped)
            // Nothing was written although chapters looked exportable: the
            // user must see a failure, not "no chapters".
            else -> CbzExportResult.Failure("No chapter could be exported")
        }
    }
}

/** The CBZ tile mirrors EPUB: visible, but enabled only with local chapters. */
internal fun cbzExportEnabled(isManga: Boolean, isLocal: Boolean, exportableCount: Int): Boolean =
    isManga && !isLocal && exportableCount > 0

internal fun chapterFileName(chapter: BookChapter): String =
    "%03d - %s.cbz".format(
        chapter.index,
        safeName(chapter.title.ifBlank { "Chapter ${chapter.index}" }, "Chapter ${chapter.index}"),
    )

private fun pageName(index: Int, path: String): String {
    val extension = path.substringAfterLast('.', "").lowercase()
        .takeIf { it in PAGE_EXTENSIONS } ?: "jpg"
    return "${(index + 1).toString().padStart(3, '0')}.$extension"
}

private fun readPage(path: String): ByteArray {
    val file = File(path)
    require(file.length() in 1..MAX_PAGE_BYTES) { "Unreadable page: ${file.name}" }
    return file.readBytes()
}

private fun safeName(value: String, fallback: String): String {
    val safe = value.trim()
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(120)
    return safe.ifBlank { fallback }
}

private const val MAX_PAGE_BYTES = 32L * 1024 * 1024

private val PAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
