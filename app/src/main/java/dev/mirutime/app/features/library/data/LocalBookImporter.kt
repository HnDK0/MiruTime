package dev.mirutime.app.features.library.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.local.CbzFileReader
import dev.mirutime.core.local.ComicInfoMeta
import dev.mirutime.core.local.EpubFileReader
import dev.mirutime.core.local.Fb2FileReader
import dev.mirutime.core.local.LocalChapter
import dev.mirutime.core.local.parseComicInfo
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.BookTagEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterPageEntity
import dev.mirutime.data.library.ChapterSeed
import dev.mirutime.data.library.ImportJobEntity
import dev.mirutime.data.library.LibraryRepository
import dev.mirutime.data.library.LocalBookFileEntity
import dev.mirutime.data.library.TagEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One supported local format, its install/source id and content-addressed name. */
internal enum class LocalImportFormat(
    val installId: String,
    val format: String,
    val extension: String,
) {
    EPUB(LocalBooksInstallId, "EPUB", "epub"),
    FB2(LocalBooksInstallId, "FB2", "fb2"),
    CBZ(LocalMangaInstallId, "CBZ", "cbz"),
    ;

    val contentType: ContentType
        get() = if (this == CBZ) ContentType.MANGA else ContentType.NOVEL

    val isManga: Boolean get() = this == CBZ

    companion object {
        fun forFile(fileName: String, mime: String? = null): LocalImportFormat? {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            entries.firstOrNull { it.extension == extension }?.let { return it }
            return when (mime?.lowercase()) {
                "application/epub+zip" -> EPUB
                "application/x-fictionbook+xml", "application/x-fictionbook" -> FB2
                "application/vnd.comicbook+zip", "application/x-cbz" -> CBZ
                else -> null
            }
        }
    }
}

internal fun localCopyPath(format: LocalImportFormat, sha256: String): String =
    "local_books/${format.installId}/$sha256.${format.extension}"

// ── image-folder layout (pure, unit-testable) ───────────────────────────────

internal val PageImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

internal data class FolderTree(
    val name: String,
    val files: List<String> = emptyList(),
    val dirs: List<FolderTree> = emptyList(),
)

internal sealed interface FolderLayout {
    data class SingleChapter(val pages: List<String>) : FolderLayout
    data class ChapterDirs(val chapters: List<FolderChapter>) : FolderLayout
    data object None : FolderLayout
}

internal data class FolderChapter(val name: String, val pages: List<String>)

// One CBZ archive is one manga chapter; the stage keeps every page path in
// natural order and its first page as the cover.
internal data class CbzPageStage(
    val paths: List<String>,
    val totalBytes: Long,
    val coverBytes: ByteArray?,
)

internal fun cbzChapterSeed(bookUrl: String, title: String): ChapterSeed = ChapterSeed(
    index = 1,
    url = LocalBookProvider.chapterUrl(bookUrl, 1),
    title = title,
)

/**
 * Copies the archive pages under the book directory using the folder-import
 * layout (`chapters/1/00001.ext`). Empty archives and unreadable pages fail
 * before a chapter is persisted; already-written pages are removed on error so
 * the outer import cleanup sees no partial payload.
 */
internal fun stageCbzPages(
    bookDir: String,
    imageNames: List<String>,
    readPage: (String) -> ByteArray?,
    fileSystem: FileSystem,
    chapterIndex: Int = 1,
): CbzPageStage {
    if (imageNames.isEmpty()) throw IllegalStateException("CBZ has no images")
    val paths = mutableListOf<String>()
    var totalBytes = 0L
    var cover: ByteArray? = null
    try {
        imageNames.forEachIndexed { position, name ->
            val bytes = readPage(name) ?: throw IllegalStateException("Missing page: $name")
            if (bytes.isEmpty()) throw IllegalStateException("Empty page: $name")
            if (cover == null) cover = bytes
            val index = position + 1
            val extension = name.substringAfterLast('.', "").lowercase()
                .takeIf { it in PageImageExtensions } ?: "jpg"
            val path = "$bookDir/chapters/$chapterIndex/${index.toString().padStart(5, '0')}.$extension"
            fileSystem.write(path, bytes)
            if (!fileSystem.exists(path)) throw IllegalStateException("Cannot write page: $name")
            paths += path
            totalBytes += bytes.size
        }
    } catch (e: Exception) {
        paths.forEach { runCatching { fileSystem.delete(it) } }
        throw e
    }
    return CbzPageStage(paths = paths, totalBytes = totalBytes, coverBytes = cover)
}

internal fun isPageImageName(name: String): Boolean {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    if (base.startsWith(".") || base.startsWith("._")) return false
    val lower = base.lowercase()
    // Thumbnail caches are not pages.
    if (lower.contains("thumb")) return false
    return lower.substringAfterLast('.', "") in PageImageExtensions
}

internal fun naturalSortKey(name: String): String =
    name.replace(Regex("\\d+")) { it.value.padStart(12, '0') }

internal fun naturalCompare(a: String, b: String): Int =
    naturalSortKey(a).compareTo(naturalSortKey(b))

internal fun isChapterDirName(name: String): Boolean {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    if (base.startsWith(".")) return false
    return !base.lowercase().contains("thumb")
}

internal fun detectFolderLayout(root: FolderTree): FolderLayout {
    // Chapter subdirectories win over a stray root cover.jpg/ComicInfo.xml.
    val chapters = root.dirs
        .filter { isChapterDirName(it.name) }
        .mapNotNull { dir ->
            val pages = dir.files.filter(::isPageImageName).sortedWith(compareBy(::naturalSortKey))
            if (pages.isEmpty()) null else FolderChapter(dir.name, pages)
        }
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
    if (chapters.isNotEmpty()) return FolderLayout.ChapterDirs(chapters)
    val rootPages = root.files.filter(::isPageImageName).sortedWith(compareBy(::naturalSortKey))
    return if (rootPages.isEmpty()) FolderLayout.None else FolderLayout.SingleChapter(rootPages)
}

// Only the basename is used in app storage; separators/traversal prefixes are
// stripped and unsafe names get a deterministic fallback.
internal fun safePageName(name: String, fallback: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
    if (base.isEmpty() || base == "." || base == ".." || base.any { it.code < 0x20 }) {
        return fallback
    }
    return base
}

internal fun folderTitle(comic: ComicInfoMeta?, folderName: String): String =
    comic?.displayTitle?.takeIf { it.isNotBlank() }
        ?: folderName.takeIf { it.isNotBlank() }
        ?: "Untitled"

internal fun folderAuthor(comic: ComicInfoMeta?): String =
    listOfNotNull(
        comic?.author?.takeIf { it.isNotBlank() },
        comic?.artist?.takeIf { it.isNotBlank() },
    ).distinct().joinToString(", ").ifBlank { "Unknown" }

private const val MAX_TREE_DEPTH = 8
private const val MAX_COMIC_INFO_BYTES = 512L * 1024

// Streamed per-file cap; the source is never materialized as one ByteArray.
// Folder pages have their own smaller per-page cap and total budget.
private const val MAX_IMPORT_BYTES = 128L * 1024 * 1024
private const val MAX_PAGE_BYTES = 32L * 1024 * 1024
private const val MAX_FOLDER_BYTES = 512L * 1024 * 1024


internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

internal fun readLimited(input: InputStream, maxBytes: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

// Catalogue taps import transiently (inLibrary=false); "Import all" and the
// BookScreen action persist membership. Kept as one pure mapping so the two
// row builders cannot drift.
internal data class LocalImportMembership(
    val inLibrary: Boolean,
    val addedToLibraryAt: Long?,
)

internal fun localImportMembership(
    addToLibrary: Boolean,
    timestamp: Long,
): LocalImportMembership = LocalImportMembership(
    inLibrary = addToLibrary,
    addedToLibraryAt = timestamp.takeIf { addToLibrary },
)

sealed class LocalImportResult {
    data class Success(
        val bookId: Long,
        val bookUrl: String,
        val title: String,
        val duplicate: Boolean,
    ) : LocalImportResult()

    data class Failure(val message: String) : LocalImportResult()
}

// Streaming copy with digest/cap. Deletes its temp file on any failure or
// cancellation, before rethrowing to the import job's cleanup.
internal suspend fun streamToTemp(
    input: InputStream,
    temp: File,
    maxBytes: Long,
    onChunk: () -> Unit = {},
): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    try {
        FileOutputStream(temp).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IllegalStateException("File too large")
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
                onChunk()
            }
            out.fd.sync()
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to total
    } catch (e: Throwable) {
        temp.delete()
        throw e
    }
}

internal data class PreparedChapterBody(val path: String, val size: Long, val sha256: String)

// Writes inline images and the interleaved HTML body under the book directory;
// the importer only persists the returned metadata. Extracted so the file
// layout and HTML can be tested with a fake FileSystem.
internal fun prepareChapterBody(
    chapter: LocalChapter,
    bodyKey: String,
    chapterIndex: Int,
    bookDir: String,
    baseDir: File,
    fileSystem: FileSystem,
): PreparedChapterBody {
    val imageDir = "$bookDir/images/$chapterIndex"
    val pages = chapter.images.mapIndexedNotNull { imageIndex, image ->
        if (image.bytes.isEmpty()) return@mapIndexedNotNull null
        val extension = imageExtension(image.mediaType, image.bytes)
        val imagePath = "$imageDir/${imageIndex + 1}.$extension"
        fileSystem.write(imagePath, image.bytes)
        if (!fileSystem.exists(imagePath)) {
            throw IllegalStateException("Cannot write chapter image: $imagePath")
        }
        InlinePage(
            position = image.position,
            url = File(baseDir, imagePath).toURI().toString(),
        )
    }
    val bodyPath = "$bookDir/chapters/$bodyKey.html"
    val bodyBytes = interleaveChapterText(chapter.body, pages).encodeToByteArray()
    fileSystem.write(bodyPath, bodyBytes)
    if (!fileSystem.exists(bodyPath)) {
        throw IllegalStateException("Cannot write chapter body: $bodyKey")
    }
    return PreparedChapterBody(bodyPath, bodyBytes.size.toLong(), sha256Hex(bodyBytes))
}

private data class SafFile(val name: String, val uri: Uri)

private data class SafDir(
    val name: String,
    val files: List<SafFile> = emptyList(),
    val dirs: List<SafDir> = emptyList(),
) {
    fun toTree(): FolderTree = FolderTree(
        name = name,
        files = files.map { it.name },
        dirs = dirs.map { it.toTree() },
    )
}

private data class ChapterPlan(
    val title: String,
    val pages: List<SafFile>,
    val comic: ComicInfoMeta?,
)

/**
 * Imports a SAF-picked EPUB/FB2/CBZ into the library:
 * copy under filesDir -> sha256 identity -> parse with core LocalSource ->
 * book + local file + chapters + import job rows.
 */
class LocalBookImporter(
    private val context: Context,
    private val library: LibraryRepository,
    private val fileSystem: FileSystem,
    private val baseDir: File,
    private val coverStore: BookCoverStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxBytes: Long = MAX_IMPORT_BYTES,
) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun import(uri: Uri, addToLibrary: Boolean = true): LocalImportResult {
        val displayName = displayName(uri)
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val format = LocalImportFormat.forFile(displayName, mime)
            ?: return LocalImportResult.Failure("Unsupported file: $displayName")

        var job = ImportJobEntity(
            kind = format.installId,
            status = "RUNNING",
            progress = 0.05,
            createdAt = now(),
        )
        job = job.copy(id = library.saveImportJob(job))
        val temp = File(context.applicationContext.cacheDir, "local-import-${newId()}.tmp")
        var createdBookId: Long? = null
        var copyPath: String? = null
        var bookDir: String? = null
        try {
            val known = fileSize(uri)
            if (known != null && known > maxBytes) {
                throw IllegalStateException("File too large ($known bytes)")
            }
            val (sha256, size) = copyToTemp(uri, temp)
            update(job, progress = 0.25)

            val existing = library.localBookFileBySha(sha256)
            val existingBook = existing?.let { library.book(it.bookId) }
            if (existingBook != null) {
                // Re-importing from "Import all" is the explicit library add;
                // opening a transient book again must not change membership.
                if (addToLibrary && !existingBook.inLibrary) {
                    library.addToLibrary(existingBook.bookId, now())
                }
                temp.delete()
                update(job, progress = 1.0, status = "COMPLETE")
                return LocalImportResult.Success(
                    bookId = existingBook.bookId,
                    bookUrl = existingBook.url,
                    title = existingBook.title,
                    duplicate = true,
                )
            }

            val target = localCopyPath(format, sha256)
            commitTemp(temp, target)
            copyPath = target
            bookDir = "local_books/${format.installId}/$sha256"
            deleteTree(bookDir)
            val file = File(baseDir, target)
            update(job, progress = 0.4)

            val timestamp = now()
            val bookUrl = "local://${newId()}"
            val fallbackTitle = displayName.substringBeforeLast('.').ifBlank { "Untitled" }
            val title: String
            when (format) {
                LocalImportFormat.EPUB -> EpubFileReader(file).use { reader ->
                    title = reader.title.trim().ifBlank { fallbackTitle }
                    val author = reader.author?.trim().orEmpty()
                    val bookId = saveBook(
                        format = format,
                        bookUrl = bookUrl,
                        title = title,
                        author = author,
                        description = reader.description.orEmpty(),
                        genres = emptyList(),
                        timestamp = timestamp,
                        inLibrary = addToLibrary,
                    )
                    createdBookId = bookId
                    saveFileRow(bookId, uri, displayName, format, mime, size, sha256, target, timestamp)
                    val count = persistTextChapters(
                        bookId = bookId,
                        bookUrl = bookUrl,
                        bookDir = bookDir,
                        timestamp = timestamp,
                    ) { consumer -> reader.forEachChapter(consumer) }
                    library.updateTotalChapters(bookId, count)
                    storeCover(bookId, reader.coverBytes)
                }
                LocalImportFormat.FB2 -> Fb2FileReader(file).let { reader ->
                    title = reader.book.title.trim().ifBlank { fallbackTitle }
                    val author = reader.book.author?.trim().orEmpty()
                    val bookId = saveBook(
                        format = format,
                        bookUrl = bookUrl,
                        title = title,
                        author = author,
                        description = reader.book.description.orEmpty(),
                        genres = emptyList(),
                        timestamp = timestamp,
                        inLibrary = addToLibrary,
                    )
                    createdBookId = bookId
                    saveFileRow(bookId, uri, displayName, format, mime, size, sha256, target, timestamp)
                    val count = persistTextChapters(
                        bookId = bookId,
                        bookUrl = bookUrl,
                        bookDir = bookDir,
                        timestamp = timestamp,
                    ) { consumer -> reader.forEachChapter(consumer) }
                    library.updateTotalChapters(bookId, count)
                    storeCover(bookId, reader.book.coverBytes)
                }
                LocalImportFormat.CBZ -> CbzFileReader(file).use { reader ->
                    title = reader.title.trim().ifBlank { fallbackTitle }
                    val bookId = saveBook(
                        format = format,
                        bookUrl = bookUrl,
                        title = title,
                        author = "Unknown",
                        description = reader.description.orEmpty(),
                        genres = emptyList(),
                        timestamp = timestamp,
                        inLibrary = addToLibrary,
                    )
                    createdBookId = bookId
                    saveFileRow(bookId, uri, displayName, format, mime, size, sha256, target, timestamp)
                    val staged = persistCbzPages(
                        bookId = bookId,
                        bookUrl = bookUrl,
                        bookDir = bookDir,
                        title = title,
                        reader = reader,
                        timestamp = timestamp,
                    )
                    library.updateTotalChapters(bookId, 1)
                    // The first natural page is the CBZ cover.
                    storeCover(bookId, staged.coverBytes)
                }
            }
            update(job, progress = 1.0, status = "COMPLETE")
            return LocalImportResult.Success(
                bookId = createdBookId ?: 0L,
                bookUrl = bookUrl,
                title = title,
                duplicate = false,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                cleanupImport(createdBookId, copyPath, bookDir)
                update(job, progress = 0.0, status = "FAILED", error = "Cancelled")
            }
            throw e
        } catch (e: Exception) {
            cleanupImport(createdBookId, copyPath, bookDir)
            val message = e.message ?: e::class.simpleName ?: "Import failed"
            update(job, progress = 0.0, status = "FAILED", error = message)
            return LocalImportResult.Failure(message)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private suspend fun cleanupImport(bookId: Long?, copyPath: String?, bookDir: String?) {
        bookId?.let { runCatching { library.deleteBook(it) } }
        copyPath?.let { runCatching { fileSystem.delete(it) } }
        bookDir?.let(::deleteTree)
    }

    private fun fileSize(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    // Streams SAF -> cache temp, hashing and enforcing the cap while copying.
    private suspend fun copyToTemp(uri: Uri, temp: File): Pair<String, Long> {
        val input = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot read file")
        return input.use { source -> streamToTemp(source, temp, maxBytes) }
    }

    // fsync'ed temp -> app storage; never leaves a partial target path.
    private fun commitTemp(temp: File, target: String) {
        val targetFile = File(baseDir, target)
        try {
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) targetFile.delete()
            if (temp.renameTo(targetFile)) return
            FileOutputStream(targetFile).use { out ->
                temp.inputStream().use { input -> input.copyTo(out) }
                out.fd.sync()
            }
            temp.delete()
        } catch (e: Exception) {
            targetFile.delete()
            throw e
        }
    }

    private suspend fun saveBook(
        format: LocalImportFormat,
        bookUrl: String,
        title: String,
        author: String,
        description: String,
        genres: List<String>,
        timestamp: Long,
        inLibrary: Boolean,
    ): Long = library.saveBook(
        BookEntity(
            installId = format.installId,
            url = bookUrl,
            title = title,
            author = author.ifBlank { "Unknown" },
            coverUrl = null,
            contentType = if (format.isManga) "Manga" else "Novel",
            rating = 0.0,
            shelfId = "reading",
            totalChapters = 0,
            inLibrary = localImportMembership(inLibrary, timestamp).inLibrary,
            addedAt = timestamp,
            updatedAt = timestamp,
            lastReadAt = null,
            lastChapterId = null,
            lastChapterIndex = null,
            chapterListHash = null,
            lastUpdateAt = null,
            genresJson = JSONArray(genres).toString(),
            firstSeenAt = timestamp,
            addedToLibraryAt = localImportMembership(inLibrary, timestamp).addedToLibraryAt,
            authorRaw = author,
            authorsJson = JSONArray(listOfNotNull(author.takeIf { it.isNotBlank() })).toString(),
            description = description,
            metadataSource = "LOCAL",
            metadataUpdatedAt = timestamp,
        ),
    )

    private suspend fun saveFileRow(
        bookId: Long,
        uri: Uri,
        displayName: String,
        format: LocalImportFormat,
        mime: String?,
        size: Long,
        sha256: String,
        target: String,
        timestamp: Long,
    ) {
        library.saveLocalBookFile(
            LocalBookFileEntity(
                bookId = bookId,
                uri = uri.toString(),
                displayName = displayName,
                format = format.format,
                mime = mime,
                sizeBytes = size,
                sha256 = sha256,
                copyPath = target,
                importedAt = timestamp,
            ),
        )
    }

    private suspend fun storeCover(bookId: Long, bytes: ByteArray?) {
        if (bytes == null) return
        runCatching { coverStore?.storeLocalCover(bookId, remoteUrl = null, bytes) }
    }

    private data class StagedBody(
        val index: Int,
        val path: String,
        val size: Long,
        val sha256: String,
    )

    // Parses/persists one chapter at a time; only body files and small seeds
    // survive between iterations.
    private suspend fun persistTextChapters(
        bookId: Long,
        bookUrl: String,
        bookDir: String,
        timestamp: Long,
        forEachChapter: ((LocalChapter) -> Unit) -> Unit,
    ): Int {
        val seeds = mutableListOf<ChapterSeed>()
        val staged = mutableListOf<StagedBody>()
        forEachChapter { chapter ->
            val index = seeds.size + 1
            val prepared = prepareChapterBody(
                chapter = chapter,
                bodyKey = "index-$index",
                chapterIndex = index,
                bookDir = bookDir,
                baseDir = baseDir,
                fileSystem = fileSystem,
            )
            staged += StagedBody(index, prepared.path, prepared.size, prepared.sha256)
            seeds += ChapterSeed(
                index = index,
                url = LocalBookProvider.chapterUrl(bookUrl, index),
                title = chapter.title,
            )
        }
        val saved = library.replaceChapters(bookId, seeds, timestamp)
        val byIndex = saved.associateBy { it.idx }
        for (body in staged) {
            val chapterId = byIndex[body.index]?.chapterId
                ?: throw IllegalStateException("Missing chapter ${body.index}")
            val finalPath = "$bookDir/chapters/$chapterId.html"
            if (body.path != finalPath) {
                if (fileSystem.exists(finalPath)) fileSystem.delete(finalPath)
                fileSystem.rename(body.path, finalPath)
                if (!fileSystem.exists(finalPath)) {
                    throw IllegalStateException("Cannot commit chapter body: ${body.index}")
                }
            }
            library.saveChapterBody(
                ChapterBodyEntity(
                    chapterId = chapterId,
                    format = "html",
                    path = finalPath,
                    bytes = body.size,
                    sha256 = body.sha256,
                    fetchedAt = timestamp,
                ),
            )
        }
        return seeds.size
    }

    private suspend fun persistCbzPages(
        bookId: Long,
        bookUrl: String,
        bookDir: String,
        title: String,
        reader: CbzFileReader,
        timestamp: Long,
    ): CbzPageStage {
        // CbzFileReader already sorts naturally; sorting again keeps the
        // contract explicit if the reader changes.
        val names = reader.imageNames.sortedWith(compareBy(::naturalSortKey))
        val staged = stageCbzPages(
            bookDir = bookDir,
            imageNames = names,
            readPage = reader::readPage,
            fileSystem = fileSystem,
        )
        // ONE manga chapter for the whole archive: the pager reads all pages
        // from chapter_pages.pagePathsJson, exactly like a folder import.
        val seed = cbzChapterSeed(bookUrl = bookUrl, title = title)
        val saved = library.replaceChapters(bookId, listOf(seed), timestamp)
        val chapterId = saved.firstOrNull { it.idx == seed.index }?.chapterId
            ?: throw IllegalStateException("Missing CBZ chapter")
        library.saveChapterPage(
            ChapterPageEntity(
                chapterId = chapterId,
                format = "images",
                pagePathsJson = JSONArray(staged.paths).toString(),
                totalBytes = staged.totalBytes,
                parserVersion = FolderParserVersion,
            ),
        )
        return staged
    }

    /**
     * Imports a SAF tree of loose manga images. Returns null when the tree has
     * no image layout (e.g. only CBZ files) so the caller can fall back to the
     * ordinary per-file import.
     */
    suspend fun importTree(
        uri: Uri,
        documentId: String? = null,
        addToLibrary: Boolean = true,
    ): LocalImportResult? {
        val rootId = documentId ?: rootDocumentId(uri)
        val rootName = treeRootName(uri, rootId)
        val root = walkTree(uri, rootId, rootName, depth = 0)
        val layout = detectFolderLayout(root.toTree())
        if (layout is FolderLayout.None) {
            // A directory of CBZ archives is a multi-chapter manga folder: it
            // has no image layout but is still readable without copying SAF.
            return importCbzSetTree(
                root = root,
                rootName = rootName,
                sourceDocumentUri = documentUri(uri, rootId).toString(),
                addToLibrary = addToLibrary,
            )
        }

        val rootComic = readComicInfo(root)
        val chapters = when (layout) {
            is FolderLayout.SingleChapter -> listOf(
                ChapterPlan(
                    title = rootName.ifBlank { "Chapter 1" },
                    pages = pageFiles(root, layout.pages),
                    comic = rootComic,
                ),
            )
            is FolderLayout.ChapterDirs -> layout.chapters.map { chapter ->
                val dir = root.dirs.firstOrNull { it.name == chapter.name }
                ChapterPlan(
                    title = dir?.let { readComicInfo(it)?.displayTitle } ?: chapter.name,
                    pages = dir?.let { pageFiles(it, chapter.pages) }.orEmpty(),
                    comic = dir?.let(::readComicInfo),
                )
            }
            FolderLayout.None -> return null
        }.filter { it.pages.isNotEmpty() }
        if (chapters.isEmpty()) return null

        var job = ImportJobEntity(
            kind = LocalMangaInstallId,
            status = "RUNNING",
            progress = 0.05,
            createdAt = now(),
        )
        job = job.copy(id = library.saveImportJob(job))
        val stagingRoot = "local_books/$LocalMangaInstallId/.import-${newId()}"
        val staged = mutableListOf<String>()
        var finalRoot: String? = null
        var createdBookId: Long? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val stagedChapters = chapters.mapIndexed { chapterIndex, chapter ->
                var chapterBytes = 0L
                val paths = chapter.pages.mapIndexed { pageIndex, page ->
                    val bytes = resolver.openInputStream(page.uri)?.use { readLimited(it, MAX_PAGE_BYTES) }
                        ?: throw IllegalStateException("Cannot read ${page.name}")
                    if (bytes.isEmpty()) throw IllegalStateException("Empty page: ${page.name}")
                    totalBytes += bytes.size
                    if (totalBytes > MAX_FOLDER_BYTES) throw IllegalStateException("Folder too large")
                    digest.update("$chapterIndex:${page.name}\u0000".encodeToByteArray())
                    digest.update(bytes)
                    val extension = page.name.substringAfterLast('.', "").lowercase()
                        .takeIf { it in PageImageExtensions } ?: "jpg"
                    val safe = safePageName(page.name, "page-${pageIndex + 1}.$extension")
                    val path = "$stagingRoot/chapters/${chapterIndex + 1}/" +
                        "${(pageIndex + 1).toString().padStart(5, '0')}-$safe"
                    fileSystem.write(path, bytes)
                    if (!fileSystem.exists(path)) throw IllegalStateException("Cannot copy ${page.name}")
                    staged += path
                    chapterBytes += bytes.size
                    path
                }
                StagedChapter(title = chapter.title, paths = paths, bytes = chapterBytes)
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            update(job, progress = 0.55)

            val existing = library.localBookFileBySha(sha256)
            val existingBook = existing?.let { library.book(it.bookId) }
            if (existingBook != null) {
                if (addToLibrary && !existingBook.inLibrary) {
                    library.addToLibrary(existingBook.bookId, now())
                }
                staged.forEach { runCatching { fileSystem.delete(it) } }
                staged.clear()
                deleteTree(stagingRoot)
                update(job, progress = 1.0, status = "COMPLETE")
                return LocalImportResult.Success(
                    bookId = existingBook.bookId,
                    bookUrl = existingBook.url,
                    title = existingBook.title,
                    duplicate = true,
                )
            }

            finalRoot = "local_books/$LocalMangaInstallId/$sha256"
            deleteTree(finalRoot)
            val finalChapters = stagedChapters.mapIndexed { index, chapter ->
                val chapterDir = "$finalRoot/chapters/${index + 1}"
                val paths = chapter.paths.mapIndexed { pageIndex, stagedPath ->
                    val fileName = stagedPath.substringAfterLast('/')
                    val finalPath = "$chapterDir/$fileName"
                    if (fileSystem.exists(finalPath)) fileSystem.delete(finalPath)
                    fileSystem.rename(stagedPath, finalPath)
                    if (!fileSystem.exists(finalPath)) throw IllegalStateException("Cannot move $finalPath")
                    finalPath
                }
                StagedChapter(title = chapter.title, paths = paths, bytes = chapter.bytes)
            }
            deleteTree(stagingRoot)
            staged.clear()
            update(job, progress = 0.75)

            val comic = rootComic ?: chapters.firstNotNullOfOrNull { it.comic }
            val title = folderTitle(comic, rootName)
            val author = folderAuthor(comic)
            val genres = comic?.genres.orEmpty()
            val timestamp = now()
            val bookUrl = "local://${newId()}"
            val bookId = library.saveBook(
                BookEntity(
                    installId = LocalMangaInstallId,
                    url = bookUrl,
                    title = title,
                    author = author,
                    coverUrl = null,
                    contentType = "Manga",
                    rating = 0.0,
                    shelfId = "reading",
                    totalChapters = finalChapters.size,
                    inLibrary = localImportMembership(addToLibrary, timestamp).inLibrary,
                    addedAt = timestamp,
                    updatedAt = timestamp,
                    lastReadAt = null,
                    lastChapterId = null,
                    lastChapterIndex = null,
                    chapterListHash = null,
                    lastUpdateAt = null,
                    genresJson = JSONArray(genres).toString(),
                    firstSeenAt = timestamp,
                    addedToLibraryAt = localImportMembership(addToLibrary, timestamp).addedToLibraryAt,
                    authorRaw = author,
                    authorsJson = JSONArray(listOfNotNull(author.takeIf { it != "Unknown" })).toString(),
                    description = comic?.summary.orEmpty(),
                    metadataSource = "LOCAL",
                    metadataUpdatedAt = timestamp,
                ),
            )
            createdBookId = bookId
            saveGenres(bookId, genres, timestamp)
            library.saveLocalBookFile(
                LocalBookFileEntity(
                    bookId = bookId,
                    uri = documentUri(uri, rootId).toString(),
                    displayName = rootName.ifBlank { "Manga folder" },
                    format = "CBZ",
                    mime = null,
                    sizeBytes = totalBytes,
                    sha256 = sha256,
                    copyPath = finalRoot,
                    parserVersion = FolderParserVersion,
                    importedAt = timestamp,
                ),
            )
            val savedChapters = library.replaceChapters(
                bookId = bookId,
                seeds = finalChapters.mapIndexed { index, chapter ->
                    ChapterSeed(
                        index = index + 1,
                        url = LocalBookProvider.chapterUrl(bookUrl, index + 1),
                        title = chapter.title,
                    )
                },
                at = timestamp,
            )
            val byIndex = savedChapters.associateBy { it.idx }
            finalChapters.forEachIndexed { index, chapter ->
                val chapterId = byIndex[index + 1]?.chapterId ?: return@forEachIndexed
                library.saveChapterPage(
                    ChapterPageEntity(
                        chapterId = chapterId,
                        format = "images",
                        pagePathsJson = JSONArray(chapter.paths).toString(),
                        totalBytes = chapter.bytes,
                        parserVersion = FolderParserVersion,
                    ),
                )
            }
            runCatching {
                finalChapters.firstOrNull()?.paths?.firstOrNull()?.let { firstPage ->
                    fileSystem.read(firstPage)?.let { bytes ->
                        coverStore?.storeLocalCover(bookId, remoteUrl = null, bytes)
                    }
                }
            }
            update(job, progress = 1.0, status = "COMPLETE")
            return LocalImportResult.Success(
                bookId = bookId,
                bookUrl = bookUrl,
                title = title,
                duplicate = false,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                cleanupFolderImport(createdBookId, staged, stagingRoot, finalRoot)
                update(job, progress = 0.0, status = "FAILED", error = "Cancelled")
            }
            throw e
        } catch (e: Exception) {
            cleanupFolderImport(createdBookId, staged, stagingRoot, finalRoot)
            val message = e.message ?: e::class.simpleName ?: "Folder import failed"
            update(job, progress = 0.0, status = "FAILED", error = message)
            return LocalImportResult.Failure(message)
        }
    }

    // A CBZ set is a folder of archives: one archive = one chapter. Pages are
    // copied into the same app-private layout as image folders, so the reader
    // does not care how the manga arrived. Source SAF bytes are never modified.
    private suspend fun importCbzSetTree(
        root: SafDir,
        rootName: String,
        sourceDocumentUri: String,
        addToLibrary: Boolean,
    ): LocalImportResult? {
        val cbzFiles = root.files
            .filter { it.name.lowercase().endsWith(".cbz") }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        if (cbzFiles.isEmpty()) return null

        var job = ImportJobEntity(
            kind = LocalMangaInstallId,
            status = "RUNNING",
            progress = 0.05,
            createdAt = now(),
        )
        job = job.copy(id = library.saveImportJob(job))
        val stagingRoot = "local_books/$LocalMangaInstallId/.import-${newId()}"
        val staged = mutableListOf<String>()
        var finalRoot: String? = null
        var createdBookId: Long? = null
        data class CbzChapter(val title: String, val paths: List<String>, val bytes: Long, val cover: ByteArray?)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val chapters = cbzFiles.mapIndexed { chapterIndex, cbz ->
                val temp = File(context.applicationContext.cacheDir, "local-cbz-${newId()}.tmp")
                try {
                    val known = fileSize(cbz.uri)
                    if (known != null && known > maxBytes) {
                        throw IllegalStateException("File too large ($known bytes)")
                    }
                    val (sha, size) = copyToTemp(cbz.uri, temp)
                    digest.update("$chapterIndex:${cbz.name}:$sha\u0000".encodeToByteArray())
                    totalBytes += size
                    if (totalBytes > MAX_FOLDER_BYTES) throw IllegalStateException("Folder too large")
                    CbzFileReader(temp).use { reader ->
                        val names = reader.imageNames.sortedWith(compareBy(::naturalSortKey))
                        val stage = stageCbzPages(
                            bookDir = stagingRoot,
                            imageNames = names,
                            readPage = reader::readPage,
                            fileSystem = fileSystem,
                            chapterIndex = chapterIndex + 1,
                        )
                        staged += stage.paths
                        val title = reader.title
                            .takeIf { it.isNotBlank() && it != "Unknown Title" }
                            ?: cbz.name.substringBeforeLast('.').ifBlank { "Chapter ${chapterIndex + 1}" }
                        CbzChapter(
                            title = title,
                            paths = stage.paths,
                            bytes = stage.totalBytes,
                            cover = stage.coverBytes,
                        )
                    }
                } finally {
                    temp.delete()
                }
            }
            update(job, progress = 0.55)
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

            val existing = library.localBookFileBySha(sha256)
            val existingBook = existing?.let { library.book(it.bookId) }
            if (existingBook != null) {
                if (addToLibrary && !existingBook.inLibrary) {
                    library.addToLibrary(existingBook.bookId, now())
                }
                staged.forEach { runCatching { fileSystem.delete(it) } }
                staged.clear()
                deleteTree(stagingRoot)
                update(job, progress = 1.0, status = "COMPLETE")
                return LocalImportResult.Success(
                    bookId = existingBook.bookId,
                    bookUrl = existingBook.url,
                    title = existingBook.title,
                    duplicate = true,
                )
            }

            finalRoot = "local_books/$LocalMangaInstallId/$sha256"
            deleteTree(finalRoot)
            val finalChapters = chapters.mapIndexed { index, chapter ->
                val chapterDir = "$finalRoot/chapters/${index + 1}"
                val paths = chapter.paths.mapIndexed { pageIndex, stagedPath ->
                    val fileName = stagedPath.substringAfterLast('/')
                    val finalPath = "$chapterDir/$fileName"
                    if (fileSystem.exists(finalPath)) fileSystem.delete(finalPath)
                    fileSystem.rename(stagedPath, finalPath)
                    if (!fileSystem.exists(finalPath)) throw IllegalStateException("Cannot move $finalPath")
                    finalPath
                }
                chapter.copy(paths = paths)
            }
            deleteTree(stagingRoot)
            staged.clear()
            update(job, progress = 0.75)

            val timestamp = now()
            val bookUrl = "local://${newId()}"
            val title = rootName.ifBlank { "Manga folder" }
            val bookId = library.saveBook(
                BookEntity(
                    installId = LocalMangaInstallId,
                    url = bookUrl,
                    title = title,
                    author = "Unknown",
                    coverUrl = null,
                    contentType = "Manga",
                    rating = 0.0,
                    shelfId = "reading",
                    totalChapters = finalChapters.size,
                    inLibrary = localImportMembership(addToLibrary, timestamp).inLibrary,
                    addedAt = timestamp,
                    updatedAt = timestamp,
                    lastReadAt = null,
                    lastChapterId = null,
                    lastChapterIndex = null,
                    chapterListHash = null,
                    lastUpdateAt = null,
                    genresJson = "[]",
                    firstSeenAt = timestamp,
                    addedToLibraryAt = localImportMembership(addToLibrary, timestamp).addedToLibraryAt,
                    authorRaw = "",
                    authorsJson = "[]",
                    description = "",
                    metadataSource = "LOCAL",
                    metadataUpdatedAt = timestamp,
                ),
            )
            createdBookId = bookId
            library.saveLocalBookFile(
                LocalBookFileEntity(
                    bookId = bookId,
                    uri = sourceDocumentUri,
                    displayName = title,
                    format = "CBZ",
                    mime = null,
                    sizeBytes = totalBytes,
                    sha256 = sha256,
                    copyPath = finalRoot,
                    parserVersion = FolderParserVersion,
                    importedAt = timestamp,
                ),
            )
            val savedChapters = library.replaceChapters(
                bookId = bookId,
                seeds = finalChapters.mapIndexed { index, chapter ->
                    ChapterSeed(
                        index = index + 1,
                        url = LocalBookProvider.chapterUrl(bookUrl, index + 1),
                        title = chapter.title,
                    )
                },
                at = timestamp,
            )
            val byIndex = savedChapters.associateBy { it.idx }
            finalChapters.forEachIndexed { index, chapter ->
                val chapterId = byIndex[index + 1]?.chapterId ?: return@forEachIndexed
                library.saveChapterPage(
                    ChapterPageEntity(
                        chapterId = chapterId,
                        format = "images",
                        pagePathsJson = JSONArray(chapter.paths).toString(),
                        totalBytes = chapter.bytes,
                        parserVersion = FolderParserVersion,
                    ),
                )
            }
            runCatching {
                finalChapters.firstOrNull()?.cover?.let { bytes ->
                    coverStore?.storeLocalCover(bookId, remoteUrl = null, bytes)
                }
            }
            update(job, progress = 1.0, status = "COMPLETE")
            return LocalImportResult.Success(
                bookId = bookId,
                bookUrl = bookUrl,
                title = title,
                duplicate = false,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                cleanupFolderImport(createdBookId, staged, stagingRoot, finalRoot)
                update(job, progress = 0.0, status = "FAILED", error = "Cancelled")
            }
            throw e
        } catch (e: Exception) {
            cleanupFolderImport(createdBookId, staged, stagingRoot, finalRoot)
            val message = e.message ?: e::class.simpleName ?: "CBZ set import failed"
            update(job, progress = 0.0, status = "FAILED", error = message)
            return LocalImportResult.Failure(message)
        }
    }

    private suspend fun cleanupFolderImport(
        bookId: Long?,
        staged: List<String>,
        stagingRoot: String,
        finalRoot: String?,
    ) {
        bookId?.let { id ->
            runCatching { library.bookCover(id)?.localPath }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(fileSystem::delete)
            runCatching { library.deleteBook(id) }
        }
        staged.forEach { runCatching { fileSystem.delete(it) } }
        deleteTree(stagingRoot)
        finalRoot?.let(::deleteTree)
    }

    private data class StagedChapter(
        val title: String,
        val paths: List<String>,
        val bytes: Long,
    )

    private fun pageFiles(dir: SafDir, names: List<String>): List<SafFile> {
        val byName = dir.files.associateBy { it.name }
        return names.mapNotNull { byName[it] }
    }

    private fun treeRootName(uri: Uri, documentId: String): String =
        displayName(documentUri(uri, documentId))
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Manga folder"

    private fun rootDocumentId(uri: Uri): String =
        if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }

    private fun childDocumentsUri(uri: Uri, parentId: String): Uri? {
        val authority = uri.authority ?: return null
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentId)
        } else {
            DocumentsContract.buildChildDocumentsUri(authority, parentId)
        }
    }

    private fun documentUri(uri: Uri, documentId: String): Uri {
        val authority = uri.authority ?: return uri
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        } else {
            DocumentsContract.buildDocumentUri(authority, documentId)
        }
    }

    // SAF-only walk: every child comes from buildChildDocumentsUriUsingTree,
    // so nothing outside the picked tree can be reached. Depth is bounded and
    // hidden/metadata files are filtered by the pure layout detector.
    private fun walkTree(
        treeUri: Uri,
        documentId: String,
        name: String,
        depth: Int,
    ): SafDir {
        if (depth > MAX_TREE_DEPTH) return SafDir(name)
        val files = mutableListOf<SafFile>()
        val dirs = mutableListOf<SafDir>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        runCatching {
            childDocumentsUri(treeUri, documentId)?.let { childrenUri ->
                resolver.query(childrenUri, projection, null, null, null)
            }?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childName = cursor.getString(0) ?: continue
                    val childId = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs += walkTree(treeUri, childId, childName, depth + 1)
                    } else {
                        files += SafFile(
                            name = childName,
                            uri = documentUri(treeUri, childId),
                        )
                    }
                }
            }
        }
        return SafDir(name = name, files = files, dirs = dirs)
    }

    private fun readComicInfo(dir: SafDir): ComicInfoMeta? {
        val file = dir.files.firstOrNull { it.name.equals("ComicInfo.xml", ignoreCase = true) }
            ?: return null
        return runCatching {
            resolver.openInputStream(file.uri)?.use { readLimited(it, MAX_COMIC_INFO_BYTES) }
                ?.let(::parseComicInfo)
        }.getOrNull()
    }

    private fun deleteTree(path: String) {
        fileSystem.list(path).forEach { child -> deleteTree(child) }
        runCatching { fileSystem.delete(path) }
    }

    private suspend fun saveGenres(bookId: Long, genres: List<String>, timestamp: Long) {
        library.deleteBookTags(bookId)
        var position = 0
        for (name in genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val normalized = name.lowercase().replace(Regex("\\s+"), " ")
            var tag = library.tagByNormalized(normalized)
            if (tag == null) {
                library.saveTag(
                    TagEntity(
                        name = name,
                        normalizedName = normalized,
                        kind = "GENRE",
                        createdAt = timestamp,
                    ),
                )
                tag = library.tagByNormalized(normalized)
            }
            tag?.let {
                library.saveBookTag(
                    BookTagEntity(
                        bookId = bookId,
                        tagId = it.tagId,
                        source = "LOCAL",
                        position = position++,
                    ),
                )
            }
        }
    }

    private suspend fun update(
        job: ImportJobEntity,
        progress: Double,
        status: String = "RUNNING",
        error: String? = null,
    ): ImportJobEntity {
        val finished = status == "COMPLETE" || status == "FAILED"
        val updated = job.copy(
            progress = progress,
            status = status,
            error = error,
            finishedAt = if (finished) now() else null,
        )
        library.saveImportJob(updated)
        return updated
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: uri.toString().substringAfterLast('/')
    }
}
