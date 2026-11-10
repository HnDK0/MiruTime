package dev.mirutime.app.features.finder.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.mirutime.app.features.local.data.PageImageExtensions
import dev.mirutime.app.features.library.data.folderAuthor
import dev.mirutime.app.features.local.data.naturalSortKey
import dev.mirutime.app.features.library.data.readLimited
import dev.mirutime.app.features.library.data.sha256Hex
import dev.mirutime.core.local.CbzFileReader
import dev.mirutime.core.local.ComicInfoMeta
import dev.mirutime.core.local.EpubFileReader
import dev.mirutime.core.local.Fb2FileReader
import dev.mirutime.core.local.parseComicInfo
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Metadata read straight from a SAF document and persisted in the app cache,
// keyed by document URI + mtime + size. No library row is created: opening an
// unimported card must stay side-effect free.
internal data class LocalCatalogMetadata(
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val chapterCount: Int? = null,
    val coverUrl: String? = null,
)

internal data class ParsedCatalogMetadata(
    val metadata: LocalCatalogMetadata = LocalCatalogMetadata(),
    val coverBytes: ByteArray? = null,
)

internal fun catalogMetadataCacheKey(
    documentUri: String,
    lastModified: Long?,
    sizeBytes: Long?,
): String = sha256Hex(
    "$documentUri|${lastModified ?: -1L}|${sizeBytes ?: -1L}".encodeToByteArray(),
)

// Magic-byte validation: a provider may return HTML/an error page for an
// image stream, and a wrong extension would make Coil retry forever.
internal fun coverImageExtension(bytes: ByteArray): String? {
    fun at(index: Int): Int = bytes.getOrNull(index)?.toInt()?.and(0xFF) ?: -1
    return when {
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
        at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> "gif"
        at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "webp"
        at(0) == 0x42 && at(1) == 0x4D -> "bmp"
        else -> null
    }
}

internal fun readEpubMetadata(file: File): ParsedCatalogMetadata =
    EpubFileReader(file).use { reader ->
        ParsedCatalogMetadata(
            metadata = LocalCatalogMetadata(
                title = reader.title.takeIf { it.isNotBlank() && it != "Unknown Title" },
                author = reader.author?.trim()?.takeIf { it.isNotBlank() },
                description = reader.description?.trim()?.takeIf { it.isNotBlank() },
                chapterCount = reader.spineCount.takeIf { it > 0 },
            ),
            coverBytes = reader.coverBytes,
        )
    }

internal fun readFb2Metadata(file: File): ParsedCatalogMetadata {
    val book = Fb2FileReader(file).book
    return ParsedCatalogMetadata(
        metadata = LocalCatalogMetadata(
            title = book.title.takeIf { it.isNotBlank() && it != "Unknown Title" },
            author = book.author?.trim()?.takeIf { it.isNotBlank() },
            description = book.description?.trim()?.takeIf { it.isNotBlank() },
            chapterCount = book.chapters.size.takeIf { it > 0 },
        ),
        coverBytes = book.coverBytes,
    )
}

internal fun readCbzMetadata(file: File): ParsedCatalogMetadata =
    CbzFileReader(file).use { reader ->
        ParsedCatalogMetadata(
            metadata = LocalCatalogMetadata(
                title = reader.title.takeIf { it.isNotBlank() && it != "Unknown Title" },
                description = reader.description?.trim()?.takeIf { it.isNotBlank() },
                chapterCount = reader.imageNames.size.takeIf { it > 0 },
            ),
            coverBytes = reader.coverBytes,
        )
    }

// Folder metadata is pure once ComicInfo/first-page bytes are in hand; keeping
// it separate makes the "no title, ComicInfo author/cover" rule unit-testable
// without SAF. A manga book is named after its folder, so ComicInfo Title is
// deliberately dropped here.
internal fun folderCatalogMetadata(
    comic: ComicInfoMeta?,
    chapterCount: Int?,
    coverBytes: ByteArray?,
): ParsedCatalogMetadata {
    val author = folderAuthor(comic).takeIf { it != "Unknown" }
    return ParsedCatalogMetadata(
        metadata = LocalCatalogMetadata(
            title = null,
            author = author,
            description = comic?.summary?.trim()?.takeIf { it.isNotBlank() },
            chapterCount = chapterCount,
        ),
        coverBytes = coverBytes,
    )
}

private const val MAX_METADATA_BYTES = 256L * 1024 * 1024
private const val MAX_COVER_BYTES = 8L * 1024 * 1024
private const val MAX_COMIC_INFO_BYTES = 512L * 1024
private const val CACHE_DIR = "local-catalog"

/**
 * Catalogue metadata for SAF documents. The root is part of the contract: a
 * cover this provider produced lives inside it, so a reader may read the cover
 * back without opening an arbitrary path.
 */
interface LocalCatalogMetadataProvider {
    /** Cache root that holds the covers this provider produced. */
    val root: File

    suspend fun enrich(entries: List<LocalCatalogEntry>): List<LocalCatalogEntry>
}

/**
 * Reads title/author/description/cover for catalogue cards. The concurrency
 * cap (4) keeps a folder of 500 EPUBs from opening 500 streams at once; the
 * size caps and magic-byte check bound what a hostile/broken provider can do.
 */
class LocalCatalogMetadataCache(
    context: Context,
    cacheDir: File,
    maxConcurrent: Int = 4,
    private val maxBytes: Long = MAX_METADATA_BYTES,
) : LocalCatalogMetadataProvider {
    private val resolver = context.applicationContext.contentResolver
    override val root: File = File(cacheDir, CACHE_DIR)
    private val semaphore = Semaphore(maxConcurrent)

    override suspend fun enrich(entries: List<LocalCatalogEntry>): List<LocalCatalogEntry> =
        coroutineScope {
            entries.map { entry ->
                async(Dispatchers.IO) {
                    if (!isSupported(entry)) return@async entry
                    semaphore.withPermit {
                        val metadata = cached(entry) ?: resolve(entry) ?: return@withPermit entry
                        entry.copy(
                            // A manga book is named after its folder/archive;
                            // its ComicInfo Title belongs to a chapter.
                            embeddedTitle = entry.embeddedTitle
                                ?: metadata.title.takeUnless { entry.isManga },
                            embeddedAuthor = entry.embeddedAuthor ?: metadata.author,
                            embeddedDescription = entry.embeddedDescription ?: metadata.description,
                            coverUrl = entry.coverUrl ?: metadata.coverUrl,
                            chapterCount = entry.chapterCount ?: metadata.chapterCount,
                        )
                    }
                }
            }.awaitAll()
        }

    private fun isSupported(entry: LocalCatalogEntry): Boolean = when {
        entry.isFolder -> entry.mangaLayout != null
        entry.kind == "manga" -> entry.format == "CBZ"
        else -> entry.format == "EPUB" || entry.format == "FB2"
    }

    private fun cacheKey(entry: LocalCatalogEntry): String =
        catalogMetadataCacheKey(entry.uri, entry.lastModified, entry.sizeBytes)

    // A cached parse is reused until the document's mtime/size changes; the
    // marker record also remembers "parsed, nothing found" so a corrupt file
    // is not re-read on every catalogue page.
    private fun cached(entry: LocalCatalogEntry): LocalCatalogMetadata? {
        val key = cacheKey(entry)
        val record = File(root, "$key.json")
        if (!record.exists()) return null
        return runCatching {
            val json = JSONObject(record.readText())
            val cover = json.optString("cover").takeIf { it.isNotBlank() }
                ?.let { File(root, it) }
                ?.takeIf { it.isFile }
            LocalCatalogMetadata(
                title = json.optString("title").takeIf { it.isNotBlank() },
                author = json.optString("author").takeIf { it.isNotBlank() },
                description = json.optString("description").takeIf { it.isNotBlank() },
                chapterCount = json.optInt("chapters", 0).takeIf { it > 0 },
                coverUrl = cover?.toURI()?.toString(),
            )
        }.getOrNull()
    }

    private suspend fun resolve(entry: LocalCatalogEntry): LocalCatalogMetadata? {
        root.mkdirs()
        val parsed = when {
            entry.isFolder && entry.primaryChildUri?.endsWith(".cbz", ignoreCase = true) == true ->
                resolveCbzSet(entry)
            entry.isFolder -> resolveImageFolder(entry)
            entry.format == "EPUB" -> withCopiedFile(entry.uri) { file ->
                runCatching { readEpubMetadata(file) }.getOrElse { ParsedCatalogMetadata() }
            }
            entry.format == "FB2" -> withCopiedFile(entry.uri) { file ->
                runCatching { readFb2Metadata(file) }.getOrElse { ParsedCatalogMetadata() }
            }
            entry.format == "CBZ" -> withCopiedFile(entry.uri) { file ->
                runCatching { readCbzMetadata(file) }.getOrElse { ParsedCatalogMetadata() }
            }
            else -> null
        } ?: return null
        return persist(entry, parsed)
    }

    private suspend fun withCopiedFile(
        documentUri: String,
        read: (File) -> ParsedCatalogMetadata,
    ): ParsedCatalogMetadata? = withContext(Dispatchers.IO) {
        root.mkdirs()
        val temp = File(root, "tmp-${UUID.randomUUID()}.bin")
        try {
            val stream = resolver.openInputStream(Uri.parse(documentUri))
                ?: return@withContext null
            stream.use { input ->
                FileOutputStream(temp).use { out ->
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val readBytes = input.read(buffer)
                        if (readBytes < 0) break
                        total += readBytes
                        if (total > maxBytes) return@withContext null
                        out.write(buffer, 0, readBytes)
                    }
                }
            }
            read(temp)
        } catch (e: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    private suspend fun resolveCbzSet(entry: LocalCatalogEntry): ParsedCatalogMetadata? {
        val first = entry.primaryChildUri ?: return null
        val parsed = withCopiedFile(first) { file ->
            runCatching { readCbzMetadata(file) }.getOrElse { ParsedCatalogMetadata() }
        } ?: return null
        return parsed.copy(
            metadata = parsed.metadata.copy(
                chapterCount = entry.chapterCount ?: parsed.metadata.chapterCount,
            ),
        )
    }

    private data class FolderChild(
        val name: String,
        val documentId: String,
        val uri: String,
        val isDirectory: Boolean,
    )

    private fun queryChildren(treeUri: Uri, documentId: String): List<FolderChild>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0) ?: continue
                        val id = cursor.getString(1) ?: continue
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        add(
                            FolderChild(
                                name = name,
                                documentId = id,
                                uri = childUri.toString(),
                                isDirectory = cursor.getString(2) ==
                                    DocumentsContract.Document.MIME_TYPE_DIR,
                            ),
                        )
                    }
                }
            }
        }.getOrNull()
    }

    private fun rootIds(entry: LocalCatalogEntry): Pair<Uri, String>? {
        val treeUri = runCatching { Uri.parse(entry.folderUri) }.getOrNull() ?: return null
        val documentId = entry.documentId ?: runCatching {
            DocumentsContract.getDocumentId(Uri.parse(entry.uri))
        }.getOrNull() ?: return null
        return treeUri to documentId
    }

    private fun resolveImageFolder(entry: LocalCatalogEntry): ParsedCatalogMetadata {
        val (treeUri, documentId) = rootIds(entry) ?: return ParsedCatalogMetadata()
        val children = queryChildren(treeUri, documentId) ?: return ParsedCatalogMetadata()
        val files = children.filter { !it.isDirectory }
        val dirs = children.filter { it.isDirectory }
        // Root ComicInfo wins; many libraries keep it inside the first chapter
        // folder, so fall back to the first one there (same rule the importer
        // applies).
        // shortcut: only the first chapter folder is checked, which matches a
        // depth-first first file for the usual layouts; scan deeper when a
        // library is reported with metadata in a later chapter.
        val comic = files
            .firstOrNull { it.name.equals("ComicInfo.xml", ignoreCase = true) }
            ?.let { readComicInfo(it.uri) }
            ?: dirs
                .sortedWith { a, b -> naturalSortKey(a.name).compareTo(naturalSortKey(b.name)) }
                .firstOrNull()
                ?.let { dir -> queryChildren(treeUri, dir.documentId) }
                ?.firstOrNull {
                    !it.isDirectory && it.name.equals("ComicInfo.xml", ignoreCase = true)
                }
                ?.let { readComicInfo(it.uri) }
        val firstPage = files
            .filter { isPageName(it.name) }
            .sortedWith { a, b -> naturalSortKey(a.name).compareTo(naturalSortKey(b.name)) }
            .firstOrNull()
            ?: dirs
                .sortedWith { a, b -> naturalSortKey(a.name).compareTo(naturalSortKey(b.name)) }
                .asSequence()
                .mapNotNull { dir -> firstPageIn(treeUri, dir.documentId) }
                .firstOrNull()
        return folderCatalogMetadata(
            comic = comic,
            chapterCount = entry.chapterCount,
            coverBytes = firstPage?.let { readCover(it.uri) },
        )
    }

    private fun firstPageIn(treeUri: Uri, documentId: String): FolderChild? =
        queryChildren(treeUri, documentId)
            ?.filter { !it.isDirectory && isPageName(it.name) }
            ?.sortedWith { a, b -> naturalSortKey(a.name).compareTo(naturalSortKey(b.name)) }
            ?.firstOrNull()

    private fun readComicInfo(documentUri: String): ComicInfoMeta? = runCatching {
        resolver.openInputStream(Uri.parse(documentUri))?.use {
            readLimited(it, MAX_COMIC_INFO_BYTES)?.let(::parseComicInfo)
        }
    }.getOrNull()

    private fun readCover(documentUri: String): ByteArray? = runCatching {
        resolver.openInputStream(Uri.parse(documentUri))?.use { readLimited(it, MAX_COVER_BYTES) }
    }.getOrNull()

    private fun isPageName(name: String): Boolean {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        if (base.startsWith(".") || base.lowercase().contains("thumb")) return false
        return base.substringAfterLast('.', "").lowercase() in PageImageExtensions
    }

    private fun persist(
        entry: LocalCatalogEntry,
        parsed: ParsedCatalogMetadata,
    ): LocalCatalogMetadata {
        val key = cacheKey(entry)
        val cover = parsed.coverBytes?.let { writeCover(key, it) }
        val record = JSONObject().apply {
            put("title", parsed.metadata.title.orEmpty())
            put("author", parsed.metadata.author.orEmpty())
            put("description", parsed.metadata.description.orEmpty())
            put("chapters", parsed.metadata.chapterCount ?: 0)
            put("cover", cover?.name.orEmpty())
        }
        runCatching {
            root.mkdirs()
            val temp = File(root, "$key.json.tmp")
            temp.writeText(record.toString())
            if (!temp.renameTo(File(root, "$key.json"))) temp.delete()
        }
        return parsed.metadata.copy(coverUrl = cover?.toURI()?.toString())
    }

    private fun writeCover(key: String, bytes: ByteArray): File? {
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return null
        val extension = coverImageExtension(bytes) ?: return null
        return runCatching {
            val file = File(root, "$key.$extension")
            file.writeBytes(bytes)
            file
        }.getOrNull()
    }
}
