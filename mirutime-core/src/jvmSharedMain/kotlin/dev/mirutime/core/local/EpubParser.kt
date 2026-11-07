package dev.mirutime.core.local

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

// ── EPUB parser (port from NoveLA tooling/epub_parser) ──────────────────────
// Replacements vs the ethalon: java.util.zip over a FileSystem-read ByteArray
// (no temp file), javax DOM for small metadata files (kxml/Xml is
// Android-only), ksoup for chapter XHTML (Jsoup fallback path — ksoup is the
// already-installed multiplatform port). Cover and inline chapter artwork are
// extracted only from entries the book actually references.
private val CHAPTER_EXTENSIONS = setOf("xhtml", "xml", "html")

internal fun parseEpub(bytes: ByteArray): LocalBook {
    val entries = readZipEntries(bytes)

    val container = entries["META-INF/container.xml"]
        ?: throw IllegalStateException("META-INF/container.xml file missing")
    val opfFilePath = parseXmlDocument(container)
        ?.selectFirstTag("rootfile")
        ?.attr("full-path")
        ?.decodedURL
        ?: throw IllegalStateException("Invalid container.xml file")
    val rootPath = opfFilePath.substringBefore('/', "")

    val opf = entries[opfFilePath] ?: throw IllegalStateException(".opf file missing")
    val document = parseXmlDocument(opf) ?: throw IllegalStateException(".opf file failed to parse data")
    val metadata = document.selectFirstTag("metadata")
        ?: throw IllegalStateException(".opf file metadata section missing")
    val manifest = document.selectFirstTag("manifest")
        ?: throw IllegalStateException(".opf file manifest section missing")
    val spine = document.selectFirstTag("spine")
        ?: throw IllegalStateException(".opf file spine section missing")

    val title = metadata.selectFirstChildTag("dc:title")?.textContent ?: "Unknown Title"
    val author = metadata.selectFirstChildTag("dc:creator")?.textContent
    val description = metadata.selectFirstChildTag("dc:description")?.textContent

    val manifestItems = manifest.selectChildTag("item").mapNotNull { item ->
        val id = item.attr("id").orEmpty()
        if (id.isEmpty()) return@mapNotNull null
        id to ManifestRef(
            absPath = item.attr("href").orEmpty().decodedURL.hrefAbsolutePath(rootPath),
            mediaType = item.attr("media-type").orEmpty(),
            properties = item.attr("properties").orEmpty(),
        )
    }.toMap()

    // NCX is optional: EPUB 3 commonly ships only nav.xhtml.
    val ncxPath = manifestItems.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        ?.absPath ?: manifestItems["ncx"]?.absPath
    val tocEntries = if (ncxPath != null) {
        runCatching { parseNcxToc({ entries[it] }, ncxPath, rootPath) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: parseEpub3NavFromEntries(entries, manifestItems, rootPath)
    } else {
        parseEpub3NavFromEntries(entries, manifestItems, rootPath)
    }

    // Spine order (sequential port of the NoveLA batched-parallel loop).
    val chapters = mutableListOf<LocalChapter>()
    var currentToc: EpubNavEntry? = null
    var body = StringBuilder()
    var chapterImages = mutableListOf<LocalImage>()

    for (itemRef in spine.selectChildTag("itemref")) {
        val item = manifestItems[itemRef.attr("idref").orEmpty()] ?: continue
        val extension = item.absPath.substringAfterLast('.')
        if (extension !in CHAPTER_EXTENSIONS) continue
        val spineUrl = item.absPath
        val spineDir = spineUrl.substringBeforeLast('/', "")
        val parsed = xhtmlToContent(entries[spineUrl] ?: ByteArray(0)) { src ->
            resolveEpubImage(src, spineDir, entries, manifestItems)
        }
        val toc = tocEntryFor(spineUrl, tocEntries, parsed.title)

        if (currentToc != null && toc != null && body.isNotEmpty()) {
            chapters.add(LocalChapter(currentToc.link, currentToc.title, body.toString(), chapterImages.toList()))
            body = StringBuilder()
            chapterImages = mutableListOf()
        }
        if (toc != null) currentToc = toc
        if (parsed.body.isNotBlank() || parsed.images.isNotEmpty()) {
            body.append("\n\n")
            val offset = body.length
            body.append(parsed.body)
            parsed.images.forEach { chapterImages += it.copy(position = it.position + offset) }
        }
    }
    if (currentToc != null && body.isNotEmpty()) {
        chapters.add(LocalChapter(currentToc.link, currentToc.title, body.toString(), chapterImages.toList()))
    }

    val cover = extractCover(entries, manifestItems, metadata)
    return LocalBook(
        title = title,
        author = author,
        description = description,
        chapters = chapters,
        coverBytes = cover?.bytes,
        coverMediaType = cover?.mediaType,
    )
}

private fun parseEpub3NavFromEntries(
    entries: Map<String, ByteArray>,
    manifestItems: Map<String, ManifestRef>,
    rootPath: String,
): List<EpubNavEntry> {
    val navPath = manifestItems.values.firstOrNull { item ->
        item.properties.split(Regex("\\s+")).contains("nav")
    }?.absPath ?: return emptyList()
    return parseEpub3Nav({ entries[it] }, navPath, rootPath)
}

// EPUB2 declares the cover through <meta name="cover" content="id">, EPUB3
// through a manifest properties="cover-image". Some files point the meta at a
// cover XHTML page instead; resolve its first <img> without scanning every
// image in the archive.
private fun extractCover(
    entries: Map<String, ByteArray>,
    manifestItems: Map<String, ManifestRef>,
    metadata: XmlElement,
): CoverData? {
    val metaCoverId = metadata.selectTag("meta")
        .firstOrNull { it.attr("name").equals("cover", ignoreCase = true) }
        ?.attr("content")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val ref = metaCoverId?.let(manifestItems::get)
        ?: manifestItems.values.firstOrNull { item ->
            item.properties.split(Regex("\\s+")).any { it == "cover-image" }
        }
        ?: return null
    return readCover(entries, ref)
}

private fun readCover(entries: Map<String, ByteArray>, ref: ManifestRef): CoverData? {
    if (ref.mediaType.startsWith("image/")) {
        val bytes = entries[ref.absPath] ?: return null
        return CoverData(bytes, ref.mediaType)
    }
    if (!ref.mediaType.contains("xhtml")) return null
    val page = entries[ref.absPath] ?: return null
    val src = Ksoup.parse(page.decodeToString(), "")
        .selectFirst("img")
        ?.attr("src")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val base = ref.absPath.substringBeforeLast('/', "")
    val path = src.substringBefore('#').decodedURL.hrefAbsolutePath(base)
    val bytes = entries[path] ?: return null
    val mediaType = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    return CoverData(bytes, mediaType)
}

private data class ManifestRef(
    val absPath: String,
    val mediaType: String,
    val properties: String = "",
)

// Chapter XHTML → structured plain text. Port of NoveLA getNodeStructuredText
// (Jsoup path): paragraphs separated by blank lines, <br> → newline, the
// file's own first heading dropped — the NCX navPoint title wins at merge
// time. Inline <img>/<image> become position markers resolved by the caller.
internal fun xhtmlToText(bytes: ByteArray): String = xhtmlToContent(bytes) { null }.body

internal fun xhtmlToContent(
    bytes: ByteArray,
    resolveImage: (String) -> LocalImage?,
): ParsedText {
    val doc = Ksoup.parse(bytes.decodeToString(), "")
    val body = doc.body() ?: return ParsedText(doc.text(), emptyList(), doc.title())
    val heading = body.selectFirst("h1, h2, h3, h4, h5, h6")
    val chapterTitle = doc.title().trim().ifBlank { heading?.text()?.trim().orEmpty() }
    heading?.remove()
    val out = InlineImagesBuilder()
    appendChildren(body, out, resolveImage)
    val parsed = out.build()
    return ParsedText(parsed.body, parsed.images, chapterTitle)
}

private fun appendChildren(
    node: Node,
    out: InlineImagesBuilder,
    resolveImage: (String) -> LocalImage?,
) {
    for (child in node.childNodes()) {
        when (child.nodeName()) {
            "p" -> {
                val paragraph = InlineImagesBuilder()
                appendParagraph(child, paragraph, resolveImage)
                val parsed = paragraph.build().trimmed()
                if (parsed.body.isNotBlank()) {
                    out.appendParsed(parsed)
                    out.append("\n\n")
                } else if (parsed.images.isNotEmpty()) {
                    // An image-only paragraph contributes no blank text block.
                    out.appendParsed(parsed)
                }
            }
            "br" -> out.append('\n')
            "hr" -> out.append("\n\n")
            "img", "image" -> out.image(resolveImage(imageSource(child)))
            else -> if (child is TextNode) {
                val text = child.text()
                if (text.isNotBlank()) out.append(text).append("\n\n")
            } else {
                appendChildren(child, out, resolveImage)
            }
        }
    }
}

// Paragraph text keeps inline markup flat: nested tags contribute their text.
// Images get their marker at the exact paragraph offset via a nested builder;
// positions are rebased when the paragraph is appended.
private fun appendParagraph(
    node: Node,
    out: InlineImagesBuilder,
    resolveImage: (String) -> LocalImage?,
) {
    for (child in node.childNodes()) {
        when (child.nodeName()) {
            "br" -> out.append('\n')
            "img", "image" -> out.image(resolveImage(imageSource(child)))
            else -> if (child is TextNode) out.append(child.text()) else appendParagraph(child, out, resolveImage)
        }
    }
}

private fun imageSource(node: Node): String =
    node.attr("src").ifBlank { node.attr("xlink:href").ifBlank { node.attr("href") } }.trim()

private fun resolveEpubImage(
    src: String,
    spineDir: String,
    entries: Map<String, ByteArray>,
    manifestItems: Map<String, ManifestRef>,
): LocalImage? {
    if (src.isBlank()) return null
    val path = src.substringBefore('#').substringBefore('?').decodedURL.hrefAbsolutePath(spineDir)
    val bytes = entries[path] ?: return null
    if (bytes.isEmpty()) return null
    val mediaType = manifestItems.values.firstOrNull { it.absPath == path }
        ?.mediaType
        ?.takeIf { it.startsWith("image/") }
        ?: when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }
    return LocalImage(position = 0, bytes = bytes, mediaType = mediaType)
}
