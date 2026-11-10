package dev.mirutime.app.features.downloads.data

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Writes final files only through a same-directory `.part` rename, so a
 * process death never leaves a path that the DB row claims is complete.
 */
class LocalDownloadFileStore(private val root: File) : DownloadFileStore {
    override fun writeText(bookId: Long, chapterId: Long, html: String): StoredText {
        val target = textFile(bookId, chapterId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        temp.writeText(html)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot store chapter body" }
        }
        return StoredText(
            path = target.absolutePath,
            bytes = target.length(),
            sha256 = sha256(html.toByteArray()),
        )
    }

    override fun readText(path: String): String? =
        runCatching { containedFile(path)?.takeIf { it.isFile }?.readText() }.getOrNull()

    override fun writePage(
        bookId: Long,
        chapterId: Long,
        index: Int,
        url: String,
        bytes: ByteArray,
    ): String {
        val directory = pageDir(bookId, chapterId)
        directory.mkdirs()
        val target = File(directory, "$index.${extension(url)}")
        val temp = File(directory, "${target.name}.part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot store manga page" }
        }
        return target.absolutePath
    }

    override fun textExists(path: String): Boolean = containedFile(path)?.isFile == true

    override fun pagesExist(pagePathsJson: String): Boolean {
        val paths = pagePaths(pagePathsJson)
        return paths.isNotEmpty() && paths.all { containedFile(it)?.isFile == true }
    }

    override fun pagePaths(pagePathsJson: String): List<String> = runCatching {
        Json.decodeFromString<List<String>>(pagePathsJson)
    }.getOrDefault(emptyList()).filter { it.isNotBlank() }

    override fun deleteChapterFiles(bookId: Long, chapterId: Long) {
        textFile(bookId, chapterId).delete()
        pageDir(bookId, chapterId).deleteRecursively()
    }

    override fun deleteBookFiles(bookId: Long) {
        File(root, "text/$bookId").deleteRecursively()
        File(root, "manga/$bookId").deleteRecursively()
    }

    override fun encodePagePaths(paths: List<String>): String = Json.encodeToString(paths)

    private fun containedFile(path: String): File? {
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val rootCanonical = root.canonicalFile
        return if (candidate == rootCanonical ||
            candidate.path.startsWith(rootCanonical.path + File.separator)
        ) {
            File(path)
        } else {
            null
        }
    }

    private fun textFile(bookId: Long, chapterId: Long): File =
        File(root, "text/$bookId/$chapterId.html")

    private fun pageDir(bookId: Long, chapterId: Long): File =
        File(root, "manga/$bookId/$chapterId")

    private fun extension(url: String): String {
        val raw = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
            .lowercase()
        return raw.takeIf { it in PAGE_EXTENSIONS } ?: "jpg"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val PAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }
}
