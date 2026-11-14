package dev.mirutime.app.features.finder.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.mirutime.core.text.textToParagraphHtml
import dev.mirutime.app.features.library.data.InlinePage
import dev.mirutime.app.features.library.data.LocalBooksInstallId
import dev.mirutime.app.features.library.data.LocalMangaInstallId
import dev.mirutime.app.features.local.data.MAX_LOCAL_SCAN_DEPTH
import dev.mirutime.app.features.local.data.PageImageExtensions
import dev.mirutime.app.features.library.data.imageExtension
import dev.mirutime.app.features.library.data.interleaveChapterText
import dev.mirutime.app.features.local.data.naturalCompare
import dev.mirutime.app.features.local.data.naturalSortKey
import dev.mirutime.core.local.CbzFileReader
import dev.mirutime.core.local.EpubFileReader
import dev.mirutime.core.local.Fb2FileReader
import dev.mirutime.core.local.TxtFileReader
import dev.mirutime.core.log.MiruLog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Direct-SAF failures are typed so BookScreen can show a real message/retry
// instead of spinning on a null book forever.
enum class SafFailure { NotFound, PermissionDenied, Unsupported, Damaged, TooLarge, Invalid }

class LocalSafException(
    val failure: SafFailure,
    message: String,
) : Exception(message)

data class SafDocument(
    val uri: String,
    val documentId: String,
    val name: String,
    val mime: String?,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val isDirectory: Boolean,
    // Tree URI used for child queries; document URIs are built with it.
    val treeUri: String?,
)

data class SafChild(
    val uri: String,
    val documentId: String,
    val name: String,
    val sizeBytes: Long?,
    val lastModified: Long?,
    val isDirectory: Boolean,
)

interface SafResolver {
    suspend fun document(documentUri: String): SafDocument
    suspend fun children(treeUri: String, documentId: String): List<SafChild>
    suspend fun openInput(documentUri: String): InputStream?
}

class AndroidSafResolver(context: Context) : SafResolver {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun document(documentUri: String): SafDocument = withContext(Dispatchers.IO) {
        val parsed = runCatching { Uri.parse(documentUri) }.getOrNull()
            ?: throw LocalSafException(SafFailure.Invalid, "Invalid local file address")
        val documentId = runCatching { DocumentsContract.getDocumentId(parsed) }
            .getOrNull() ?: throw LocalSafException(SafFailure.Invalid, "Invalid SAF document")
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
        val treeUri = treeId?.let { id ->
            runCatching {
                DocumentsContract.buildTreeDocumentUri(parsed.authority ?: "", id).toString()
            }.getOrNull()
        }
        val docUri = treeUri?.let { tree ->
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree), documentId)
            }.getOrNull()
        } ?: parsed
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            resolver.query(docUri, projection, null, null, null)
        } catch (e: SecurityException) {
            throw LocalSafException(SafFailure.PermissionDenied, "Access to the local file was revoked")
        } ?: throw LocalSafException(SafFailure.NotFound, "Local file not found")
        cursor.use {
            if (!it.moveToFirst()) {
                throw LocalSafException(SafFailure.NotFound, "Local file not found")
            }
            val mime = it.getString(1)
            return@withContext SafDocument(
                uri = docUri.toString(),
                documentId = documentId,
                name = it.getString(0) ?: "Local file",
                mime = mime,
                sizeBytes = if (it.isNull(2)) null else it.getLong(2),
                lastModified = if (it.isNull(3)) null else it.getLong(3),
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                treeUri = treeUri,
            )
        }
    }

    override suspend fun children(treeUri: String, documentId: String): List<SafChild> =
        withContext(Dispatchers.IO) {
            val tree = runCatching { Uri.parse(treeUri) }.getOrNull()
                ?: throw LocalSafException(SafFailure.Invalid, "Invalid SAF tree")
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val cursor = try {
                resolver.query(childrenUri, projection, null, null, null)
            } catch (e: SecurityException) {
                throw LocalSafException(SafFailure.PermissionDenied, "Access to the local folder was revoked")
            } ?: throw LocalSafException(SafFailure.Damaged, "Cannot read the local folder")
            cursor.use {
                buildList {
                    while (it.moveToNext()) {
                        val name = it.getString(0) ?: continue
                        val id = it.getString(1) ?: continue
                        add(
                            SafChild(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                                documentId = id,
                                name = name,
                                sizeBytes = if (it.isNull(3)) null else it.getLong(3),
                                lastModified = if (it.isNull(4)) null else it.getLong(4),
                                isDirectory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            ),
                        )
                    }
                }
            }
        }

    override suspend fun openInput(documentUri: String): InputStream? = withContext(Dispatchers.IO) {
        try {
            resolver.openInputStream(Uri.parse(documentUri))
        } catch (e: SecurityException) {
            throw LocalSafException(SafFailure.PermissionDenied, "Access to the local file was revoked")
        } catch (e: Exception) {
            throw LocalSafException(SafFailure.NotFound, "Cannot open the local file")
        }
    }
}

private const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
private const val MAX_PAGE_BYTES = 32L * 1024 * 1024
private const val MAX_SAF_CACHE_BYTES = 512L * 1024 * 1024
private const val MAX_CHAPTER_HTML_CACHE = 24

/**
 * Evictable cache for direct SAF access. Archives are copied once for
 * random-access ZIP parsers; pages/images are materialized only when a chapter
 * is opened. Nothing here writes to filesDir/local_books.
 */
class LocalSafCache(
    val root: File,
    private val open: suspend (String) -> InputStream?,
    private val maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
    private val maxCacheBytes: Long = MAX_SAF_CACHE_BYTES,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    @Volatile
    private var lastCleanup = 0L

    suspend fun archive(
        documentUri: String,
        lastModified: Long?,
        sizeBytes: Long?,
        extension: String,
    ): File {
        if (sizeBytes != null && sizeBytes > maxArchiveBytes) {
            throw LocalSafException(SafFailure.TooLarge, "File is too large to open")
        }
        val hash = catalogMetadataCacheKey(documentUri, lastModified, sizeBytes)
        val dir = File(root, "books").apply { mkdirs() }
        val target = File(dir, "$hash.${extension.lowercase()}")
        if (target.isFile && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        return withContext(Dispatchers.IO) {
            val cached = locks.computeIfAbsent(hash) { Mutex() }.withLock {
                if (target.isFile && target.length() > 0) {
                    target.setLastModified(System.currentTimeMillis())
                    return@withLock target
                }
                val temp = File(dir, "$hash.tmp")
                try {
                    val input = open(documentUri)
                        ?: throw LocalSafException(SafFailure.NotFound, "Local file not found")
                    input.use { source ->
                        FileOutputStream(temp).use { out ->
                            var total = 0L
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = source.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxArchiveBytes) {
                                    throw LocalSafException(SafFailure.TooLarge, "File is too large to open")
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    target
                } catch (e: LocalSafException) {
                    temp.delete()
                    throw e
                } catch (e: Exception) {
                    temp.delete()
                    throw LocalSafException(SafFailure.Damaged, e.message ?: "Cannot cache the local file")
                }
            }
            maybeCleanup()
            cached
        }
    }

    suspend fun writePage(
        hash: String,
        chapterIndex: Int,
        pageIndex: Int,
        extension: String,
        bytes: ByteArray,
    ): File = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) throw LocalSafException(SafFailure.Damaged, "Empty page")
        if (bytes.size > MAX_PAGE_BYTES) {
            throw LocalSafException(SafFailure.TooLarge, "Page is too large")
        }
        val dir = File(root, "pages/$hash/$chapterIndex").apply { mkdirs() }
        val file = File(dir, "${pageIndex.toString().padStart(5, '0')}.$extension")
        if (file.isFile && file.length() == bytes.size.toLong()) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        val temp = File(dir, "${file.name}.part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Cannot store page" }
        }
        maybeCleanup()
        file
    }

    internal suspend fun writeTextImage(
        hash: String,
        chapterIndex: Int,
        imageIndex: Int,
        extension: String,
        bytes: ByteArray,
    ): File = writePage(hash, chapterIndex, imageIndex, extension, bytes)

    internal suspend fun writeCover(hash: String, bytes: ByteArray): File? =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return@withContext null
            val extension = coverImageExtension(bytes) ?: return@withContext null
            runCatching {
                val dir = File(root, "covers").apply { mkdirs() }
                val file = File(dir, "$hash.$extension")
                if (!file.isFile) file.writeBytes(bytes)
                file.setLastModified(System.currentTimeMillis())
                file
            }.getOrNull()
        }

    internal fun cleanup(limitBytes: Long = maxCacheBytes) {
        val files = root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toList()
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limitBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
        root.walkTopDown().filter { it.isDirectory && it.listFiles().isNullOrEmpty() }
            .sortedByDescending { it.path.length }
            .forEach { it.delete() }
    }

    private fun maybeCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanup < 30_000) return
        lastCleanup = now
        cleanup()
    }
}

data class LocalSafChapter(
    val index: Int,
    val title: String,
    val url: String,
)

data class LocalSafBook(
    val bookUrl: String,
    val documentUri: String,
    val displayName: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String?,
    val format: String,
    val isManga: Boolean,
    val chapterCount: Int,
    val cacheHash: String,
    val sizeBytes: Long?,
    val installId: String,
)

data class LocalSafChapterContent(
    val title: String,
    val html: String,
    // True when the parsed HTML came from the bounded in-memory cache.
    val cacheHit: Boolean = false,
)

private data class ChapterSource(
    val archiveUri: String? = null,
    val archiveName: String? = null,
    val archiveSize: Long? = null,
    val archiveModified: Long? = null,
    val pages: List<String> = emptyList(),
)

private data class DirectPlan(
    val bookUrl: String,
    val document: SafDocument,
    val cacheHash: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String?,
    val format: String,
    val isManga: Boolean,
    val layout: MangaFolderKind?,
    val chapters: List<LocalSafChapter>,
    val sources: List<ChapterSource>,
)

/**
 * Direct reader for `local-file://<encoded document URI>`. It never creates a
 * library row or copies into local_books; parsing/cache files live under
 * cacheDir/local_saf and are safe to evict.
 */
class LocalSafBookSource(
    private val resolver: SafResolver,
    private val cache: LocalSafCache,
    private val registry: LocalSafRegistry,
    private val metadataCache: LocalCatalogMetadataProvider? = null,
) {
    private val plans = ConcurrentHashMap<String, DirectPlan>()
    // Parsed chapter HTML is derived, not persisted: re-opening the same
    // chapter must not re-walk the archive. The key carries the file identity
    // hash, so a changed file naturally misses.
    private val chapterHtml = ConcurrentHashMap<String, String>()
    private val planLock = Mutex()

    fun invalidate(bookUrl: String) {
        plans.remove(bookUrl.substringBefore('#'))
        chapterHtml.clear()
    }

    suspend fun book(bookUrl: String): LocalSafBook {
        val plan = plan(bookUrl)
        return LocalSafBook(
            bookUrl = plan.bookUrl,
            documentUri = plan.document.uri,
            displayName = plan.document.name,
            title = plan.title,
            author = plan.author,
            description = plan.description,
            coverUrl = plan.coverUrl,
            format = plan.format,
            isManga = plan.isManga,
            chapterCount = plan.chapters.size,
            cacheHash = plan.cacheHash,
            sizeBytes = plan.document.sizeBytes,
            installId = if (plan.isManga) LocalMangaInstallId else LocalBooksInstallId,
        )
    }

    suspend fun chapters(bookUrl: String): List<LocalSafChapter> = plan(bookUrl).chapters

    suspend fun chapterContent(bookUrl: String, chapterIndex: Int): LocalSafChapterContent {
        val plan = plan(bookUrl)
        val chapter = plan.chapters.getOrNull(chapterIndex - 1)
            ?: throw LocalSafException(SafFailure.Invalid, "Chapter not found")
        val source = plan.sources.getOrNull(chapterIndex - 1)
            ?: throw LocalSafException(SafFailure.Invalid, "Chapter not found")
        val cacheKey = "${plan.cacheHash}:$chapterIndex"
        chapterHtml[cacheKey]?.let {
            return LocalSafChapterContent(title = chapter.title, html = it, cacheHit = true)
        }
        val html = when {
            plan.format == "EPUB" -> textHtml(plan, chapterIndex, format = "EPUB")
            plan.format == "FB2" -> textHtml(plan, chapterIndex, format = "FB2")
            plan.format == "TXT" -> textHtml(plan, chapterIndex, format = "TXT")
            source.archiveUri != null -> cbzHtml(plan, source, chapterIndex)
            source.pages.isNotEmpty() -> imagePagesHtml(source.pages)
            else -> throw LocalSafException(SafFailure.Damaged, "Chapter has no content")
        }
        // shortcut: bounded in-memory cache; add a real LRU if readers jump
        // between dozens of long text chapters.
        if (chapterHtml.size >= MAX_CHAPTER_HTML_CACHE) chapterHtml.clear()
        chapterHtml[cacheKey] = html
        return LocalSafChapterContent(title = chapter.title, html = html)
    }

    suspend fun coverBytes(bookUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        val coverUrl = plan(bookUrl).coverUrl ?: return@withContext null
        if (!coverUrl.startsWith("file:", ignoreCase = true)) return@withContext null
        val file = coverFile(coverUrl) ?: return@withContext null
        // Check the size before reading: a corrupt/huge cover must not OOM.
        if (!file.isFile || file.length() <= 0L || file.length() > 8L * 1024 * 1024) {
            return@withContext null
        }
        runCatching {
            file.readBytes().takeIf { coverImageExtension(it) != null }
        }.getOrNull()
    }

    // Only files inside the app's own caches may be read: a crafted cover URL
    // must not reach arbitrary paths. The catalogue metadata cache keeps its
    // covers in a sibling directory, so both roots are allowed.
    private fun coverFile(coverUrl: String): File? {
        val uri = runCatching { Uri.parse(coverUrl) }.getOrNull()
        val path = uri?.path?.takeIf { it.isNotBlank() }
            ?: coverUrl.substringAfter("file:").removePrefix("//")
        val file = File(path)
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val allowed = listOfNotNull(cache.root, metadataCache?.root).any { root ->
            val rootPath = runCatching { root.canonicalFile }.getOrNull()?.path
            rootPath != null &&
                (candidate.path == rootPath || candidate.path.startsWith(rootPath + File.separator))
        }
        return if (allowed) file else null
    }

    private suspend fun plan(bookUrl: String): DirectPlan = withContext(Dispatchers.IO) {
        val cleanUrl = bookUrl.substringBefore('#')
        val docUri = localFileDocumentUri(cleanUrl, registry)
            ?: throw LocalSafException(SafFailure.Invalid, "Invalid local file address")
        val document = resolver.document(docUri)
        val hash = catalogMetadataCacheKey(document.uri, document.lastModified, document.sizeBytes)
        plans[cleanUrl]?.takeIf { it.cacheHash == hash }?.let { return@withContext it }
        planLock.withLock {
            plans[cleanUrl]?.takeIf { it.cacheHash == hash }?.let { return@withLock it }
            // A changed file invalidates every derived chapter body.
            chapterHtml.clear()
            val built = if (document.isDirectory) {
                folderPlan(cleanUrl, document, hash)
            } else {
                filePlan(cleanUrl, document, hash)
            }
            plans[cleanUrl] = built
            built
        }
    }

    private suspend fun filePlan(
        bookUrl: String,
        document: SafDocument,
        hash: String,
    ): DirectPlan {
        val extension = document.name.substringAfterLast('.', "").lowercase()
        // PDF is not planned here: its pages render lazily through the isolated
        // renderer in an imported copy, not from a scanned document.
        val format = when (extension) {
            "epub" -> "EPUB"
            "fb2" -> "FB2"
            "txt" -> "TXT"
            "cbz" -> "CBZ"
            else -> throw LocalSafException(
                SafFailure.Unsupported,
                "Unsupported local format: ${document.name}",
            )
        }
        val entryBase = LocalCatalogEntry(
            uri = document.uri,
            displayName = document.name,
            format = format,
            kind = if (format == "CBZ") "manga" else "books",
            sizeBytes = document.sizeBytes,
            lastModified = document.lastModified,
            folderUri = document.treeUri ?: document.uri,
            folderName = document.name,
            mangaLayout = if (format == "CBZ") MangaFolderKind.SingleCbz else null,
        )
        val entry = metadataCache?.enrich(listOf(entryBase))?.firstOrNull() ?: entryBase
        val fallbackTitle = document.name.substringBeforeLast('.').ifBlank { document.name }
        var parsedTitle: String? = null
        var parsedAuthor: String? = null
        var parsedDescription: String? = null
        var parsedCover: ByteArray? = null
        fun seeds(titles: List<String>): List<LocalSafChapter> = titles.mapIndexed { index, title ->
            LocalSafChapter(
                index = index + 1,
                title = title.ifBlank { "Chapter ${index + 1}" },
                url = localFileChapterUrl(bookUrl, index + 1),
            )
        }
        val chapters = when (format) {
            "CBZ" -> {
                if (metadataCache == null) {
                    runCatching {
                        val archive = cache.archive(
                            document.uri,
                            document.lastModified,
                            document.sizeBytes,
                            extension,
                        )
                        CbzFileReader(archive).use { reader ->
                            parsedTitle = reader.title.takeIf { it != "Unknown Title" }
                            parsedDescription = reader.description
                            parsedCover = reader.coverBytes
                        }
                    }
                }
                listOf(
                    LocalSafChapter(
                        index = 1,
                        // A standalone archive is a one-chapter manga book named
                        // after its file; ComicInfo Title is not the book name.
                        title = fallbackTitle,
                        url = localFileChapterUrl(bookUrl, 1),
                    ),
                )
            }
            "TXT" -> {
                val archive = cache.archive(
                    document.uri,
                    document.lastModified,
                    document.sizeBytes,
                    extension,
                )
                val titles = runCatching {
                    // Titles only: the body is read again when a chapter opens.
                    buildList { TxtFileReader(archive).forEachChapter { add(it.title) } }
                }.getOrElse { e ->
                    throw LocalSafException(
                        SafFailure.Damaged,
                        e.message ?: "Cannot parse ${document.name}",
                    )
                }
                if (titles.isEmpty()) {
                    throw LocalSafException(SafFailure.Damaged, "Book has no chapters")
                }
                seeds(titles)
            }
            else -> {
                val archive = cache.archive(
                    document.uri,
                    document.lastModified,
                    document.sizeBytes,
                    extension,
                )
                val titles = runCatching {
                    if (format == "EPUB") {
                        EpubFileReader(archive).use { reader ->
                            parsedTitle = reader.title.takeIf { it != "Unknown Title" }
                            parsedAuthor = reader.author
                            parsedDescription = reader.description
                            parsedCover = reader.coverBytes
                            buildList { reader.forEachChapter { add(it.title) } }
                        }
                    } else {
                        val book = Fb2FileReader(archive).book
                        parsedTitle = book.title.takeIf { it != "Unknown Title" }
                        parsedAuthor = book.author
                        parsedDescription = book.description
                        parsedCover = book.coverBytes
                        book.chapters.map { it.title }
                    }
                }.getOrElse { e ->
                    throw LocalSafException(
                        SafFailure.Damaged,
                        e.message ?: "Cannot parse ${document.name}",
                    )
                }
                if (titles.isEmpty()) {
                    throw LocalSafException(SafFailure.Damaged, "Book has no chapters")
                }
                seeds(titles)
            }
        }
        val source = ChapterSource(
            archiveUri = document.uri,
            archiveName = document.name,
            archiveSize = document.sizeBytes,
            archiveModified = document.lastModified,
        )
        val coverUrl = entry.coverUrl
            ?: parsedCover?.let { cache.writeCover(hash, it) }?.toURI()?.toString()
        return DirectPlan(
            bookUrl = bookUrl,
            document = document,
            cacheHash = hash,
            title = if (format == "CBZ") {
                fallbackTitle
            } else {
                entry.embeddedTitle ?: parsedTitle ?: fallbackTitle
            },
            author = entry.embeddedAuthor ?: parsedAuthor ?: "Unknown",
            description = entry.embeddedDescription ?: parsedDescription.orEmpty(),
            coverUrl = coverUrl,
            format = format,
            isManga = format == "CBZ",
            layout = if (format == "CBZ") MangaFolderKind.SingleCbz else null,
            chapters = chapters,
            sources = List(chapters.size) { source },
        )
    }

    private suspend fun folderPlan(
        bookUrl: String,
        document: SafDocument,
        hash: String,
    ): DirectPlan {
        val root = walkFolder(document)
        val units = collectMangaChapters(root)
        if (units.isEmpty()) {
            throw LocalSafException(SafFailure.Unsupported, "Folder has no readable manga")
        }
        val sources = units.map { unit ->
            val archiveUri = unit.archiveUri
            if (archiveUri != null) {
                val child = findNode(root, archiveUri)
                ChapterSource(
                    archiveUri = archiveUri,
                    archiveName = child?.name.orEmpty(),
                    archiveSize = child?.sizeBytes,
                    archiveModified = child?.lastModified,
                )
            } else {
                ChapterSource(pages = unit.pages)
            }
        }
        val layout = when {
            units.all { it.archiveUri != null } -> MangaFolderKind.CbzSet
            units.none { it.archiveUri != null } -> MangaFolderKind.ImageBook
            else -> MangaFolderKind.Mixed
        }
        val format = if (layout == MangaFolderKind.ImageBook) "IMAGES" else "CBZ"
        val entryBase = LocalCatalogEntry(
            uri = document.uri,
            displayName = document.name,
            format = format,
            kind = "manga",
            sizeBytes = document.sizeBytes,
            lastModified = document.lastModified,
            folderUri = document.treeUri ?: document.uri,
            folderName = document.name,
            entryType = LocalCatalogEntryType.Folder,
            mangaLayout = layout,
            documentId = document.documentId,
            primaryChildUri = sources.firstOrNull()?.archiveUri ?: units.firstOrNull()?.pages?.firstOrNull(),
            chapterCount = sources.size,
        )
        val entry = metadataCache?.enrich(listOf(entryBase))?.firstOrNull() ?: entryBase
        val chapters = units.mapIndexed { index, unit ->
            LocalSafChapter(
                index = index + 1,
                title = unit.title.ifBlank { "Chapter ${index + 1}" },
                url = localFileChapterUrl(bookUrl, index + 1),
            )
        }
        return DirectPlan(
            bookUrl = bookUrl,
            document = document,
            cacheHash = hash,
            title = document.name,
            author = entry.embeddedAuthor ?: "Unknown",
            description = entry.embeddedDescription.orEmpty(),
            coverUrl = entry.coverUrl,
            format = format,
            isManga = true,
            layout = layout,
            chapters = chapters,
            sources = sources,
        )
    }

    private fun findNode(node: ScannedNode, uri: String): ScannedNode? {
        if (node.uri == uri) return node
        for (child in node.children) {
            findNode(child, uri)?.let { return it }
        }
        return null
    }

    private suspend fun walkFolder(document: SafDocument): ScannedNode {
        suspend fun walk(node: ScannedNode, depth: Int): ScannedNode {
            if (depth > MAX_LOCAL_SCAN_DEPTH) {
                // Truncate, do not fail: the catalogue may see a deeper tree
                // than the importer can materialize.
                MiruLog.w("local", "direct read tree truncated at depth=$depth")
                return node
            }
            if (!node.isDirectory) return node
            val tree = document.treeUri ?: document.uri
            val children = resolver.children(tree, node.documentId)
                .map { child ->
                    val childNode = ScannedNode(
                        uri = child.uri,
                        documentId = child.documentId,
                        name = child.name,
                        isDirectory = child.isDirectory,
                        sizeBytes = child.sizeBytes,
                        lastModified = child.lastModified,
                    )
                    if (child.isDirectory) walk(childNode, depth + 1) else childNode
                }
                .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            return node.copy(children = children)
        }
        return walk(
            ScannedNode(
                uri = document.uri,
                documentId = document.documentId,
                name = document.name,
                isDirectory = true,
                sizeBytes = document.sizeBytes,
                lastModified = document.lastModified,
            ),
            depth = 0,
        )
    }

    private suspend fun textHtml(
        plan: DirectPlan,
        chapterIndex: Int,
        format: String,
    ): String {
        val document = plan.document
        val extension = document.name.substringAfterLast('.', "").lowercase()
        val archive = cache.archive(
            document.uri,
            document.lastModified,
            document.sizeBytes,
            extension,
        )
        val chapter = runCatching {
            when (format) {
                "EPUB" -> {
                    var found: dev.mirutime.core.local.LocalChapter? = null
                    var current = 0
                    EpubFileReader(archive).use { reader ->
                        reader.forEachChapter { item ->
                            current++
                            if (current == chapterIndex) found = item
                        }
                    }
                    found
                }
                // Streaming: only the requested chapter is kept in memory.
                "TXT" -> {
                    var found: dev.mirutime.core.local.LocalChapter? = null
                    var current = 0
                    TxtFileReader(archive).forEachChapter { item ->
                        current++
                        if (current == chapterIndex) found = item
                    }
                    found
                }
                else -> Fb2FileReader(archive).book.chapters.getOrNull(chapterIndex - 1)
            }
        }.getOrElse { e ->
            throw LocalSafException(SafFailure.Damaged, e.message ?: "Cannot parse chapter")
        } ?: throw LocalSafException(SafFailure.Damaged, "Chapter not found in the file")
        val pages = chapter.images.mapIndexedNotNull { index, image ->
            if (image.bytes.isEmpty()) return@mapIndexedNotNull null
            val extension = imageExtension(image.mediaType, image.bytes)
            val file = cache.writeTextImage(
                hash = plan.cacheHash,
                chapterIndex = chapterIndex,
                imageIndex = index,
                extension = extension,
                bytes = image.bytes,
            )
            InlinePage(position = image.position, url = file.toURI().toString())
        }
        return if (pages.isEmpty()) {
            textToParagraphHtml(chapter.body)
        } else {
            interleaveChapterText(chapter.body, pages)
        }
    }

    private suspend fun cbzHtml(
        plan: DirectPlan,
        source: ChapterSource,
        chapterIndex: Int,
    ): String {
        val archiveUri = source.archiveUri
            ?: throw LocalSafException(SafFailure.Damaged, "CBZ archive is missing")
        val archive = cache.archive(
            archiveUri,
            source.archiveModified,
            source.archiveSize,
            "cbz",
        )
        val files = runCatching {
            CbzFileReader(archive).use { reader ->
                val names = reader.imageNames.sortedWith(compareBy(::naturalSortKey))
                if (names.isEmpty()) throw LocalSafException(SafFailure.Damaged, "CBZ has no pages")
                names.mapIndexedNotNull { index, name ->
                    val bytes = reader.readPage(name)
                        ?: throw LocalSafException(SafFailure.Damaged, "Cannot read $name")
                    val extension = name.substringAfterLast('.', "").lowercase()
                        .takeIf { it in PageImageExtensions } ?: "jpg"
                    cache.writePage(
                        hash = plan.cacheHash,
                        chapterIndex = chapterIndex,
                        pageIndex = index,
                        extension = extension,
                        bytes = bytes,
                    )
                }
            }
        }.getOrElse { e ->
            if (e is LocalSafException) throw e
            throw LocalSafException(SafFailure.Damaged, e.message ?: "Cannot read the CBZ")
        }
        return files.joinToString("") { "<p><img src=\"${htmlAttribute(it.toURI().toString())}\"/></p>" }
    }

    private fun imagePagesHtml(pages: List<String>): String {
        if (pages.isEmpty()) throw LocalSafException(SafFailure.Damaged, "Chapter has no pages")
        return pages.joinToString("") { "<p><img src=\"${htmlAttribute(it)}\"/></p>" }
    }

    private fun ScannedNode.directImageUris(): List<String> = children
        .filter { !it.isDirectory && isPageName(it.name) }
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        .map { it.uri }

    private fun isPageName(name: String): Boolean {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        if (base.startsWith(".") || base.lowercase().contains("thumb")) return false
        return base.substringAfterLast('.', "").lowercase() in PageImageExtensions
    }

    private fun htmlAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
