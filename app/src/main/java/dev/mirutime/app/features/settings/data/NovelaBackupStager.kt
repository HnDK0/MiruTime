package dev.mirutime.app.features.settings.data

import android.content.Context
import androidx.room3.withWriteTransaction
import dev.mirutime.app.features.finder.domain.isNewerVersion
import dev.mirutime.app.features.library.data.CoverBytes
import dev.mirutime.core.installer.ScriptMetadataParser
import dev.mirutime.core.log.MiruLog
import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.data.library.BookEntity
import dev.mirutime.data.library.ChapterBodyEntity
import dev.mirutime.data.library.ChapterEntity
import dev.mirutime.data.library.LibraryDao
import dev.mirutime.data.library.LibrarySchemaVersion
import dev.mirutime.data.library.ProgressEntity
import dev.mirutime.data.library.ShelfEntity
import dev.mirutime.data.library.createDatabaseSnapshot
import dev.mirutime.data.library.libraryDatabaseAt
import dev.mirutime.data.library.novela.NovelaBookRow
import dev.mirutime.data.library.novela.NovelaDatabase
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

data class InstalledPluginRef(val id: String, val version: String, val baseUrl: String)

data class NovelaImportReport(
    val books: Int,
    val booksSkipped: Int,
    val chapters: Int,
    val progress: Int,
    val bodies: Int,
    val bodiesSkipped: Int,
    val covers: Int,
    val coversSkipped: Int,
    val plugins: Int,
    val skippedPlugins: Int,
    val degradedProgress: Int,
    val unmatchedBooks: Int,
)

/**
 * Converts a NoveLA backup ZIP into a MiruTime staging directory, so the
 * existing restore pipeline ([BackupRestoreService.applyStagedLive]) imports it
 * without a second importer.
 *
 * The archive is streamed once into [STAGING_TMP_DIR], mapped into a fresh
 * library database (one write transaction), packed with
 * [createDatabaseSnapshot] and described by a normal `manifest.json`.
 */
class NovelaBackupStager(
    private val context: Context,
    private val filesDir: File,
    private val appVersion: String,
    private val installedPlugins: () -> List<InstalledPluginRef>,
    private val existingInstallIdForUrl: suspend (url: String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Streams [input] (a NoveLA zip) into [staging], leaving a complete
     * MiruTime staging dir: manifest.json + database/mirutime.db + files/ + plugins/.
     */
    suspend fun stage(
        input: InputStream,
        staging: File,
        onProgress: (Float) -> Unit = {},
        onStage: ((RestoreProgress) -> Unit)? = null,
    ): NovelaImportReport = try {
        val report = convert(input, staging, onProgress, onStage)
        MiruLog.event(
            "backup",
            "novela-stage",
            "ok" to true,
            "books" to report.books,
            "books_skipped" to report.booksSkipped,
            "chapters" to report.chapters,
            "bodies" to report.bodies,
            "bodies_skipped" to report.bodiesSkipped,
            "covers" to report.covers,
            "covers_skipped" to report.coversSkipped,
            "plugins" to report.plugins,
            "skipped_plugins" to report.skippedPlugins,
            "degraded_progress" to report.degradedProgress,
            "unmatched_books" to report.unmatchedBooks,
        )
        report
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MiruLog.event("backup", "novela-stage", "ok" to false, "error" to e::class.simpleName)
        throw e
    }

    private suspend fun convert(
        input: InputStream,
        staging: File,
        onProgress: (Float) -> Unit,
        onStage: ((RestoreProgress) -> Unit)?,
    ): NovelaImportReport {
        val work = File(staging, STAGING_TMP_DIR)
        val novelaDb = File(work, NOVELA_DB_ENTRY)
        val coversDir = File(work, COVERS_DIR)
        val pluginsDir = File(work, PLUGINS_DIR)
        work.mkdirs()

        extractZip(input, novelaDb, coversDir, pluginsDir, onProgress, onStage)

        // Converted DB + snapshot + staged bodies/covers all land in filesDir.
        requireFreeSpace(filesDir, novelaDb.length() * 2 + 256L * 1024 * 1024, "NoveLA restore")

        val stagedPlugins = stagedPlugins(pluginsDir)

        val converted = File(work, CONVERTED_DB)
        val database = libraryDatabaseAt(context, converted)
        var books = 0
        var booksSkipped = 0
        var chapters = 0
        var progress = 0
        var bodies = 0
        var bodiesSkipped = 0
        var covers = 0
        var coversSkipped = 0
        var degraded = 0
        var unmatched = 0
        val installedSnapshot = installedPlugins()
        val fileEntries = mutableListOf<BackupFileEntry>()
        try {
            val novela = NovelaDatabase.open(novelaDb.absolutePath)
            try {
                val all = novela.books()
                // NoveLA also keeps history-only rows; only library books are imported.
                val rows = all.filter { it.inLibrary }
                booksSkipped = all.size - rows.size
                val coverByUrl = stagedCovers(coversDir, rows.map { it.url })
                val pluginRefs = stagedPlugins.map {
                    InstalledPluginRef(it.id, it.version, it.baseUrl)
                } + installedSnapshot
                database.withWriteTransaction {
                    val dao = database.libraryDao()
                    val shelfIds = upsertShelves(dao, rows)
                    rows.forEachIndexed { index, book ->
                        currentCoroutineContext().ensureActive()
                        val written = writeBook(
                            dao = dao,
                            staging = staging,
                            novela = novela,
                            book = book,
                            shelfId = shelfIds[book.category.trim()] ?: DEFAULT_SHELF_ID,
                            pluginRefs = pluginRefs,
                            cover = coverByUrl[book.url],
                            fileEntries = fileEntries,
                            onStage = onStage,
                        )
                        if (written.installUnmatched) unmatched++
                        books++
                        chapters += written.chapters
                        progress += written.progress
                        bodies += written.bodies
                        bodiesSkipped += written.bodiesSkipped
                        covers += written.covers
                        coversSkipped += written.coversSkipped
                        degraded += written.degraded
                        onProgress(0.5f + 0.45f * (index + 1).toFloat() / rows.size)
                        onStage?.invoke(RestoreProgress(RestoreStage.DATABASE, index + 1, rows.size))
                    }
                }
            } finally {
                novela.close()
            }
        } finally {
            database.close()
        }

        val (pluginEntries, accepted, skipped) = installPlugins(
            staging = staging,
            stagedPlugins = stagedPlugins,
            installed = installedSnapshot,
        )

        val snapshot = File(staging, DATABASE_ENTRY)
        snapshot.parentFile?.mkdirs()
        createDatabaseSnapshot(converted.absolutePath, snapshot.absolutePath)
        work.deleteRecursively()

        val manifest = BackupManifest(
            formatVersion = BackupFormatVersion,
            appVersion = appVersion,
            schemaVersion = LibrarySchemaVersion,
            createdAt = now(),
            settingsIncluded = false,
            imagesIncluded = true,
            pluginsIncluded = pluginEntries.isNotEmpty(),
            readerIncluded = false,
            database = BackupEntry(DATABASE_ENTRY, snapshot.length(), sha256File(snapshot)),
            files = fileEntries,
            plugins = pluginEntries,
            counts = BackupCounts(
                // Log-only for `stagingBookCount`; NoveLA has no local books.
                localBooks = books,
                libraryBooks = books,
                totalBooks = books,
                localFiles = fileEntries.size,
                pluginFiles = pluginEntries.size,
            ),
        )
        File(staging, MANIFEST_ENTRY).writeText(
            BackupJson.encodeToString(BackupManifest.serializer(), manifest),
        )
        return NovelaImportReport(
            books = books,
            booksSkipped = booksSkipped,
            chapters = chapters,
            progress = progress,
            bodies = bodies,
            bodiesSkipped = bodiesSkipped,
            covers = covers,
            coversSkipped = coversSkipped,
            plugins = accepted,
            skippedPlugins = skipped,
            degradedProgress = degraded,
            unmatchedBooks = unmatched,
        )
    }

    private suspend fun extractZip(
        input: InputStream,
        novelaDb: File,
        coversDir: File,
        pluginsDir: File,
        onProgress: (Float) -> Unit,
        onStage: ((RestoreProgress) -> Unit)?,
    ) {
        val zip = ZipInputStream(BufferedInputStream(input))
        var total = 0L
        var processed = 0
        var sawDatabase = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = entry.name
                val cover = coverSegment(name)
                val plugin = pluginName(name)
                when {
                    name == NOVELA_DB_ENTRY -> {
                        sawDatabase = true
                        val written = copyEntry(zip, novelaDb, MAX_NOVELA_DB_BYTES, total)
                        if (written == 0L) error("NoveLA backup database is empty")
                        total += written
                    }
                    cover != null -> {
                        total += copyEntry(
                            zip,
                            File(File(coversDir, cover), "cover"),
                            MAX_NOVELA_COVER_BYTES,
                            total,
                        )
                    }
                    plugin != null -> {
                        total += copyEntry(
                            zip,
                            File(pluginsDir, "$plugin.lua"),
                            MAX_NOVELA_PLUGIN_BYTES,
                            total,
                        )
                    }
                    // NoveLA archives carry settings.json and unrelated files.
                    else -> Unit
                }
                processed++
                if (processed > MAX_NOVELA_ENTRIES) error("NoveLA backup has too many entries")
                onProgress(
                    0.05f + 0.45f * (processed.toFloat() / PROGRESS_ENTRY_CAP).coerceAtMost(1f),
                )
                onStage?.invoke(
                    RestoreProgress(
                        RestoreStage.EXTRACTING,
                        processed,
                        maxOf(processed, PROGRESS_ENTRY_CAP),
                    ),
                )
            }
        } finally {
            zip.close()
        }
        if (!sawDatabase) error("NoveLA backup has no database.sqlite3")
    }

    /** Copies one entry with a per-entry cap, enforcing the total budget. */
    private suspend fun copyEntry(
        zip: ZipInputStream,
        target: File,
        maxBytes: Long,
        totalBefore: Long,
    ): Long {
        target.parentFile?.mkdirs()
        val buffer = ByteArray(CHUNK_BYTES)
        var written = 0L
        target.outputStream().use { out ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = zip.read(buffer)
                if (read < 0) break
                written += read
                if (written > maxBytes) error("NoveLA backup entry is too large")
                if (totalBefore + written > MAX_NOVELA_TOTAL_BYTES) {
                    error("NoveLA backup exceeds the size limit")
                }
                out.write(buffer, 0, read)
            }
        }
        return written
    }

    private suspend fun writeBook(
        dao: LibraryDao,
        staging: File,
        novela: NovelaDatabase,
        book: NovelaBookRow,
        shelfId: String,
        pluginRefs: List<InstalledPluginRef>,
        cover: File?,
        fileEntries: MutableList<BackupFileEntry>,
        onStage: ((RestoreProgress) -> Unit)?,
    ): BookWrite {
        val chapters = novela.chapters(book.url)
        val bodyRows = novela.bodies(chapters.map { it.url })
        val bodyByUrl = bodyRows.associateBy { it.chapterUrl }
        val bookHost = hostOf(book.url)
        val resolvedInstall = existingInstallIdForUrl(book.url)
            ?: pluginRefs.firstOrNull { ref ->
                bookHost != null && hostOf(ref.baseUrl) == bookHost
            }?.id
        val installId = resolvedInstall ?: bookHost ?: FALLBACK_INSTALL_ID
        val bookId = dao.upsertBook(
            BookEntity(
                installId = installId,
                url = book.url,
                title = book.title,
                author = "",
                coverUrl = book.coverUrl.ifBlank { null },
                contentType = if (
                    book.contentType.equals("manga", true) || book.contentType.equals("comic", true)
                ) {
                    "MANGA"
                } else {
                    "NOVEL"
                },
                rating = book.rating.toDoubleOrNull() ?: 0.0,
                shelfId = shelfId,
                totalChapters = chapters.size,
                inLibrary = book.inLibrary,
                addedAt = book.addedAt,
                updatedAt = book.updatedAt,
                lastReadAt = book.lastReadAt.takeIf { it > 0 },
                lastChapterId = null,
                lastChapterIndex = null,
                chapterListHash = book.chaptersListHash,
                lastUpdateAt = book.updatedAt.takeIf { it > 0 },
                genresJson = genresJson(book.genres),
                description = book.description,
                ratingRaw = book.rating,
                statusRaw = book.status.ifBlank { if (book.completed) "Completed" else "" },
                lastUpdatedRaw = book.lastUpdateDate,
                metadataSource = "PLUGIN",
            ),
        )
        check(bookId > 0) { "NoveLA book row was not inserted" }

        val chapterIdByUrl = HashMap<String, Long>(chapters.size)
        val chapterIndexByUrl = HashMap<String, Int>(chapters.size)
        val lastReadChapter = book.lastReadChapter?.takeIf { it.isNotBlank() }
        chapters.forEachIndexed { index, chapter ->
            // NoveLA positions are not unique; the listed order is the identity.
            val chapterId = dao.upsertChapter(
                ChapterEntity(
                    bookId = bookId,
                    idx = index + 1,
                    url = chapter.url,
                    title = chapter.title,
                    read = chapter.read,
                    readAt = if (chapter.url == lastReadChapter) {
                        book.lastReadAt.takeIf { it > 0 }
                    } else {
                        null
                    },
                    downloaded = bodyByUrl.containsKey(chapter.url),
                    updatedAt = book.updatedAt,
                    volume = chapter.volume,
                    uploadedAt = chapter.uploadedAt?.let { it * 1000 },
                ),
            )
            chapterIdByUrl[chapter.url] = chapterId
            chapterIndexByUrl[chapter.url] = index + 1
        }

        var degraded = 0
        var progressCount = 0
        val lastChapterId = lastReadChapter?.let { chapterIdByUrl[it] }
        // 1-based, as everywhere else in this app.
        val lastChapterIndex = lastReadChapter?.let { chapterIndexByUrl[it] }
        if (lastChapterId != null && lastChapterIndex != null) {
            dao.setLastRead(
                bookId = bookId,
                readAt = book.lastReadAt.takeIf { it > 0 },
                chapterId = lastChapterId,
                chapterIndex = lastChapterIndex,
            )
            if (book.lastReadAt > 0) {
                val chapter = chapters[lastChapterIndex - 1]
                val pos = chapter.lastReadPosition
                // NoveLA numbers the chapter title as item 0, so paragraph item
                // n is block n-1; the bound is this chapter's block count, not
                // the book's chapter count.
                val blockLimit = bodyByUrl[chapter.url]?.let { novelaBlockCount(it.body) }
                val blockIndex = when {
                    pos == 0 -> 0
                    pos > 0 && (blockLimit == null || pos - 1 < blockLimit) -> pos - 1
                    else -> {
                        degraded++
                        0
                    }
                }
                dao.upsertProgress(
                    ProgressEntity(
                        bookId = bookId,
                        chapterId = lastChapterId,
                        blockIndex = blockIndex,
                        charOffset = if (pos >= 0) chapter.lastReadOffset else 0,
                        sentenceIndex = null,
                        offsetPx = 0,
                        split = false,
                        seq = book.lastReadAt,
                        updatedAt = book.lastReadAt,
                    ),
                )
                progressCount++
            }
        } else if (lastReadChapter != null) {
            degraded++
        }

        var bodyCount = 0
        var bodySkipped = 0
        for (row in bodyRows) {
            val chapterId = chapterIdByUrl[row.chapterUrl] ?: continue
            val html = novelaBodyToHtml(row.body)
            if (html.isEmpty()) {
                bodySkipped++
                continue
            }
            val urlHash = sha256Hex(row.chapterUrl.encodeToByteArray())
            val relative = "novela/text/$urlHash.html"
            val bytes = html.encodeToByteArray()
            val staged = File(staging, "files/$relative")
            staged.parentFile?.mkdirs()
            staged.writeBytes(bytes)
            val digest = sha256Hex(bytes)
            dao.upsertChapterBody(
                ChapterBodyEntity(
                    chapterId = chapterId,
                    format = "html",
                    path = File(filesDir, relative).absolutePath,
                    bytes = bytes.size.toLong(),
                    sha256 = digest,
                    fetchedAt = now(),
                ),
            )
            fileEntries += BackupFileEntry(
                path = "files/$relative",
                bookId = bookId,
                kind = KIND_CHAPTER_BODY,
                size = bytes.size.toLong(),
                sha256 = digest,
            )
            bodyCount++
            onStage?.invoke(
                RestoreProgress(
                    RestoreStage.FILES,
                    fileEntries.size,
                    maxOf(fileEntries.size, PROGRESS_ENTRY_CAP),
                ),
            )
        }

        var coverCount = 0
        var coverSkipped = 0
        if (cover != null) {
            val bytes = cover.readBytes()
            if (CoverBytes.isValidImage(bytes)) {
                val staged = File(staging, "files/covers/$bookId/cover")
                staged.parentFile?.mkdirs()
                cover.copyTo(staged, overwrite = true)
                val digest = sha256Hex(bytes)
                dao.upsertBookCover(
                    BookCoverEntity(
                        bookId = bookId,
                        remoteUrl = book.coverUrl.ifBlank { null },
                        localPath = File(filesDir, "covers/$bookId/cover").absolutePath,
                        sha256 = digest,
                        bytes = bytes.size.toLong(),
                        isUserOverride = false,
                        fetchedAt = now(),
                        checkedAt = now(),
                    ),
                )
                fileEntries += BackupFileEntry(
                    path = "files/covers/$bookId/cover",
                    bookId = bookId,
                    kind = KIND_COVER,
                    size = bytes.size.toLong(),
                    sha256 = digest,
                )
                coverCount++
            } else {
                coverSkipped++
            }
        }

        return BookWrite(
            chapters = chapters.size,
            progress = progressCount,
            bodies = bodyCount,
            bodiesSkipped = bodySkipped,
            covers = coverCount,
            coversSkipped = coverSkipped,
            degraded = degraded,
            installUnmatched = resolvedInstall == null,
        )
    }

    private suspend fun upsertShelves(
        dao: LibraryDao,
        rows: List<NovelaBookRow>,
    ): Map<String, String> {
        val shelfIds = LinkedHashMap<String, String>()
        val inserted = HashSet<String>()
        var position = 0
        rows.map { it.category.trim() }.filter { it.isNotEmpty() }.distinct().forEach { label ->
            val id = "novela:" + label.lowercase(Locale.ROOT)
            shelfIds[label] = id
            if (inserted.add(id)) {
                dao.upsertShelf(ShelfEntity(id = id, label = label, position = position++))
            }
        }
        return shelfIds
    }

    private data class StagedPlugin(
        val id: String,
        val version: String,
        val baseUrl: String,
        val file: File,
    )

    private data class BookWrite(
        val chapters: Int,
        val progress: Int,
        val bodies: Int,
        val bodiesSkipped: Int,
        val covers: Int,
        val coversSkipped: Int,
        val degraded: Int,
        val installUnmatched: Boolean,
    )

    // Parsed here, not during extraction: the Lua header is untrusted input and
    // only the mapping phase needs version/baseUrl.
    private fun stagedPlugins(pluginsDir: File): List<StagedPlugin> =
        pluginsDir.listFiles { file -> file.isFile && file.name.endsWith(".lua") }
            .orEmpty()
            .sortedBy { it.name }
            .map { file ->
                val metadata = ScriptMetadataParser.parse(file.readText())
                StagedPlugin(
                    id = file.name.removeSuffix(".lua"),
                    version = metadata.version.orEmpty(),
                    baseUrl = metadata.baseUrl.orEmpty(),
                    file = file,
                )
            }
            .filter { it.id.isNotBlank() }

    private fun installPlugins(
        staging: File,
        stagedPlugins: List<StagedPlugin>,
        installed: List<InstalledPluginRef>,
    ): Triple<List<BackupPluginEntry>, Int, Int> {
        val installedVersions = installed.associate { it.id to it.version }
        val entries = mutableListOf<BackupPluginEntry>()
        var skipped = 0
        for (plugin in stagedPlugins) {
            val installedVersion = installedVersions[plugin.id]
            if (installedVersion != null && !isNewerVersion(plugin.version, installedVersion)) {
                skipped++
                continue
            }
            val bytes = plugin.file.readBytes()
            val relative = "lua_plugins/${plugin.id}.lua"
            val target = File(File(staging, "plugins"), relative)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            entries += BackupPluginEntry(
                path = "plugins/$relative",
                size = bytes.size.toLong(),
                sha256 = sha256Hex(bytes),
            )
        }
        return Triple(entries, entries.size, skipped)
    }

    /**
     * Cover folders are `Base64(url)` or `h_<sha256 prefix>`; only the hashed
     * form needs decoding, so the map is built from the book urls themselves.
     */
    private fun stagedCovers(coversDir: File, urls: List<String>): Map<String, File> {
        // The folder name is Base64, so it may contain '/': resolve the whole
        // relative path instead of looking at direct children only.
        return urls.mapNotNull { url ->
            val file = novelaCoverFolderCandidates(url)
                .map { File(File(coversDir, it), "cover") }
                .firstOrNull { it.isFile }
                ?: return@mapNotNull null
            url to file
        }.toMap()
    }

    private fun genresJson(raw: String): String = buildJsonArray {
        raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { add(JsonPrimitive(it)) }
    }.toString()

    private fun hostOf(url: String): String? = runCatching {
        URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val STAGING_TMP_DIR = "_novela"
        const val COVERS_DIR = "covers"
        const val PLUGINS_DIR = "plugins"
        const val CONVERTED_DB = "converted.db"
        const val DEFAULT_SHELF_ID = "reading"
        // A URL that is not a usable host still needs an install id; keeping one
        // constant means such books group together instead of failing the import.
        const val FALLBACK_INSTALL_ID = "novela"
        const val CHUNK_BYTES = 64 * 1024
        const val MAX_NOVELA_DB_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_NOVELA_COVER_BYTES = 16L * 1024 * 1024
        const val MAX_NOVELA_PLUGIN_BYTES = 2L * 1024 * 1024
        const val MAX_NOVELA_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_NOVELA_ENTRIES = 100_000
        const val PROGRESS_ENTRY_CAP = 300
    }
}

/** The NoveLA archive entry that carries the backup database. */
internal const val NOVELA_DB_ENTRY = "database.sqlite3"

/** Block count of the HTML [novelaBodyToHtml] produces for [body]. */
internal fun novelaBlockCount(body: String): Int =
    novelaBodyToHtml(body).let { if (it.isEmpty()) 0 else it.split("<p>").size - 1 }

/**
 * Mirrors the blank-line boundaries of NoveLA's *stored* chapter text.
 *
 * NoveLA's reader additionally splits paragraphs longer than 800 chars
 * (`TextToItemsConverter.splitParagraphRespectingLogicalBlocks`), so this count
 * equals MiruTime's block count, not necessarily NoveLA's item count.
 *
 * shortcut: mirrors NoveLA paragraph boundaries (translations align by
 * paragraph index, but are not imported); tag stripping differs on purpose.
 */
internal fun novelaBodyToHtml(text: String): String {
    val normalized = text
        .replace("\r\n", "\n")
        .replace('\u00A0', ' ')
        .replace(SPACE_RUN, " ")
    val paragraphs = normalized.split(PARAGRAPH_BREAK)
        .let { if (it.size <= 1 && normalized.contains('\n')) normalized.split('\n') else it }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return paragraphs.joinToString("\n") { "<p>${it.escapeAngles()}</p>" }
}

private fun String.escapeAngles(): String = replace('<', '\u27E8').replace('>', '\u27E9')

/**
 * NoveLA stores a cover per book under `Base64(url)`, or under a hash when the
 * encoded folder name is too long for a file name. Base64 may contain '/', so
 * the result is a relative path, not one path segment.
 */
internal fun novelaCoverFolderName(url: String): String {
    val bytes = url.encodeToByteArray()
    val encoded = Base64.getEncoder().encodeToString(bytes)
    return if (encoded.length <= MAX_COVER_NAME_LENGTH) {
        encoded
    } else {
        "h_" + sha256Hex(bytes).take(32)
    }
}

/**
 * Folder names to try for one book, newest first. Archives written before
 * NoveLA commit `1263574c` used the un-truncated Base64 name even when it
 * exceeded the length cap.
 */
internal fun novelaCoverFolderCandidates(url: String): List<String> =
    listOf(novelaCoverFolderName(url), Base64.getEncoder().encodeToString(url.encodeToByteArray()))
        .filter { it.length <= 240 }
        .distinct()

/** First non-directory ZIP entry name without consuming the stream. */
internal fun peekZipFirstEntry(input: InputStream): Pair<String?, InputStream> {
    val header = ByteArray(ZIP_LOCAL_HEADER_BYTES)
    var headerRead = 0
    while (headerRead < header.size) {
        val read = input.read(header, headerRead, header.size - headerRead)
        if (read < 0) break
        headerRead += read
    }
    var consumed = header.copyOf(headerRead)
    fun stream(): InputStream = SequenceInputStream(ByteArrayInputStream(consumed), input)
    if (headerRead < ZIP_LOCAL_HEADER_BYTES) return null to stream()
    val signatureMatches = header[0] == 0x50.toByte() &&
        header[1] == 0x4B.toByte() &&
        header[2] == 0x03.toByte() &&
        header[3] == 0x04.toByte()
    if (!signatureMatches) return null to stream()
    val nameLength = readUInt16Le(header, 26)
    val name = ByteArray(nameLength)
    var nameRead = 0
    while (nameRead < nameLength) {
        val read = input.read(name, nameRead, nameLength - nameRead)
        if (read < 0) break
        nameRead += read
    }
    consumed = consumed + name.copyOf(nameRead)
    if (nameRead < nameLength) return null to stream()
    return name.decodeToString() to stream()
}

private fun readUInt16Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun coverSegment(entryName: String): String? {
    if (!entryName.startsWith("books/") || !entryName.endsWith("/__cover_image")) return null
    // Base64 folder names may contain '/', so the relative path can be deeper
    // than one segment; every segment still has to be a plain directory name.
    val folder = entryName.removePrefix("books/").removeSuffix("/__cover_image")
    if (folder.isBlank()) return null
    val parts = folder.split('/')
    if (parts.any { it.isBlank() || it == "." || it == ".." || it.contains('\\') }) return null
    return folder
}

private fun pluginName(entryName: String): String? {
    if (!entryName.startsWith("lua_extensions/") || !entryName.endsWith(".lua")) return null
    val parts = entryName.split('/')
    if (parts.size != 2) return null
    val stem = parts[1].removeSuffix(".lua")
    if (stem.isBlank() || stem.contains('\\') || stem == "." || stem == "..") return null
    return stem
}

private val SPACE_RUN = Regex("[ \t]+")
private val PARAGRAPH_BREAK = Regex("\n\\s*\n")
private const val MAX_COVER_NAME_LENGTH = 200
private const val ZIP_LOCAL_HEADER_BYTES = 30
