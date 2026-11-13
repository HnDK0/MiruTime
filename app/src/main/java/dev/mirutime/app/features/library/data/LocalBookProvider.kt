package dev.mirutime.app.features.library.data

import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.Chapter
import dev.mirutime.core.api.ChapterContent as CoreChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.log.MiruLog
import dev.mirutime.core.text.textToParagraphHtml
import dev.mirutime.core.reader.ChapterContent
import dev.mirutime.core.reader.ChapterSource
import dev.mirutime.app.core.jsonStringArray
import dev.mirutime.core.local.LocalSource
import dev.mirutime.data.library.LibraryBookFiles
import dev.mirutime.data.library.LibraryRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal const val LocalBooksInstallId = "local-books"
internal const val LocalMangaInstallId = "local-manga"

// local_book_files.parserVersion: 1 = single copy file, 2 = folder of image
// chapters persisted through chapter_pages.
internal const val FolderParserVersion = 2
// 3 = direct SAF row: copyPath = null, chapters/read state live in the DB but
// the document stays in the user's folder.
internal const val DirectSafParserVersion = 3

// A PDF import stays on the books shelf (its install id is `local-books`) but
// is read page by page, so the reader must see it as manga.
internal fun isLocalManga(installId: String, format: String?): Boolean =
    installId == LocalMangaInstallId || format == "PDF"

internal fun localBookContentType(installId: String, format: String?): ContentType =
    if (isLocalManga(installId, format)) ContentType.MANGA else ContentType.NOVEL

/**
 * Resolves an opaque `local://<uuid>` book through `local_book_files.copyPath`
 * and serves it with the core [LocalSource] parser. The imported copy is
 * content-addressed, so the URL and the SAF uri are never used as identity.
 */
class LocalBookProvider(
    private val library: LibraryRepository,
    private val fileSystem: FileSystem,
    private val baseDir: File,
    // Rendered PDF pages live in the evictable cache, not under baseDir.
    private val cacheDir: File? = null,
) {
    suspend fun book(bookUrl: String): BookMeta? {
        val row = library.book(bookUrl) ?: return null
        if (library.localBookFile(row.bookId) == null) return null
        // Imported metadata is authoritative; no parser walk for book details.
        return BookMeta(
            title = row.title,
            description = row.description,
            author = row.authorRaw.ifBlank { row.author },
        )
    }

    // DB chapter URLs are stable `local://<uuid>#chapter=N`; the import writes
    // the full list, so the parser is only a fallback for a missing row set.
    suspend fun chapters(bookUrl: String): List<Chapter>? {
        val row = library.book(bookUrl) ?: return null
        val stored = library.chapters(row.bookId)
        if (stored.isNotEmpty()) {
            return stored.map { Chapter(title = it.title, url = it.url) }
        }
        val (source, path) = resolve(bookUrl) ?: return null
        val parsed = withContext(Dispatchers.IO) { source.chapters(path).getOrNull() } ?: return null
        return parsed.mapIndexed { position, chapter ->
            Chapter(title = chapter.title, url = chapterUrl(bookUrl, position + 1))
        }
    }

    suspend fun chapterContent(bookUrl: String, chapterUrl: String): ChapterContent? {
        if (!chapterUrl.startsWith("$bookUrl#chapter=")) return null
        val index = chapterUrl.substringAfter("#chapter=").toIntOrNull() ?: return null
        if (index <= 0) return null
        val row = library.book(bookUrl)
        val chapterRow = row?.let { library.chapterByUrl(it.bookId, chapterUrl) }
            ?: row?.let { library.chapterByIndex(it.bookId, index) }
        // Persisted import body is the source of truth: no parser/zip walk.
        val body = chapterRow?.let { library.chapterBody(it.chapterId) }
        if (body != null) {
            val file = File(baseDir, body.path)
            if (file.isFile) {
                return ChapterContent(
                    bookUrl = bookUrl,
                    chapterIndex = index,
                    title = chapterRow?.title ?: "Chapter $index",
                    html = withContext(Dispatchers.IO) { file.readText() },
                    chapterUrl = chapterUrl,
                    source = ChapterSource.IMPORTED_DB_BODY,
                    chapterId = chapterRow?.chapterId,
                )
            }
        }
        val chapterPages = chapterRow?.let { library.chapterPage(it.chapterId) }
        if (chapterPages != null) {
            val paths = runCatching {
                val array = JSONArray(chapterPages.pagePathsJson)
                List(array.length()) { array.optString(it) }
            }.getOrDefault(emptyList()).filter { it.isNotBlank() }
            if (paths.isEmpty()) return null
            val html = pageImagesHtml(baseDir, paths)
            return ChapterContent(
                bookUrl = bookUrl,
                chapterIndex = index,
                title = chapterRow?.title ?: "Chapter $index",
                html = html,
                chapterUrl = chapterUrl,
                source = ChapterSource.IMPORTED_DB_BODY,
                chapterId = chapterRow?.chapterId,
            )
        }
        val (source, path) = resolve(bookUrl) ?: return null
        if (path.endsWith(".cbz")) {
            val chapters = withContext(Dispatchers.IO) { source.chapters(path).getOrNull() } ?: return null
            val parserChapter = chapters.getOrNull(index - 1) ?: return null
            val parsed = withContext(Dispatchers.IO) {
                source.chapterContent("", parserChapter.url).getOrNull()
            }
            if (parsed !is CoreChapterContent.Pages) return null
            val pageDir = "${path.substringBeforeLast('.')}/pages/$index"
            val inlinePages = parsed.pageUrls.mapIndexedNotNull { pageIndex, pageUrl ->
                val bytes = withContext(Dispatchers.IO) { source.pageBytes(pageUrl) }
                    ?: return@mapIndexedNotNull null
                val extension = pageUrl.substringAfter('#').substringAfterLast('.', "jpg")
                val pagePath = "$pageDir/$pageIndex.$extension"
                withContext(Dispatchers.IO) { fileSystem.write(pagePath, bytes) }
                if (!withContext(Dispatchers.IO) { fileSystem.exists(pagePath) }) {
                    return@mapIndexedNotNull null
                }
                InlinePage(position = pageIndex, url = File(baseDir, pagePath).toURI().toString())
            }
            if (inlinePages.isEmpty()) return null
            return ChapterContent(
                bookUrl = bookUrl,
                chapterIndex = index,
                title = parserChapter.title,
                html = inlinePages.joinToString("") { "<p><img src=\"${htmlAttribute(it.url)}\"/></p>" },
                chapterUrl = chapterUrl,
                source = ChapterSource.IMPORTED_DB_BODY,
                chapterId = chapterRow?.chapterId,
            )
        }

        // Pre-release data is always imported, so a missing body only remains
        // reachable for unexpected files; parse text without lazy image writes.
        val parsedChapter = withContext(Dispatchers.IO) {
            source.localChapterAt(path, index - 1)
        } ?: return null
        return ChapterContent(
            bookUrl = bookUrl,
            chapterIndex = index,
            title = parsedChapter.title,
            html = textToParagraphHtml(parsedChapter.body),
            chapterUrl = chapterUrl,
            source = ChapterSource.IMPORTED_DB_BODY,
            chapterId = chapterRow?.chapterId,
        )
    }

    /** Deletes a manifest path relative to the same root the imports were copied under. */
    fun deleteFile(path: String) = fileSystem.delete(path)

    /**
     * Post-commit cleanup for a bulk delete. Paths were collected in the same
     * DB transaction that removed the rows; this runs on IO and never touches
     * the user's original SAF document. Folder imports own their copy tree,
     * single-file imports own the file plus its content-addressed directory.
     */
    suspend fun removeFiles(files: List<LibraryBookFiles>) {
        removeLibraryFiles(fileSystem, files)
        withContext(Dispatchers.IO) { files.forEach { deletePdfPageCache(it.bookId) } }
    }

    /**
     * Removes an imported book from the app: DB row cascade + copied file +
     * local cover. The user's original SAF document is never touched.
     */
    suspend fun remove(bookUrl: String): Boolean = withContext(Dispatchers.IO) {
        val row = library.book(bookUrl) ?: return@withContext false
        val file = library.localBookFile(row.bookId)
        val copyPath = file?.copyPath
        val coverPath = library.bookCover(row.bookId)?.localPath
        val folderCopy = copyPath?.takeIf { it.isNotBlank() }
            ?.let { File(baseDir, it).isDirectory } == true
        library.deleteBook(row.bookId)
        val copy = copyPath?.takeIf { it.isNotBlank() }
        val contentRoot = copy?.takeIf { !folderCopy }?.substringBeforeLast('.')
        val pathsToCheck = buildList {
            copy?.let(::add)
            contentRoot?.let(::add)
            coverPath?.takeIf { it.isNotBlank() }?.let(::add)
        }
        if (copy != null) {
            if (folderCopy) {
                deleteTree(copy)
            } else {
                runCatching { fileSystem.delete(copy) }
                // Text bodies/images and CBZ pages live beside the copy in
                // the content-addressed book directory.
                contentRoot?.let(::deleteTree)
            }
        }
        coverPath?.takeIf { it.isNotBlank() }?.let { runCatching { fileSystem.delete(it) } }
        val leftovers = pathsToCheck.filter { path ->
            runCatching { fileSystem.exists(path) }.getOrDefault(false)
        }
        if (leftovers.isNotEmpty()) {
            // The row is gone; a later Data-screen cleanup can reclaim these.
            MiruLog.event(
                "library",
                "local-remove-leftovers",
                "paths" to leftovers.size,
            )
        }
        deletePdfPageCache(row.bookId)
        true
    }

    private fun deletePdfPageCache(bookId: Long) {
        cacheDir?.let { File(File(it, "pdf_pages"), bookId.toString()).deleteRecursively() }
    }

    private fun deleteTree(path: String): Boolean {
        runCatching { fileSystem.list(path) }.getOrDefault(emptyList()).forEach(::deleteTree)
        return runCatching {
            fileSystem.delete(path)
            !fileSystem.exists(path)
        }.getOrDefault(false)
    }

    private suspend fun resolve(bookUrl: String): Pair<LocalSource, String>? {
        val row = library.book(bookUrl) ?: return null
        val file = library.localBookFile(row.bookId) ?: return null
        val path = file.copyPath?.takeIf { it.isNotBlank() } ?: return null
        val contentType = localBookContentType(row.installId, file.format)
        return LocalSource(
            fileSystem = fileSystem,
            libraryDir = path.substringBeforeLast('/'),
            contentType = contentType,
            fileResolver = { relative -> File(baseDir, relative) },
        ) to path
    }

    companion object {
        fun chapterUrl(bookUrl: String, index: Int): String = "$bookUrl#chapter=$index"

        fun isLocalBook(bookUrl: String): Boolean = bookUrl.startsWith("local://")
    }
}

/**
 * Post-commit file removal shared by the bulk and single-book paths. Runs on
 * IO so the DB transaction never waits on disk, and only touches paths under
 * the app-private filesystem (SAF/document URIs are never passed here).
 */
internal suspend fun removeLibraryFiles(
    fileSystem: FileSystem,
    files: List<LibraryBookFiles>,
) = withContext(Dispatchers.IO) {
    files.forEach { book ->
        val copy = book.copyPath?.trim()?.takeIf { it.isNotEmpty() }
        when {
            copy == null -> Unit
            book.folderCopy -> deleteFileTree(fileSystem, copy)
            else -> {
                fileSystem.delete(copy)
                deleteFileTree(fileSystem, copy.substringBeforeLast('.'))
            }
        }
        book.bodyPaths.forEach { fileSystem.delete(it) }
        book.pagePathsJson.flatMap(::jsonStringArray)
            .filter { it.isNotBlank() }
            .forEach(fileSystem::delete)
        book.coverPath?.takeIf { it.isNotBlank() }?.let(fileSystem::delete)
    }
}

private fun deleteFileTree(fileSystem: FileSystem, path: String) {
    fileSystem.list(path).forEach { child -> deleteFileTree(fileSystem, child) }
    runCatching { fileSystem.delete(path) }
}

/** Ordered <img> blocks for one chapter_pages row (CBZ, folder import or PDF). */
internal fun pageImagesHtml(baseDir: File, paths: List<String>): String =
    paths.joinToString("") { path ->
        // Only a rendered-PDF reference is already a URL; every other row is a
        // path relative to the book directory.
        val src = if (path.startsWith("pdfpage://")) path
        else File(baseDir, path).toURI().toString()
        "<p><img src=\"${htmlAttribute(src)}\"/></p>"
    }

internal data class InlinePage(val position: Int, val url: String)

// Splits the plain chapter body at image offsets and emits the same paragraph
// HTML the text-only path uses, with one <p><img/></p> per image.
internal fun interleaveChapterText(body: String, pages: List<InlinePage>): String {
    if (pages.isEmpty()) return textToParagraphHtml(body)
    val out = StringBuilder()
    var cursor = 0
    for (page in pages.sortedBy { it.position }) {
        val position = page.position.coerceIn(cursor, body.length)
        out.append(textToParagraphHtml(body.substring(cursor, position)))
        out.append("<p><img src=\"").append(htmlAttribute(page.url)).append("\"/></p>")
        cursor = position
    }
    out.append(textToParagraphHtml(body.substring(cursor)))
    return out.toString()
}

internal fun imageExtension(mediaType: String?, bytes: ByteArray): String {
    val type = mediaType?.lowercase().orEmpty()
    return when {
        "png" in type -> "png"
        "webp" in type -> "webp"
        "gif" in type -> "gif"
        "bmp" in type -> "bmp"
        "jpeg" in type || "jpg" in type -> "jpg"
        startsWith(bytes, 0x89, 0x50, 0x4E, 0x47) -> "png"
        startsWith(bytes, 0xFF, 0xD8, 0xFF) -> "jpg"
        startsWith(bytes, 0x47, 0x49, 0x46) -> "gif"
        startsWith(bytes, 0x42, 0x4D) -> "bmp"
        startsWith(bytes, 0x52, 0x49, 0x46, 0x46) -> "webp"
        else -> "jpg"
    }
}

private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean =
    bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }

private fun htmlAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
