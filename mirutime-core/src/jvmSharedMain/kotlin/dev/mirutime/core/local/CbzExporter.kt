package dev.mirutime.core.local

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One page image of an exported archive; [name] is kept on a best-effort basis. */
public data class CbzPage(val name: String, val bytes: ByteArray)

/**
 * Writes a portable CBZ. Pages are stored in the given order, empty pages are
 * skipped, and ComicInfo.xml (when supplied) is the first entry so readers
 * that stop at the metadata still see it.
 */
public fun writeCbz(
    output: OutputStream,
    pages: List<CbzPage>,
    comicInfo: ComicInfoMeta? = null,
    number: Int? = null,
    pageCount: Int? = null,
) {
    writeCbzPages(
        output = output,
        comicInfo = comicInfo,
        number = number,
        pageCount = pageCount ?: pages.size,
        pages = pages.iterator(),
    )
}

/**
 * Streaming variant: page bytes are consumed one at a time, so exporting a
 * large chapter does not hold every page in memory at once. [pageCount] is
 * written to ComicInfo even when the iterator skips an empty page.
 */
public fun writeCbzPages(
    output: OutputStream,
    comicInfo: ComicInfoMeta?,
    number: Int?,
    pageCount: Int,
    pages: Iterator<CbzPage>,
) {
    var written = 0
    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
        if (comicInfo != null) {
            zip.putNextEntry(ZipEntry("ComicInfo.xml"))
            zip.write(buildComicInfoXml(comicInfo, pageCount, number))
            zip.closeEntry()
        }
        while (pages.hasNext()) {
            val page = pages.next()
            if (page.bytes.isEmpty()) continue
            written++
            zip.putNextEntry(ZipEntry(entryName(page.name, written)))
            zip.write(page.bytes)
            zip.closeEntry()
        }
        zip.finish()
    }
    require(written > 0) { "CBZ needs at least one page" }
}

/** ComicRack/ComicInfo metadata understood by Komga, Kavita and Mihon. */
public fun buildComicInfoXml(
    meta: ComicInfoMeta,
    pageCount: Int,
    number: Int? = null,
): ByteArray = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    append("<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
    append("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n")
    meta.title?.takeIf { it.isNotBlank() }?.let { append(tag("Title", it)) }
    meta.series?.takeIf { it.isNotBlank() }?.let { append(tag("Series", it)) }
    meta.volume?.takeIf { it.isNotBlank() }?.let { append(tag("Volume", it)) }
    number?.let { append(tag("Number", it.toString())) }
    append(tag("PageCount", pageCount.toString()))
    meta.summary?.takeIf { it.isNotBlank() }?.let { append(tag("Summary", it)) }
    meta.author?.takeIf { it.isNotBlank() }?.let { append(tag("Writer", it)) }
    meta.artist?.takeIf { it.isNotBlank() }?.let { append(tag("Penciller", it)) }
    meta.genres.takeIf { it.isNotEmpty() }?.let { append(tag("Genre", it.joinToString(", "))) }
    append(tag("Manga", "Yes"))
    append("</ComicInfo>\n")
}.encodeToByteArray()

private fun tag(name: String, value: String): String =
    "  <$name>${escapeXml(value)}</$name>\n"

private fun escapeXml(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(char)
        }
    }
}

// The archive entry never leaves its root: separators and traversal are cut.
private fun entryName(name: String, fallbackIndex: Int): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
    return base.takeIf {
        it.isNotEmpty() && it != "." && it != ".." && it.none { char -> char.code < 0x20 }
    } ?: "page-$fallbackIndex.jpg"
}
