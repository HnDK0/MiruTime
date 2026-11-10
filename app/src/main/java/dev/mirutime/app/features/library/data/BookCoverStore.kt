package dev.mirutime.app.features.library.data

import dev.mirutime.data.library.BookCoverEntity
import dev.mirutime.core.log.MiruLog
import dev.mirutime.imageloader.FetchedImage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// One fetched cover body is capped here; the image lane that produced it has
// its own cap in PluginGraph.
private const val MAX_COVER_BYTES = 16L * 1024 * 1024

// Library covers live in files/covers/<bookId>/cover. The remote URL is only a
// fetch source; the local file is what the UI renders. One mutex per book keeps
// concurrent additions/backfills from racing on the same file, while different
// books still download in parallel.
class BookCoverStore(
    private val root: File,
    private val load: suspend (Long) -> BookCoverEntity?,
    private val save: suspend (BookCoverEntity) -> Unit,
    private val fetch: suspend (sourceId: String, url: String, headers: Map<String, String>) -> FetchedImage,
    private val headersFor: (String) -> Map<String, String> = { emptyMap() },
    // Magic bytes prove a supported container, not a readable image: a cut-off
    // PNG still starts with the signature. Production injects a real header
    // decode (AndroidCoverDecoder); the JVM default keeps the unit tests pure.
    private val isDecodable: (ByteArray) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    // Valid file fast path: never touch the network. Callers use this before
    // enqueueing a backfill so already-covered books cost one stat.
    suspend fun localCoverUrl(bookId: Long): String? = withContext(Dispatchers.IO) {
        val row = runCatching { load(bookId) }.getOrNull()
        val file = validLocal(bookId, row) ?: return@withContext null
        coverUri(file, row?.sha256)
    }

    // Batch variant: the caller already loaded every book_covers row. List
    // screens run on the UI thread, so trust the persisted row here instead of
    // stat-ing every file; the suspend variant above does the file check on IO.
    fun localCoverUrl(bookId: Long, row: BookCoverEntity?): String? {
        val stored = row?.localPath
            ?.takeIf { it.isNotBlank() && (row.bytes ?: 0L) > 0L }
            ?.let(::File)
            ?: return null
        // No file stat here: list screens run on Main. The stored size is the
        // version fallback when a legacy row has no sha256.
        return coverUri(stored, row.sha256, row.bytes ?: 0L)
    }

    // A dead remote URL (404/410/transport/invalid body) is worth one repair:
    // Hitomi rotates CDN tokens and a site may replace a broken cover. A
    // transient 5xx cooldown is already time-bounded by retryAt.
    suspend fun needsRepair(bookId: Long): Boolean =
        isDeadCoverError(runCatching { load(bookId) }.getOrNull()?.lastError)

    // Local cover bytes for consumers that must not touch the network (EPUB
    // export): a missing or invalid file is simply null.
    suspend fun localCoverBytes(bookId: Long): ByteArray? = lockFor(bookId).withLock {
        val row = runCatching { load(bookId) }.getOrNull()
        val file = validLocal(bookId, row) ?: return@withLock null
        if (file.length() > MAX_COVER_BYTES) return@withLock null
        runCatching { file.readBytes() }
            .getOrNull()
            ?.takeIf { it.size <= MAX_COVER_BYTES && CoverBytes.isValidImage(it) }
    }

    // Remote URL from book_covers only; the legacy books.coverUrl fallback
    // stays with the library provider so the store never mixes the two.
    suspend fun remoteCoverUrl(bookId: Long): String? =
        remoteCoverUrl(runCatching { load(bookId) }.getOrNull())

    fun remoteCoverUrl(row: BookCoverEntity?): String? = row
        ?.remoteUrl
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // Resolves a book's cover, downloading only when the persisted state says
    // it is worth a request. Returns a versioned file URI or null.
    suspend fun ensureCover(
        bookId: Long,
        sourceId: String?,
        remoteUrl: String?,
        // Repair: ignore the local-file fast path and the retry cooldown so a
        // freshly resolved URL gets one real fetch. A failed force keeps the
        // old file/URI (every failure branch below returns `local`).
        force: Boolean = false,
    ): String? {
        val url = remoteUrl?.trim()?.takeIf { it.isNotEmpty() }
        return lockFor(bookId).withLock {
            val at = clock()
            val row = runCatching { load(bookId) }.getOrNull()
            val local = validLocal(bookId, row)
            if (url == null) return@withLock local?.let { coverUri(it, row?.sha256) }

            // A valid local file is authoritative. Hitomi-style CDN tokens
            // change between page loads; the downloaded cover must survive a
            // changed remote URL. Refresh the stored URL only — no fetch, no
            // overwrite. The network is reached only when the file is missing
            // or invalid, or the caller explicitly asks for a repair.
            if (local != null && !force) {
                if (row?.remoteUrl != url) {
                    runCatching {
                        save(
                            (row ?: BookCoverEntity(bookId = bookId, remoteUrl = url)).copy(
                                remoteUrl = url,
                                localPath = local.absolutePath,
                            ),
                        )
                    }
                }
                return@withLock coverUri(local, row?.sha256)
            }

            val retryAt = row?.retryAt
            if (!force && retryAt != null && retryAt > at) return@withLock null

            val target = coverFile(bookId)
            val headers = LinkedHashMap(headersFor(url))
            // Per-host headers carry UA; the Referer of the referenced page is
            // only known from the persisted row (hotlink-protected covers).
            row?.referer?.takeIf { it.isNotBlank() }?.let { headers.putIfAbsent("Referer", it) }
            // Persist the fresh URL before attempting the download so a crash
            // mid-fetch leaves the next run knowing what to retry.
            val base = (row ?: BookCoverEntity(bookId = bookId, remoteUrl = url)).copy(
                remoteUrl = url,
                localPath = target.absolutePath,
                referer = headers["Referer"] ?: row?.referer,
            )
            runCatching { save(base) }

            // Evidence line for the device check: exactly one per real request.
            MiruLog.d("covers", "event=cover-fetch book=$bookId force=$force referer=${headers["Referer"] != null}")
            val result = try {
                fetch(sourceId.orEmpty(), url, headers)
            } catch (e: Exception) {
                FetchedImage(status = 0)
            }

            when {
                result.status == HTTP_NOT_MODIFIED -> {
                    runCatching {
                        save(
                            base.copy(
                                checkedAt = at,
                                etag = result.etag ?: row?.etag,
                                lastModified = result.lastModified ?: row?.lastModified,
                                lastError = null,
                                retryAt = null,
                            ),
                        )
                    }
                    local?.let { coverUri(it, row?.sha256) }
                }

                result.status in 200..299 -> {
                    val bytes = result.bytes ?: ByteArray(0)
                    if (bytes.size > MAX_COVER_BYTES ||
                        !CoverBytes.isValidImage(bytes) ||
                        !isDecodable(bytes)
                    ) {
                        // Never overwrite a good file with an error page. The
                        // stored bytes are untouched; retry after a cooldown.
                        runCatching {
                            save(
                                base.copy(
                                    checkedAt = at,
                                    lastError = "invalid image",
                                    retryAt = at + TRANSIENT_COOLDOWN_MS,
                                ),
                            )
                        }
                        local?.let { coverUri(it, row?.sha256) }
                    } else {
                        val sha256 = CoverBytes.sha256Hex(bytes)
                        val written = runCatching { writeAtomic(target, bytes) }.isSuccess
                        if (!written) {
                            runCatching {
                                save(
                                    base.copy(
                                        checkedAt = at,
                                        lastError = "write failed",
                                        retryAt = at + TRANSIENT_COOLDOWN_MS,
                                    ),
                                )
                            }
                            local?.let { coverUri(it, row?.sha256) }
                        } else {
                            runCatching {
                                save(
                                    base.copy(
                                        localPath = target.absolutePath,
                                        sha256 = sha256,
                                        bytes = bytes.size.toLong(),
                                        fetchedAt = at,
                                        checkedAt = at,
                                        etag = result.etag,
                                        lastModified = result.lastModified,
                                        lastError = null,
                                        retryAt = null,
                                    ),
                                )
                            }
                            coverUri(target, sha256)
                        }
                    }
                }

                result.status == 404 || result.status == 410 -> {
                    runCatching {
                        save(
                            base.copy(
                                checkedAt = at,
                                lastError = "HTTP ${result.status}",
                                retryAt = at + NOT_FOUND_COOLDOWN_MS,
                            ),
                        )
                    }
                    local?.let { coverUri(it, row?.sha256) }
                }

                else -> {
                    runCatching {
                        save(
                            base.copy(
                                checkedAt = at,
                                lastError = "HTTP ${result.status}",
                                retryAt = at + TRANSIENT_COOLDOWN_MS,
                            ),
                        )
                    }
                    local?.let { coverUri(it, row?.sha256) }
                }
            }
        }
    }

    // Embedded local cover (imported EPUB/FB2/CBZ): no fetch, same atomic
    // file/path as a downloaded one so the library resolves it unchanged.
    suspend fun storeLocalCover(bookId: Long, remoteUrl: String?, bytes: ByteArray): Boolean {
        if (!CoverBytes.isValidImage(bytes)) return false
        return lockFor(bookId).withLock {
            val at = clock()
            val target = coverFile(bookId)
            val written = runCatching { writeAtomic(target, bytes) }.isSuccess
            if (!written) return@withLock false
            val row = runCatching { load(bookId) }.getOrNull()
            runCatching {
                save(
                    (row ?: BookCoverEntity(bookId = bookId, remoteUrl = remoteUrl)).copy(
                        remoteUrl = remoteUrl,
                        localPath = target.absolutePath,
                        sha256 = CoverBytes.sha256Hex(bytes),
                        bytes = bytes.size.toLong(),
                        fetchedAt = at,
                        checkedAt = at,
                        lastError = null,
                        retryAt = null,
                    ),
                )
            }
            true
        }
    }

    private fun lockFor(bookId: Long): Mutex = locks.getOrPut(bookId) { Mutex() }

    private fun coverFile(bookId: Long): File = File(File(root, bookId.toString()), COVER_NAME)

    // The fragment is the Coil cache key: a repaired file gets a new one even
    // though the path never changes. Devices where Coil ignores file-URI
    // fragments are covered by renaming — VERIFY, see plan.
    private fun coverUri(file: File, sha256: String?): String =
        coverUri(file, sha256, file.length())

    private fun coverUri(file: File, sha256: String?, sizeFallback: Long): String {
        val version = sha256?.takeIf { it.length >= SHA_PREFIX }?.take(SHA_PREFIX)
            ?: sizeFallback.toString()
        // File.toURI() stats the path (isDirectory); list screens run on Main.
        val uri = java.net.URI("file", null, file.absolutePath, null).toASCIIString()
        return "$uri#v=$version"
    }

    private fun validLocal(bookId: Long, row: BookCoverEntity?): File? {
        val stored = row?.localPath?.takeIf { it.isNotBlank() }?.let(::File)
        if (stored != null && isValidFile(stored)) return stored
        val fallback = coverFile(bookId)
        return fallback.takeIf(::isValidFile)
    }

    private fun isValidFile(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        // Magic bytes only: never read a full cover just to validate it.
        val head = ByteArray(MAGIC_BYTES)
        val read = file.inputStream().use { it.read(head) }
        return read > 0 && CoverBytes.isValidImage(head.copyOf(read))
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: error("cover path has no parent: $target")
        if (!dir.isDirectory && !dir.mkdirs()) error("cannot create $dir")
        val temp = File.createTempFile("cover-", ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}

// Pure image validation shared by the store and its tests: magic bytes only
// (decoding is Coil's job) plus a non-empty body.
object CoverBytes {
    fun isValidImage(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) return false
        return when {
            startsWith(bytes, 0xFF, 0xD8, 0xFF) -> true
            startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> true
            asciiAt(bytes, 0, "GIF87a") || asciiAt(bytes, 0, "GIF89a") -> true
            asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "WEBP") -> true
            else -> false
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        return prefix.indices.all { bytes[it] == prefix[it].toByte() }
    }

    private fun asciiAt(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (bytes.size < offset + text.length) return false
        return text.indices.all { bytes[offset + it] == text[it].code.toByte() }
    }
}

// A persisted error that means the remote URL itself is dead, not just
// temporarily unavailable: those are worth a source re-resolve (repair).
internal fun isDeadCoverError(lastError: String?): Boolean = when (lastError) {
    "HTTP 404", "HTTP 410", "HTTP 0", "invalid image" -> true
    else -> false
}

private const val COVER_NAME = "cover"
private const val SHA_PREFIX = 8
private const val MAGIC_BYTES = 12
private const val HTTP_NOT_MODIFIED = 304
private const val NOT_FOUND_COOLDOWN_MS = 24 * 60 * 60 * 1000L
private const val TRANSIENT_COOLDOWN_MS = 15 * 60 * 1000L
