package dev.mirutime.core.local

import com.fleeksoft.ksoup.parser.Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Same paragraph separator as the reader: \n\s*\n (not "\n\n") so CRLF text
// (\r\n\r\n) is not glued into a single paragraph.
private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

// src of an <img ...> tag (tag and attribute names are case-insensitive).
private val IMG_SRC_REGEX = Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']*)["'][^>]*>""", RegexOption.IGNORE_CASE)

// Port of NoveLA STRIP_HTML_TAGS (core/utils/extensions.kt): a regex is enough
// here because the body is only reduced to paragraphs afterwards.
private val STRIP_HTML_TAGS = Regex("</?\\w+(?:\\s+\\w+=(?:\"[^\"]*\"|\\S+))*\\s*/?>")

private const val MIME_TYPE = "application/epub+zip"

private val MIME_BYTES = MIME_TYPE.toByteArray(Charsets.UTF_8)

private const val XML_PROLOG = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"

private const val XHTML_NS = "xmlns=\"http://www.w3.org/1999/xhtml\""

// epub:type="toc" needs the epub prefix declared, otherwise nav.xhtml is not
// well-formed XML (unbound prefix) and epubcheck rejects the book.
private const val EPUB_NS = "xmlns:epub=\"http://www.idpf.org/2007/ops\""

private const val NCX_NS = "xmlns=\"http://www.daisy.org/z3986/2005/ncx/\""

// Chapter batch size: bounds peak memory (chapter bodies are not accumulated in
// one list) and keeps SQLite bind-variable counts in loaders far from ~999.
private const val CHAPTER_BATCH = 200

// Keep one oversized image from exhausting the export heap.
private const val MAX_EMBEDDED_IMAGE_BYTES = 32L * 1024 * 1024

/** XML escaping is mandatory — raw content otherwise breaks book parsing. */
private fun xmlEscape(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

/** Image MIME by magic bytes — same signatures as the local parsers. */
private fun detectImageMime(bytes: ByteArray): String = when {
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
        "image/jpeg"
    bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
    bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() -> "image/gif"
    bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() -> "image/webp"
    else -> "image/jpeg"
}

/** File extension matching the MIME of the bytes actually written. */
private fun imageExtension(mime: String): String = when (mime) {
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    else -> "jpg"
}

/** HTML fragment → paragraphs: strip tags, unescape entities, split on blank lines. */
private fun textToParagraphs(html: String): List<String> = html
    .replace(STRIP_HTML_TAGS, "")
    .let { Parser.unescapeEntities(it, false) }
    .split(PARAGRAPH_BREAK)
    .map { it.trim() }
    .filter { it.isNotEmpty() }

/**
 * Writes a book as EPUB3 with maximum reader compatibility: nav.xhtml (the
 * mandatory EPUB3 TOC) and toc.ncx (legacy readers report "ncx file missing"
 * without it).
 *
 * The cover (when bytes are given) lands in images/cover.<ext>, declared with
 * properties="cover-image" plus <meta name="cover">. There is deliberately no
 * cover.xhtml: readers building the chapter list from the spine would show it
 * as an extra "chapter". A non-blank description becomes <dc:description>.
 *
 * Convenience wrapper over [exportStreaming] for callers that already hold all
 * chapters in memory; large books should use [exportStreaming] to avoid OOM.
 */
suspend fun export(
    outputStream: OutputStream,
    bookTitle: String,
    language: String,
    chapters: List<Pair<String, String>>,
    coverBytes: ByteArray? = null,
    description: String? = null,
    creator: String? = null,
    subjects: List<String> = emptyList(),
    imageByteLoader: suspend (src: String) -> ByteArray? = { null },
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
) = exportStreaming(
    outputStream,
    bookTitle,
    language,
    chapters.size,
    { offset, count -> chapters.subList(offset, minOf(offset + count, chapters.size)) },
    coverBytes,
    description,
    creator,
    subjects,
    imageByteLoader,
    onProgress
)

/**
 * Streaming EPUB writer: chapters are written to the archive as they are
 * loaded from [chapterLoader] in batches of [CHAPTER_BATCH], so thousand-chapter
 * books do not hold all text in memory at once.
 *
 * [chapterLoader] is called sequentially with (offset, count) and must return
 * (title, body) pairs for that range; chapters with neither text nor images are
 * skipped. [imageByteLoader] maps an inline image src to bytes (or null — then
 * the image is not embedded and its original src is kept in the XHTML). Zip
 * order: mimetype, container, chapters, then content.opf/nav.xhtml/toc.ncx/cover
 * — chapter file names are known up front (chapter-NNN.xhtml), so navigation is
 * written last.
 *
 * Progress: done is the number of chapters actually written, total the book's
 * total chapter count (known before the export starts).
 */
suspend fun exportStreaming(
    outputStream: OutputStream,
    bookTitle: String,
    language: String,
    totalChapters: Int,
    chapterLoader: suspend (offset: Int, count: Int) -> List<Pair<String, String>>,
    coverBytes: ByteArray? = null,
    description: String? = null,
    creator: String? = null,
    subjects: List<String> = emptyList(),
    imageByteLoader: suspend (src: String) -> ByteArray? = { null },
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
) = withContext(Dispatchers.IO) {
    require(totalChapters > 0) { "Chapter list is empty — an EPUB without content is not allowed" }

    // One UUID per book: used in dc:identifier and in the NCX dtb:uid.
    val uid = "urn:uuid:${UUID.randomUUID()}"

    // dcterms:modified must not carry fractional seconds: Instant.now() would,
    // so format manually in UTC.
    val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    val escapedTitle = xmlEscape(bookTitle)
    val escapedDescription = description?.takeIf { it.isNotBlank() }?.let(::xmlEscape)
    val escapedCreator = creator?.takeIf { it.isNotBlank() }?.let(::xmlEscape)
    val coverMime = coverBytes?.takeIf { it.isNotEmpty() }?.let(::detectImageMime)
    val coverExt = coverMime?.let(::imageExtension)

    ZipOutputStream(outputStream).use { zip ->
        // mimetype must be the FIRST, uncompressed entry (OCF spec). A STORED
        // entry needs size and CRC known up front or closeEntry() throws.
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = MIME_BYTES.size.toLong()
            crc = CRC32().apply { update(MIME_BYTES) }.value
        })
        zip.write(MIME_BYTES)
        zip.closeEntry()

        zip.writeEntry(
            "META-INF/container.xml",
            """$XML_PROLOG
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
        )

        // 1) Chapters go straight into the archive; titles of written chapters
        //    are kept (tens of KB even for thousands) to build the navigation.
        val validTitles = mutableListOf<String>()
        // Inline chapter images: file names and MIME for the manifest.
        val chapterImages = mutableListOf<Pair<String, String>>()
        var offset = 0
        while (offset < totalChapters) {
            // Cancellation is checked between batches so a cancelled export
            // does not leave a half-written archive.
            coroutineContext.ensureActive()

            val batch = chapterLoader(offset, minOf(CHAPTER_BATCH, totalChapters - offset))
            offset += CHAPTER_BATCH
            batch.forEach { (title, body) ->
                // Unescape HTML entities once: otherwise &amp; from the source
                // becomes &amp;amp; after xmlEscape and readers show "&amp;".
                val trimmedTitle = Parser.unescapeEntities(title.trim(), false)
                val trimmedBody = body.trim()
                if (trimmedBody.isEmpty()) return@forEach

                // Image srcs of the body BEFORE tag stripping: image-only
                // chapters produce no text after stripping but must be kept.
                val imgSrcList = IMG_SRC_REGEX.findAll(trimmedBody).map { it.groupValues[1] }.toList()
                val paragraphs = textToParagraphs(trimmedBody)
                if (paragraphs.isEmpty() && imgSrcList.isEmpty()) return@forEach
                val name = chapterFileName(validTitles.size)
                val imageBaseName = name.removeSuffix(".xhtml")
                var imageIndex = 0
                // Text and images in source order: text between <img> tags is
                // split into paragraphs; images are embedded when bytes are
                // available and rewritten to a local src.
                val bodyXml = buildString {
                    var lastEnd = 0
                    IMG_SRC_REGEX.findAll(trimmedBody).forEach { m ->
                        val textPart = trimmedBody.substring(lastEnd, m.range.first)
                        textToParagraphs(textPart).forEach { p ->
                            if (isNotEmpty()) appendLine()
                            append("""    <p>${xmlEscape(p)}</p>""")
                        }
                        appendLine()
                        // Decode HTML entities in the raw src (&amp; in URLs is
                        // normal in scraped HTML): downloaders must see a real
                        // &, and the fallback must not double-escape.
                        val src = Parser.unescapeEntities(m.groupValues[1], false)
                        val bytes = imageByteLoader(src)
                            ?.takeIf { it.size <= MAX_EMBEDDED_IMAGE_BYTES }
                        if (bytes != null && bytes.isNotEmpty()) {
                            val mime = detectImageMime(bytes)
                            val fileName = "images/$imageBaseName-img-$imageIndex.${imageExtension(mime)}"
                            zip.writeEntryBytes("OEBPS/$fileName", bytes)
                            chapterImages += fileName to mime
                            append("""    <img src="$fileName" alt=""/>""")
                        } else {
                            // No bytes — keep the original src untouched.
                            append("""    <img src="${xmlEscape(src)}" alt=""/>""")
                        }
                        imageIndex++
                        lastEnd = m.range.last + 1
                    }
                    textToParagraphs(trimmedBody.substring(lastEnd)).forEach { p ->
                        if (isNotEmpty()) appendLine()
                        append("""    <p>${xmlEscape(p)}</p>""")
                    }
                }
                zip.writeEntry(
                    "OEBPS/$name",
                    """$XML_PROLOG
<html $XHTML_NS>
  <head><title>${xmlEscape(trimmedTitle)}</title></head>
  <body>
    <h2>${xmlEscape(trimmedTitle)}</h2>
$bodyXml
  </body>
</html>"""
                )
                validTitles += trimmedTitle
                onProgress(validTitles.size, totalChapters)
            }
        }
        if (validTitles.isEmpty()) {
            throw IllegalArgumentException("All chapters are empty — nothing to write")
        }

        // 2) Service files, built from the titles of written chapters.
        val coverImageItem = if (coverMime != null && coverExt != null) {
            """    <item id="cover-image" href="images/cover.$coverExt" media-type="$coverMime" properties="cover-image"/>"""
        } else null
        val manifest = buildString {
            append("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            appendLine()
            append("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
            coverImageItem?.let {
                appendLine()
                append(it)
            }
            validTitles.forEachIndexed { i, _ ->
                val name = chapterFileName(i)
                appendLine()
                append("""    <item id="$name" href="$name" media-type="application/xhtml+xml"/>""")
            }
            chapterImages.forEachIndexed { i, (fileName, mime) ->
                appendLine()
                append("""    <item id="img-$i" href="$fileName" media-type="$mime"/>""")
            }
        }
        val spine = buildString {
            validTitles.forEachIndexed { i, _ ->
                val name = chapterFileName(i)
                if (isNotEmpty()) appendLine()
                append("""    <itemref idref="$name"/>""")
            }
        }
        val metadataDescription = escapedDescription?.let {
            "\n    <dc:description>$it</dc:description>"
        } ?: ""
        val metadataCreator = escapedCreator?.let {
            "\n    <dc:creator>$it</dc:creator>"
        } ?: ""
        val metadataSubjects = subjects
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("") { "\n    <dc:subject>${xmlEscape(it)}</dc:subject>" }
        val coverMeta = if (coverImageItem != null) "\n    <meta name=\"cover\" content=\"cover-image\"/>" else ""
        // Accessibility metadata (schema.org): epubcheck requires accessMode,
        // accessibilityFeature and accessibilityHazard.
        val accessibilityMeta = listOf(
            """<meta property="schema:accessMode">textual</meta>""",
            """<meta property="schema:accessMode">visual</meta>""",
            """<meta property="schema:accessModeSufficient">textual;visual</meta>""",
            """<meta property="schema:accessibilityFeature">structuralNavigation</meta>""",
            """<meta property="schema:accessibilityFeature">tableOfContents</meta>""",
            """<meta property="schema:accessibilityHazard">none</meta>""",
            """<meta property="schema:accessibilitySummary">Textual content with images and structural navigation via the table of contents.</meta>"""
        ).joinToString(separator = "\n    ", prefix = "\n    ")
        zip.writeEntry(
            "OEBPS/content.opf",
            """$XML_PROLOG
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$escapedTitle</dc:title>
    <dc:language>${xmlEscape(language.ifBlank { "und" })}</dc:language>
    <dc:identifier id="book-id">$uid</dc:identifier>$metadataCreator$metadataSubjects$metadataDescription
    <meta property="dcterms:modified">$modified</meta>$coverMeta$accessibilityMeta
  </metadata>
  <manifest>
$manifest
  </manifest>
  <spine toc="ncx">
$spine
  </spine>
</package>"""
        )

        val navItems = buildString {
            validTitles.forEachIndexed { i, title ->
                if (i > 0) appendLine()
                append("""      <li><a href="${chapterFileName(i)}">${xmlEscape(title)}</a></li>""")
            }
        }
        zip.writeEntry(
            "OEBPS/nav.xhtml",
            """$XML_PROLOG
<html $XHTML_NS $EPUB_NS>
  <head><title>$escapedTitle</title></head>
  <body>
    <nav epub:type="toc" id="toc" role="doc-toc">
      <ol>
$navItems
      </ol>
    </nav>
  </body>
</html>"""
        )

        // toc.ncx — legacy navigation: without it old readers report
        // "ncx file missing" and refuse to open the book.
        val navMap = buildString {
            validTitles.forEachIndexed { i, title ->
                if (i > 0) appendLine()
                append(
                    """    <navPoint id="navPoint-${i + 1}" playOrder="${i + 1}">
      <navLabel><text>${xmlEscape(title)}</text></navLabel>
      <content src="${chapterFileName(i)}"/>
    </navPoint>"""
                )
            }
        }
        zip.writeEntry(
            "OEBPS/toc.ncx",
            """$XML_PROLOG
<ncx $NCX_NS version="2005-1">
  <head>
    <meta name="dtb:uid" content="$uid"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>$escapedTitle</text></docTitle>
  <navMap>
$navMap
  </navMap>
</ncx>"""
        )

        // Cover: binary file only; it is referenced through
        // properties="cover-image" in the manifest (EPUB3 standard).
        if (coverBytes != null && coverMime != null && coverExt != null) {
            zip.writeEntryBytes("OEBPS/images/cover.$coverExt", coverBytes)
        }
    }
}

/** Chapter file name, zero-padded to three digits: chapter-001.xhtml. */
private fun chapterFileName(index: Int): String =
    "chapter-${(index + 1).toString().padStart(3, '0')}.xhtml"

private fun ZipOutputStream.writeEntry(name: String, content: String) {
    putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}

/** Binary entry writer (cover and inline images), counterpart of [writeEntry]. */
private fun ZipOutputStream.writeEntryBytes(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
    write(bytes)
    closeEntry()
}
