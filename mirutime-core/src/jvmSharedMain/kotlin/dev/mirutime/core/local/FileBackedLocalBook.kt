package dev.mirutime.core.local

import com.fleeksoft.ksoup.Ksoup
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

// ── File-backed local book readers ─────────────────────────────────────────
// Production parsing uses ZipFile random access, so a 7k-chapter EPUB or a
// multi-hundred-MB CBZ never materializes its entries in a Map. The importer
// consumes chapters/pages through forEach* callbacks (one body/page in RAM).
// FB2 is still whole-file, but only after an explicit file-size cap.

internal const val MAX_ZIP_ENTRY_BYTES = 32L * 1024 * 1024
internal const val MAX_ZIP_TOTAL_IMAGES_BYTES = 64L * 1024 * 1024
internal const val MAX_FB2_FILE_BYTES = 64L * 1024 * 1024
private const val MAX_ZIP_COMPRESSION_RATIO = 1000L
private const val MAX_COMIC_INFO_BYTES = 4L * 1024 * 1024

internal class ZipArchive(file: File) : Closeable {
    private val zip = ZipFile(file)

    fun names(): List<String> {
        val out = mutableListOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.isSafeBookPath()) out += entry.name
        }
        return out
    }

    fun read(name: String, cap: Long = MAX_ZIP_ENTRY_BYTES): ByteArray? {
        if (!name.isSafeBookPath()) return null
        val entry = zip.getEntry(name) ?: return null
        return readEntry(entry, cap)
    }

    fun readEntry(entry: ZipEntry, cap: Long = MAX_ZIP_ENTRY_BYTES): ByteArray {
        if (entry.isDirectory) return ByteArray(0)
        if (entry.size > cap) throw IllegalStateException("zip entry too large: ${entry.name}")
        val compressed = entry.compressedSize
        if (entry.size > 0 && compressed > 0 &&
            entry.size / compressed > MAX_ZIP_COMPRESSION_RATIO
        ) {
            throw IllegalStateException("zip bomb ratio: ${entry.name}")
        }
        zip.getInputStream(entry).use { input -> return readStreamLimited(input, cap) }
    }

    override fun close() = zip.close()
}

private fun readStreamLimited(input: InputStream, cap: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > cap) throw IllegalStateException("zip entry too large")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

// ── EPUB ───────────────────────────────────────────────────────────────────

private val FILE_CHAPTER_EXTENSIONS = setOf("xhtml", "xml", "html")

class EpubFileReader(file: File) : Closeable {
    private val archive = ZipArchive(file)
    private val manifestItems: Map<String, EpubManifestRef>
    private val spine: List<EpubManifestRef>
    private val tocEntries: List<EpubTocEntry>
    private val rootPath: String

    val title: String
    val author: String?
    val description: String?
    val coverBytes: ByteArray?
    val coverMediaType: String?
    var imageBudget = MAX_ZIP_TOTAL_IMAGES_BYTES
        private set

    init {
        val container = archive.read("META-INF/container.xml")
            ?: throw IllegalStateException("META-INF/container.xml file missing")
        val opfPath = parseXmlDocument(container)
            ?.selectFirstTag("rootfile")
            ?.attr("full-path")
            ?.decodedURL
            ?: throw IllegalStateException("Invalid container.xml file")
        rootPath = opfPath.substringBefore('/', "")

        val opf = archive.read(opfPath) ?: throw IllegalStateException(".opf file missing")
        val document = parseXmlDocument(opf)
            ?: throw IllegalStateException(".opf file failed to parse data")
        val metadata = document.selectFirstTag("metadata")
            ?: throw IllegalStateException(".opf file metadata section missing")
        val manifest = document.selectFirstTag("manifest")
            ?: throw IllegalStateException(".opf file manifest section missing")
        val spineEl = document.selectFirstTag("spine")
            ?: throw IllegalStateException(".opf file spine section missing")

        title = metadata.selectFirstChildTag("dc:title")?.textContent ?: "Unknown Title"
        author = metadata.selectFirstChildTag("dc:creator")?.textContent
        description = metadata.selectFirstChildTag("dc:description")?.textContent

        manifestItems = manifest.selectChildTag("item").mapNotNull { item ->
            val id = item.attr("id").orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            id to EpubManifestRef(
                absPath = item.attr("href").orEmpty().decodedURL.hrefAbsolutePath(rootPath),
                mediaType = item.attr("media-type").orEmpty(),
                properties = item.attr("properties").orEmpty(),
            )
        }.toMap()
        spine = spineEl.selectChildTag("itemref").mapNotNull { itemRef ->
            manifestItems[itemRef.attr("idref").orEmpty()]
        }.filter { it.absPath.substringAfterLast('.') in FILE_CHAPTER_EXTENSIONS }

        val ncxPath = manifestItems.values.firstOrNull {
            it.mediaType == "application/x-dtbncx+xml"
        }?.absPath ?: manifestItems["ncx"]?.absPath
            ?: throw IllegalStateException("ncx file missing")
        val ncx = archive.read(ncxPath) ?: throw IllegalStateException("ncx file missing")
        val navMap = parseXmlDocument(ncx)?.selectFirstTag("navMap")
            ?: throw IllegalStateException("Invalid NCX file: navMap not found")
        tocEntries = navMap.selectTag("navPoint").mapNotNull { navPoint ->
            val navTitle = (navPoint.selectFirstTag("navLabel")
                ?.selectFirstTag("text")?.textContent ?: "")
                .replace(Regex("\\s+"), " ")
                .trim()
            val src = navPoint.selectFirstTag("content")?.attr("src") ?: ""
            if (src.isEmpty()) null
            else EpubTocEntry(navTitle, src.decodedURL.hrefAbsolutePath(rootPath))
        }
        val cover = runCatching { extractEpubCover(archive, manifestItems, metadata) }.getOrNull()
        coverBytes = cover?.bytes
        coverMediaType = cover?.mediaType
        imageBudget = (imageBudget - (coverBytes?.size ?: 0)).coerceAtLeast(0)
    }

    val spineCount: Int get() = spine.size

    fun forEachChapter(consumer: (LocalChapter) -> Unit) {
        var currentToc: EpubTocEntry? = null
        var body = StringBuilder()
        var images = mutableListOf<LocalImage>()

        for (item in spine) {
            val spineUrl = item.absPath
            val spineDir = spineUrl.substringBeforeLast('/', "")
            val parsed = xhtmlToContent(archive.read(spineUrl) ?: ByteArray(0)) { src ->
                readEpubImage(src, spineDir, manifestItems)
            }
            val toc = tocEntries.firstOrNull {
                it.link.substringBefore('#').equals(spineUrl.substringBefore('#'), ignoreCase = true)
            }
            if (currentToc != null && toc != null && body.isNotEmpty()) {
                consumer(LocalChapter(currentToc.link, currentToc.title, body.toString(), images.toList()))
                body = StringBuilder()
                images = mutableListOf()
            }
            if (toc != null) currentToc = toc
            if (parsed.body.isNotBlank() || parsed.images.isNotEmpty()) {
                body.append("\n\n")
                val offset = body.length
                body.append(parsed.body)
                parsed.images.forEach { images += it.copy(position = it.position + offset) }
            }
        }
        if (currentToc != null && body.isNotEmpty()) {
            consumer(LocalChapter(currentToc.link, currentToc.title, body.toString(), images.toList()))
        }
    }

    fun readAll(): LocalBook {
        val chapters = mutableListOf<LocalChapter>()
        forEachChapter { chapters += it }
        return LocalBook(
            title = title,
            author = author,
            description = description,
            chapters = chapters,
            coverBytes = coverBytes,
            coverMediaType = coverMediaType,
        )
    }

    private fun readEpubImage(
        src: String,
        spineDir: String,
        manifestItems: Map<String, EpubManifestRef>,
    ): LocalImage? {
        if (src.isBlank()) return null
        val path = src.substringBefore('#').substringBefore('?').decodedURL.hrefAbsolutePath(spineDir)
        val bytes = runCatching { archive.read(path) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > imageBudget) return null
        imageBudget -= bytes.size
        val mediaType = manifestItems.values.firstOrNull { it.absPath == path }
            ?.mediaType
            ?.takeIf { it.startsWith("image/") }
            ?: mediaTypeForPath(path)
        return LocalImage(position = 0, bytes = bytes, mediaType = mediaType)
    }

    override fun close() = archive.close()
}

private fun extractEpubCover(
    archive: ZipArchive,
    manifestItems: Map<String, EpubManifestRef>,
    metadata: XmlElement,
): CoverData? {
    val metaCoverId = metadata.selectTag("meta")
        .firstOrNull { it.attr("name").equals("cover", ignoreCase = true) }
        ?.attr("content")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val ref = metaCoverId?.let(manifestItems::get)
        ?: manifestItems.values.firstOrNull { item ->
            item.properties.split(Regex("\\s+")).any { it == "cover-image" }
        }
        ?: return null
    if (ref.mediaType.startsWith("image/")) {
        val bytes = archive.read(ref.absPath) ?: return null
        return CoverData(bytes, ref.mediaType)
    }
    if (!ref.mediaType.contains("xhtml")) return null
    val page = archive.read(ref.absPath) ?: return null
    val src = Ksoup.parse(page.decodeToString(), "")
        .selectFirst("img")
        ?.attr("src")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val base = ref.absPath.substringBeforeLast('/', "")
    val path = src.substringBefore('#').decodedURL.hrefAbsolutePath(base)
    val bytes = archive.read(path) ?: return null
    return CoverData(bytes, mediaTypeForPath(path))
}

private data class EpubManifestRef(
    val absPath: String,
    val mediaType: String,
    val properties: String = "",
)

private data class EpubTocEntry(val title: String, val link: String)

// ── CBZ ────────────────────────────────────────────────────────────────────

class CbzFileReader(file: File) : Closeable {
    private val archive = ZipArchive(file)
    val imageNames: List<String>
    val title: String
    val description: String?
    val coverBytes: ByteArray?
    val coverMediaType: String?

    init {
        val names = archive.names().filter { name ->
            name.substringAfterLast('.', "").lowercase() in PageImageExtensions
        }
        imageNames = names.sortedWith(compareBy(::fileNaturalKey))
        val comic = archive.read("ComicInfo.xml", MAX_COMIC_INFO_BYTES)?.let(::parseComicInfo)
        title = comic?.title ?: "Unknown Title"
        description = comic?.summary
        val coverName = fileCoverName(imageNames)
        coverBytes = coverName?.let { runCatching { archive.read(it) }.getOrNull() }
        coverMediaType = coverName?.let(::mediaTypeForPath)
    }

    fun readPage(name: String): ByteArray? = archive.read(name)

    fun readAll(): LocalBook {
        val chapters = imageNames.map { name ->
            LocalChapter(
                url = name,
                title = name.substringAfterLast('/').substringBeforeLast('.'),
                body = name,
            )
        }
        return LocalBook(
            title = title,
            description = description,
            chapters = chapters,
            coverBytes = coverBytes,
            coverMediaType = coverMediaType,
        )
    }

    override fun close() = archive.close()
}

private val PageImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

private fun fileNaturalKey(name: String): String =
    name.replace(Regex("\\d+")) { it.value.padStart(12, '0') }

private fun fileCoverName(names: List<String>): String? =
    names.sortedWith(
        compareBy(
            { name ->
                val stem = name.substringAfterLast('/').substringBeforeLast('.').lowercase()
                !(stem == "cover" || stem.startsWith("cover-") || stem.startsWith("cover_"))
            },
            ::fileNaturalKey,
        ),
    ).firstOrNull()

private fun mediaTypeForPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    else -> "image/jpeg"
}

// ── FB2 ────────────────────────────────────────────────────────────────────

// FB2 has no useful random-access substructure, so it stays a whole-file parse
// with an explicit 64 MB cap (text+base64 images). Larger files are rejected
// before bytes are read.
class Fb2FileReader(file: File) {
    val book: LocalBook

    init {
        if (file.length() > MAX_FB2_FILE_BYTES) {
            throw IllegalStateException("FB2 file too large (${file.length()} bytes)")
        }
        book = parseFb2(file.readBytes())
    }

    fun forEachChapter(consumer: (LocalChapter) -> Unit) {
        book.chapters.forEach(consumer)
    }
}

// ── format dispatch ────────────────────────────────────────────────────────

internal fun readZipEntry(
    file: File,
    name: String,
    cap: Long = MAX_ZIP_ENTRY_BYTES,
): ByteArray? = ZipArchive(file).use { it.read(name, cap) }

internal fun parseLocalBookFile(file: File, path: String): LocalBook = when {
    path.endsWith(".epub") -> EpubFileReader(file).use { it.readAll() }
    path.endsWith(".fb2") || path.endsWith(".fb2.zip") -> Fb2FileReader(file).book
    path.endsWith(".cbz") -> CbzFileReader(file).use { it.readAll() }
    else -> throw IllegalStateException("Unsupported local format: $path")
}
