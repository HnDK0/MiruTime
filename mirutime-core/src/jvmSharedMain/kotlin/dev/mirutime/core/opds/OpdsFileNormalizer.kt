package dev.mirutime.core.opds

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * `.epub.zip` exists as "an epub packed one more time" (download sites and a few
 * catalogs). EPUB is already a zip, so the only sane handling is unwrapping the
 * outer layer once, before the file reaches EpubFileReader.
 */
object OpdsFileNormalizer {
    private const val CONTAINER = "META-INF/container.xml"
    private const val MAX_INNER_BYTES = 128L * 1024 * 1024

    /** Rewrites [file] with the inner epub; false when it is already an epub. */
    fun unwrapEpubZip(file: File): Boolean {
        val inner = extractInnerEpub(file) ?: return false
        val temp = File(file.parentFile, "${file.name}.unwrapped")
        try {
            FileOutputStream(temp).use { out ->
                out.write(inner)
                out.flush()
            }
            if (!temp.renameTo(file)) file.writeBytes(inner)
        } finally {
            temp.delete()
        }
        return true
    }

    private fun extractInnerEpub(file: File): ByteArray? = try {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            if (entries.size != 1 || zip.getEntry(CONTAINER) != null) return null
            zip.getInputStream(entries.single()).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_INNER_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }
    } catch (_: Exception) {
        null
    }
}
