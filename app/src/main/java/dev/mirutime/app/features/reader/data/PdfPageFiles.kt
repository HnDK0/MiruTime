package dev.mirutime.app.features.reader.data

import dev.mirutime.data.library.LocalBookFileEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val PdfPageUrlScheme = "pdfpage://"

/** Importer cap: one chapter row holds every page reference. */
internal const val MaxPdfPages = 5000

internal data class PdfPageRef(val bookId: Long, val index: Int)

/** `pdfpage://<bookId>/<index>` is the only shape a PDF page reference has. */
internal fun parsePdfPageUrl(url: String): PdfPageRef? {
    if (!url.startsWith(PdfPageUrlScheme)) return null
    val parts = url.removePrefix(PdfPageUrlScheme).trimEnd('/').split('/')
    if (parts.size != 2) return null
    val bookId = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
    val index = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return PdfPageRef(bookId, index)
}

internal fun pdfPageUrl(bookId: Long, index: Int): String = "$PdfPageUrlScheme$bookId/$index"

/** Every page of an imported PDF as `pdfpage://` rows, capped. */
internal fun pdfPageRefs(bookId: Long, pageCount: Int): List<String> =
    (0 until pageCount.coerceIn(0, MaxPdfPages)).map { pdfPageUrl(bookId, it) }

/**
 * Turns a `pdfpage://` reference into a JPEG in the app cache. The page is
 * rendered by the isolated-process renderer on first use; a re-import of the
 * same book keeps the same page numbers, so the cache needs no mtime check.
 */
class PdfPageFiles(
    // The owning book row: production passes `LibraryRepository::localBookFile`.
    private val localBookFile: suspend (bookId: Long) -> LocalBookFileEntity?,
    private val baseDir: File,
    private val root: File,
    // The renderer is the isolated-process client in production; a test injects
    // a plain function instead.
    private val render: suspend (pdf: File, index: Int, widthPx: Int, out: File) -> Boolean,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val renderLock = Mutex()

    suspend fun file(url: String): File? {
        val ref = parsePdfPageUrl(url) ?: return null
        // Defense in depth: only a PDF row may be rendered, whatever the URL says.
        val row = localBookFile(ref.bookId) ?: return null
        if (row.format != "PDF") return null
        val copyPath = row.copyPath?.takeIf { it.isNotBlank() } ?: return null
        val pdf = File(baseDir, copyPath)
        if (!withContext(Dispatchers.IO) { pdf.isFile }) return null
        val target = File(File(root, ref.bookId.toString()), "${ref.index}.jpg")
        return renderLock.withLock {
            if (isCached(target)) {
                withContext(Dispatchers.IO) {
                    target.setLastModified(System.currentTimeMillis())
                }
                return@withLock target
            }
            // A partial render must never look like a cached page.
            val temp = File(target.parentFile, "${target.name}.tmp")
            withContext(Dispatchers.IO) { target.parentFile?.mkdirs() }
            if (!render(pdf, ref.index, MAX_RENDER_WIDTH, temp)) {
                withContext(Dispatchers.IO) { temp.delete() }
                return@withLock null
            }
            val ready = withContext(Dispatchers.IO) {
                temp.isFile && temp.length() > 0L && isJpeg(temp) && temp.renameTo(target)
            }
            if (!ready) {
                withContext(Dispatchers.IO) { temp.delete() }
                return@withLock null
            }
            evict()
            target
        }
    }

    private suspend fun evict() = withContext(Dispatchers.IO) {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return@withContext
        // Least recently used first; a cache hit bumps the mtime above.
        files.sortedBy { it.lastModified() }.forEach { candidate ->
            if (total <= maxBytes) return@forEach
            val length = candidate.length()
            if (candidate.delete()) total -= length
        }
    }

    private suspend fun isCached(file: File): Boolean = withContext(Dispatchers.IO) {
        file.isFile && file.length() > 0L && isJpeg(file)
    }

    private fun isJpeg(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            input.read() == 0xFF && input.read() == 0xD8
        }
    }.getOrDefault(false)

    private companion object {
        // The renderer caps the width itself; this is the phone target.
        const val MAX_RENDER_WIDTH = 1600
        // Rendered pages are re-creatable from the imported PDF.
        const val MAX_BYTES = 256L * 1024 * 1024
    }
}
