package dev.mirutime.core.local

// Local book model shared by the epub/fb2/cbz parsers.
// Chapter artwork is extracted as LocalImage entries positioned in the plain
// text body; CBZ pages stay in LocalSource.Pages.
data class LocalBook(
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val chapters: List<LocalChapter>,
    val coverBytes: ByteArray? = null,
    val coverMediaType: String? = null,
)

// Parser-internal cover pair; callers only see LocalBook.coverBytes.
internal data class CoverData(val bytes: ByteArray, val mediaType: String?)

// One inline illustration. `position` is a character offset into
// LocalChapter.body (before marker removal), so callers can interleave text
// and images without a second parse.
data class LocalImage(
    val position: Int,
    val bytes: ByteArray,
    val mediaType: String? = null,
)

// `url` is the chapter key consumed by LocalSource (relative path inside the
// archive for epub, "fb2_N" for fb2, entry name for cbz).
data class LocalChapter(
    val url: String,
    val title: String,
    val body: String,
    val images: List<LocalImage> = emptyList(),
)

internal data class ParsedText(
    val body: String,
    val images: List<LocalImage>,
    val title: String = "",
)

internal fun ParsedText.trimmed(): ParsedText {
    val start = body.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) body.length else it }
    val end = body.indexOfLast { !it.isWhitespace() }.let { if (it < 0) start else it + 1 }
    if (start == 0 && end == body.length) return this
    val trimmedBody = body.substring(start, end)
    val images = images.map { image ->
        image.copy(position = (image.position - start).coerceIn(0, trimmedBody.length))
    }
    return ParsedText(trimmedBody, images, title)
}

// Builds plain text with NUL-delimited image markers, then turns markers into
// LocalImage.position offsets. A single pass avoids a second DOM/zip walk.
internal class InlineImagesBuilder {
    private val raw = StringBuilder()
    private val images = mutableListOf<LocalImage>()

    fun append(text: CharSequence): InlineImagesBuilder {
        raw.append(text)
        return this
    }

    fun append(text: Char): InlineImagesBuilder {
        raw.append(text)
        return this
    }

    fun image(image: LocalImage?): Boolean {
        if (image == null || image.bytes.isEmpty()) return false
        val index = images.size
        images += image.copy(position = 0)
        raw.append(MARKER).append(index).append(MARKER)
        return true
    }

    // Appends an already-positioned chunk (e.g. one XHTML paragraph) while
    // preserving its image offsets relative to the text appended so far.
    fun appendParsed(parsed: ParsedText) {
        var cursor = 0
        for (image in parsed.images) {
            val position = image.position.coerceIn(cursor, parsed.body.length)
            raw.append(parsed.body, cursor, position)
            val index = images.size
            images += image.copy(position = 0)
            raw.append(MARKER).append(index).append(MARKER)
            cursor = position
        }
        raw.append(parsed.body, cursor, parsed.body.length)
    }

    fun build(): ParsedText {
        val body = StringBuilder(raw.length)
        val positioned = ArrayList<LocalImage>(images.size)
        var i = 0
        while (i < raw.length) {
            if (raw[i] != MARKER) {
                body.append(raw[i])
                i++
                continue
            }
            val end = raw.indexOf(MARKER.toString(), i + 1)
            if (end < 0) {
                body.append(raw[i])
                i++
                continue
            }
            val index = raw.substring(i + 1, end).toIntOrNull()
            if (index == null || index !in images.indices) {
                body.append(raw[i])
                i++
                continue
            }
            positioned += images[index].copy(position = body.length)
            i = end + 1
        }
        return ParsedText(body.toString(), positioned)
    }

    private companion object {
        const val MARKER = '\u0000'
    }
}
