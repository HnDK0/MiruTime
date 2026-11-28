package dev.mirutime.core.local

import java.util.Base64

// FB2 XML is often hard-wrapped at 80 columns; a line break inside a <p> is
// source formatting, not a run of spaces or a <br>.
private val WhitespaceRun = Regex("\\s+")

// ── FB2 parser (port from NoveLA tooling/epub_parser Fb2Parser) ─────────────
// Replacements vs the ethalon: javax DOM single pass instead of two streaming
// XmlPullParser passes (ceiling: whole-tree DOM; the NoveLA streaming was a
// 256MB-heap Android constraint — revisit if huge FB2 imports OOM); cover and
// inline image binaries are base64-decoded, unresolved IDs keep their "[id]"
// placeholder; zip-wrapped .fb2.zip supported via unwrapSingleEntryZip.

internal fun parseFb2(raw: ByteArray): LocalBook {
    val bytes = unwrapSingleEntryZip(raw)
    val doc = parseXmlDocument(bytes) ?: throw IllegalStateException("FB2 parse failed")

    val title = doc.selectFirstTag("book-title")?.textContent?.trim()
        .orEmpty().ifEmpty { "Unknown Title" }
    val authorEl = doc.selectFirstTag("author")
    val author = authorEl?.let {
        listOfNotNull(
            it.selectFirstChildTag("first-name")?.textContent?.trim(),
            it.selectFirstChildTag("last-name")?.textContent?.trim(),
        ).joinToString(" ").ifEmpty { null }
    }
    val description = doc.selectFirstTag("annotation")?.let { blockText(it) }

    val binaries = doc.selectTag("binary").associateBy { it.attr("id").orEmpty() }
    val chapters = doc.selectTag("section").mapIndexedNotNull { index, section ->
        val parsed = sectionBodyText(section, binaries)
        if (parsed.body.isEmpty() && parsed.images.isEmpty()) null else {
            val sectionTitle = section.selectFirstChildTag("title")
                ?.let { blockText(it) }?.replace(WhitespaceRun, " ")?.trim().orEmpty()
            LocalChapter(
                url = "fb2_$index",
                title = sectionTitle.ifEmpty { "Chapter ${index + 1}" },
                body = parsed.body,
                images = parsed.images,
            )
        }
    }

    val cover = extractFb2Cover(doc, binaries)
    return LocalBook(
        title = title,
        author = author,
        description = description,
        chapters = chapters,
        coverBytes = cover?.bytes,
        coverMediaType = cover?.mediaType,
    )
}

// <coverpage><image l:href="#cover.jpg"/></coverpage> points at a <binary>
// whose text is base64 (possibly wrapped across lines).
private fun extractFb2Cover(doc: XmlElement, binaries: Map<String, XmlElement>): CoverData? {
    val href = doc.selectFirstTag("coverpage")
        ?.selectFirstTag("image")
        ?.let { it.attr("l:href") ?: it.attr("xlink:href") ?: it.attr("href") }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val id = href.removePrefix("#")
    if (id.isEmpty()) return null
    val binary = binaries[id] ?: return null
    val image = decodeFb2Binary(binary) ?: return null
    return CoverData(image.bytes, image.mediaType)
}

// Прямой текст structural-тегов — whitespace-мусор между элементами; вложенные
// <section> уходят в собственные главы (NoveLA stack semantics: детский текст
// не копится в родителе). Direct <image> entries become positioned markers.
private fun sectionBodyText(section: XmlElement, binaries: Map<String, XmlElement>): ParsedText {
    val out = InlineImagesBuilder()
    for (child in section.children) {
        when (child.name) {
            "title", "section" -> {}
            "empty-line" -> out.append("\n\n")
            "br" -> out.append('\n')
            "p", "subtitle", "cite", "epigraph", "table" -> {
                val text = if (child.name == "p" || child.name == "subtitle") {
                    child.textContent.replace(WhitespaceRun, " ").trim()
                } else {
                    blockText(child)
                }
                if (text.isNotEmpty()) out.append(text)
                child.selectTag("image").forEach { appendFb2Image(it, out, binaries) }
                out.append("\n\n")
            }
            "image" -> appendFb2Image(child, out, binaries)
            else -> {
                val text = blockText(child)
                if (text.isNotEmpty()) out.append(text).append("\n\n")
            }
        }
    }
    return out.build().trimmed()
}

private fun appendFb2Image(
    node: XmlElement,
    out: InlineImagesBuilder,
    binaries: Map<String, XmlElement>,
) {
    val href = (node.attr("l:href") ?: node.attr("xlink:href") ?: node.attr("href")).orEmpty()
    val id = href.removePrefix("#").trim()
    if (id.isEmpty()) return
    val binary = binaries[id]
    if (binary == null) {
        // Keep the old readable placeholder only when the target is missing.
        out.append("[").append(id).append("]")
        return
    }
    // Broken base64 is skipped: a bad image must not break the chapter.
    out.image(decodeFb2Binary(binary))
}

private fun decodeFb2Binary(binary: XmlElement): LocalImage? {
    val encoded = binary.textContent.filterNot { it.isWhitespace() }
    if (encoded.isEmpty()) return null
    val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return LocalImage(position = 0, bytes = bytes, mediaType = binary.attr("content-type"))
}

// FictionBook elements that hold the text of one line or paragraph.
private val FB2_TEXT_BLOCKS = setOf("p", "v", "subtitle", "text-author", "th", "td")

// Text of a container (cite, epigraph, poem, annotation, title, table...): one line per text block,
// in document order. XmlElement.textContent alone glues the blocks together when the file has no
// whitespace between tags (<v>one</v><v>two</v> reads "onetwo"). Whitespace is collapsed inside each
// line (hard-wrapped source), never across lines.
private fun blockText(node: XmlElement): String {
    val lines = ArrayList<String>()
    collectBlocks(node, lines)
    return lines.joinToString("\n")
}

private fun collectBlocks(node: XmlElement, lines: MutableList<String>) {
    if (node.name in FB2_TEXT_BLOCKS || node.children.isEmpty()) {
        val text = node.textContent.replace(WhitespaceRun, " ").trim()
        if (text.isNotEmpty()) lines.add(text)
        return
    }
    // Text sitting directly in a container is not expected, but it is never dropped.
    val own = node.text.replace(WhitespaceRun, " ").trim()
    if (own.isNotEmpty()) lines.add(own)
    node.children.forEach { collectBlocks(it, lines) }
}
