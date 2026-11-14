package dev.mirutime.core.local

import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.CatalogItem
import dev.mirutime.core.api.CatalogPage
import dev.mirutime.core.api.Chapter
import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.host.FileSystem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Local-library PluginSource: a catalog of epub/fb2/cbz files under one
// directory (FileSystem, no android.net.Uri — the NoveLA wiring point).
// ceiling: every call re-parses the book from scratch — add an LRU/parse
// cache only if open latency shows up in profiling.
class LocalSource(
    private val fileSystem: FileSystem,
    private val libraryDir: String,
    contentType: ContentType = ContentType.NOVEL,
    // Production passes the real app file so ZIP parsing uses ZipFile random
    // access; the byte fallback exists only for FakeFileSystem tests.
    private val fileResolver: ((String) -> File?)? = null,
    override val metadata: PluginMetadata = PluginMetadata(
        id = "local",
        name = "Local library",
        baseUrl = "local://library",
        contentType = contentType,
        language = "en",
        api = 1,
        version = "1.0.0",
        sha256 = "",
    ),
) : PluginSource {

    override suspend fun catalog(index: Int): Outcome<CatalogPage> = pageOf(listedBooks(), index)

    override suspend fun search(index: Int, query: String): Outcome<CatalogPage> =
        pageOf(listedBooks().filter { it.title.contains(query, ignoreCase = true) }, index)

    override suspend fun catalogFiltered(
        index: Int,
        filters: Map<String, List<String>>,
    ): Outcome<CatalogPage> = catalog(index)

    override suspend fun book(url: String): Outcome<BookMeta> =
        parse(url).let { result ->
            when (result) {
                is Outcome.Ok -> Outcome.Ok(
                    BookMeta(
                        title = result.value.title,
                        description = result.value.description,
                        author = result.value.author,
                    ),
                )
                is Outcome.Err -> result
            }
        }

    // Embedded cover for the import pipeline; null when the file has none or
    // parsing fails. The importer stores it through BookCoverStore.
    suspend fun coverBytes(bookPath: String): ByteArray? =
        parse(bookPath).getOrNull()?.coverBytes

    // One CBZ page, read without materializing the whole archive. The URL is
    // the parser chapter URL (`<bookPath>#<entry>`), not a SAF URI.
    suspend fun pageBytes(pageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        val bookPath = pageUrl.substringBefore('#')
        if (!bookPath.endsWith(".cbz")) return@withContext null
        val entry = pageUrl.substringAfter('#', missingDelimiterValue = "")
        if (entry.isBlank()) return@withContext null
        val file = fileResolver?.invoke(bookPath)
        if (file != null && file.isFile) {
            return@withContext readZipEntry(file, entry)
        }
        fileSystem.read(bookPath)?.let { readCbzEntry(it, entry) }
    }

    override suspend fun chapters(url: String): Outcome<List<Chapter>> =
        when (val result = parse(url)) {
            is Outcome.Err -> result
            is Outcome.Ok -> Outcome.Ok(
                result.value.chapters.map { Chapter(title = it.title, url = "$url#${it.url}") },
            )
        }

    // One parsed chapter including inline image bytes. `chapterUrl` may be the
    // parser URL (`<path>#<key>`) or the bare key.
    suspend fun localChapter(bookPath: String, chapterUrl: String): LocalChapter? {
        val key = chapterUrl.substringAfter('#', chapterUrl)
        return parse(bookPath).getOrNull()
            ?.chapters
            ?.firstOrNull { it.url == key }
    }

    // One parse for all chapters; callers that persist a whole book must not
    // call localChapterAt in a loop (that would re-parse the archive N times).
    suspend fun localChapters(bookPath: String): List<LocalChapter>? =
        parse(bookPath).getOrNull()?.chapters

    // 0-based parser chapter index; lets one reader call avoid parsing twice.
    suspend fun localChapterAt(bookPath: String, index: Int): LocalChapter? =
        localChapters(bookPath)?.getOrNull(index)

    override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> {
        val bookPath = url.substringBefore('#')
        // CBZ: manga readers hit this on every swipe — existence-check only,
        // a full unzip per page would be absurd. ceiling: the entry name is
        // not validated against the archive (a stale url yields an empty
        // page); validate via a parse on catalog refresh instead.
        if (bookPath.endsWith(".cbz")) {
            val file = fileResolver?.invoke(bookPath)
            val exists = if (file != null) file.isFile else fileSystem.read(bookPath) != null
            if (!exists) {
                return Outcome.Err(
                    PluginError.UserMessage("Local library", "File not found: $bookPath"),
                )
            }
            return Outcome.Ok(ChapterContent.Pages(listOf(url)))
        }
        val chapterKey = url.substringAfter('#', missingDelimiterValue = "")
        val book = when (val result = parse(bookPath)) {
            is Outcome.Err -> return result
            is Outcome.Ok -> result.value
        }
        val chapter = book.chapters.firstOrNull { it.url == chapterKey }
            ?: return Outcome.Err(PluginError.UserMessage("Local library", "Chapter not found: $url"))
        return Outcome.Ok(ChapterContent.Text(chapter.body))
    }

    override suspend fun filters(): Outcome<List<Map<String, String>>> = Outcome.Ok(emptyList())

    override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> = Outcome.Ok(emptyList())

    private fun listedBooks(): List<CatalogItem> =
        fileSystem.list(libraryDir)
            .filter { isSupportedBook(it) }
            .map { path ->
                CatalogItem(
                    title = path.substringAfterLast('/').substringBeforeLast('.'),
                    url = path,
                )
            }

    private fun isSupportedBook(path: String): Boolean =
        path.endsWith(".epub") || path.endsWith(".fb2") || path.endsWith(".fb2.zip") ||
            path.endsWith(".cbz") || path.endsWith(".txt", ignoreCase = true)

    private fun pageOf(items: List<CatalogItem>, index: Int): Outcome<CatalogPage> {
        val from = index * PAGE_SIZE
        if (from >= items.size) return Outcome.Ok(CatalogPage(emptyList(), false))
        return Outcome.Ok(
            CatalogPage(
                items = items.subList(from, minOf(from + PAGE_SIZE, items.size)),
                hasNext = from + PAGE_SIZE < items.size,
            ),
        )
    }

    private fun parse(path: String): Outcome<LocalBook> {
        val file = fileResolver?.invoke(path)
        if (file != null && file.isFile) {
            return runCatching { Outcome.Ok(parseLocalBookFile(file, path)) }
                .getOrElse { e ->
                    Outcome.Err(
                        PluginError.ScriptError(e.message ?: e::class.simpleName ?: "parse failed"),
                    )
                }
        }
        val bytes = fileSystem.read(path)
            ?: return Outcome.Err(PluginError.UserMessage("Local library", "File not found: $path"))
        return try {
            Outcome.Ok(
                when {
                    path.endsWith(".epub") -> parseEpub(bytes)
                    path.endsWith(".fb2") || path.endsWith(".fb2.zip") -> parseFb2(bytes)
                    path.endsWith(".cbz") -> parseCbz(bytes)
                    path.endsWith(".txt", ignoreCase = true) -> parseTxt(
                        bytes,
                        path.substringAfterLast('/').substringBeforeLast('.')
                            .ifEmpty { "Untitled" },
                    )
                    else -> return Outcome.Err(
                        PluginError.ScriptError("Unsupported local format: $path"),
                    )
                },
            )
        } catch (e: Exception) {
            Outcome.Err(
                PluginError.ScriptError(e.message ?: e::class.simpleName ?: "parse failed"),
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
