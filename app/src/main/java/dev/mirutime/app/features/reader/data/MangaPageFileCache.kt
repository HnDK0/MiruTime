package dev.mirutime.app.features.reader.data

import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local files for manga pages. The tiled viewer needs a file (it region-decodes
 * tiles instead of loading a whole bitmap), so each page is fetched once and
 * kept in the app cache with an LRU-style byte budget.
 *
 * Cache identity is the page URL; two pages never share a file. A failed fetch
 * returns null and leaves no partial file behind.
 */
class MangaPageFileCache(
    private val directory: File,
    private val fetchRemote: suspend (url: String, headers: Map<String, String>) -> ByteArray?,
    private val headersFor: (String) -> Map<String, String>,
    // Non-network pages (content://, android.resource://) are read by the app.
    private val readLocalBytes: suspend (String) -> ByteArray? = { null },
    private val maxBytes: Long = MAX_BYTES,
) {
    private val locks = mutableMapOf<String, Mutex>()
    private val locksGuard = Mutex()

    suspend fun file(url: String): File? {
        if (url.isBlank()) return null
        directFile(url)?.let { return it }
        val key = sha256(url)
        val target = File(directory, "$key.${extension(url)}")
        val lock = lockFor(key)
        return lock.withLock {
            if (target.isFile && target.length() > 0L) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            val bytes = when {
                url.startsWith("http://") || url.startsWith("https://") ->
                    fetchRemote(url, headersFor(url))
                else -> readLocal(url)
            } ?: return@withLock null
            if (bytes.isEmpty()) return@withLock null
            val tmp = File(directory, "$key.tmp")
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            trim()
            target
        }
    }

    private suspend fun lockFor(key: String): Mutex =
        locksGuard.withLock { locks.getOrPut(key) { Mutex() } }

    private fun directFile(url: String): File? = runCatching {
        when {
            url.startsWith("file://") -> File(URI(url)).takeIf { it.isFile }
            url.startsWith("/") -> File(url).takeIf { it.isFile }
            else -> null
        }
    }.getOrNull()

    private suspend fun readLocal(url: String): ByteArray? =
        runCatching { readLocalBytes.invoke(url) }.getOrNull()

    private suspend fun trim() = withContext(Dispatchers.IO) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return@withContext
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return@withContext
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return@forEach
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun extension(url: String): String {
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in PAGE_EXTENSIONS } ?: "img"
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        // Pages are re-fetchable: the cache may evict aggressively.
        const val MAX_BYTES = 256L * 1024 * 1024
        val PAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")
    }
}
