package dev.mirutime.core.reader.html

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock as DocumentBlock
import dev.mirutime.app.features.reader.document.SentenceSplitter
import java.net.URI

object ReaderHtmlParser {
    private val blockTag = Regex(
        "(?is)<(h[1-3]|p|div|li)\\b[^>]*>(.*?)</\\1>",
    )
    private val anyTag = Regex("(?is)<[^>]+>")
    private val scriptStyle = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>")
    private val brTag = Regex("(?i)<br\\s*/?>")
    private val entity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val imgTag = Regex("(?is)<img\\b[^>]*>")
    private val srcAttr = Regex("(?is)\\bsrc\\s*=\\s*[\"']([^\"']*)[\"']")
    private val altAttr = Regex("(?is)\\balt\\s*=\\s*[\"']([^\"']*)[\"']")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "nbsp" to " ",
    )

    // One malformed EPUB paragraph can contain a whole chapter (a 1.4 MB <p>
    // was enough to OOM Compose TextLayout/semantics). Keep one Text bounded;
    // split at sentence/whitespace boundaries so translation/TTS units stay
    // meaningful and block ids remain stable.
    private const val MAX_BLOCK_CHARS = 4_000

    internal fun splitOversized(kind: String, text: String): List<Pair<String, String>> {
        if (text.length <= MAX_BLOCK_CHARS) return listOf(kind to text)
        val out = mutableListOf<Pair<String, String>>()
        var rest = text
        while (rest.length > MAX_BLOCK_CHARS) {
            val limit = MAX_BLOCK_CHARS
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '…', '\n'), startIndex = limit)
            if (cut < limit / 2) cut = rest.lastIndexOf(' ', startIndex = limit)
            if (cut < limit / 2) cut = limit
            if (cut < rest.length && (rest[cut] == '.' || rest[cut] == '!' || rest[cut] == '?' || rest[cut] == '…' || rest[cut] == '\n')) {
                cut += 1
            }
            val head = rest.substring(0, cut).trim()
            if (head.isNotEmpty()) out += kind to head
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += kind to rest
        return out
    }

    fun parse(html: String): List<ParsedBlock> {
        val cleanHtml = scriptStyle.replace(html, " ")
        val matches = blockTag.findAll(cleanHtml).toList()
        val blocks = if (matches.isEmpty()) {
            val text = clean(cleanHtml)
            if (text.isBlank()) emptyList() else listOf(ParsedBlock.Paragraph(text))
        } else {
            matches.mapNotNull { match ->
                val tag = match.groupValues[1].lowercase()
                val text = clean(match.groupValues[2])
                when {
                    text.isBlank() -> null
                    tag.startsWith("h") -> ParsedBlock.Heading(text)
                    else -> ParsedBlock.Paragraph(text)
                }
            }
        }
        val bounded = blocks.flatMap { block ->
            when (block) {
                is ParsedBlock.Heading -> splitOversized("h", block.text).map { ParsedBlock.Heading(it.second) }
                is ParsedBlock.Paragraph -> splitOversized("p", block.text).map { ParsedBlock.Paragraph(it.second) }
            }
        }
        return bounded
    }

    private data class RawBlock(
        val kind: BlockKind,
        val text: String,
        val imageRef: String? = null,
    )

    fun parseDocument(
        html: String,
        chapterUrl: String,
        transform: (String) -> String = { it },
    ): List<DocumentBlock> {
        val cleanHtml = scriptStyle.replace(html, " ")
        val matches = blockTag.findAll(cleanHtml).toList()
        val raw = if (matches.isEmpty()) {
            rawBlocks("p", cleanHtml, chapterUrl)
        } else {
            matches.flatMap { match ->
                val tag = match.groupValues[1].lowercase()
                rawBlocks(tag, match.groupValues[2], chapterUrl)
            }
        }
        val bounded = raw.flatMap { block ->
            if (block.imageRef != null) listOf(block)
            else splitOversized(
                if (block.kind == BlockKind.Heading) "h" else "p",
                block.text,
            ).map { (kind, text) ->
                block.copy(
                    kind = if (kind == "h") BlockKind.Heading else block.kind,
                    text = text,
                )
            }
        }
        return bounded.mapIndexed { index, block ->
            // User regex rules run before spans are computed so offsets always
            // describe the text that is actually rendered.
            val text = transform(block.text)
            DocumentBlock(
                id = BlockId(chapterUrl, index),
                kind = block.kind,
                original = text,
                imageRef = block.imageRef,
                sentences = if (block.kind == BlockKind.Image) emptyList() else SentenceSplitter.spans(text),
            )
        }
    }

    /** Splits one block's HTML into text and image blocks, preserving order. */
    private fun rawBlocks(tag: String, content: String, chapterUrl: String): List<RawBlock> {
        val images = imgTag.findAll(content).toList()
        if (images.isEmpty()) {
            val text = clean(content)
            return if (text.isBlank()) emptyList() else listOf(
                RawBlock(if (tag.startsWith("h")) BlockKind.Heading else BlockKind.Paragraph, text),
            )
        }
        val result = mutableListOf<RawBlock>()
        var cursor = 0
        val kind = if (tag.startsWith("h")) BlockKind.Heading else BlockKind.Paragraph
        images.forEach { image ->
            val before = clean(content.substring(cursor, image.range.first))
            if (before.isNotBlank()) result += RawBlock(kind, before)
            val tagText = image.value
            val src = srcAttr.find(tagText)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?.trim()
                .orEmpty()
            if (src.isNotEmpty()) {
                val alt = altAttr.find(tagText)?.groupValues?.get(1)?.trim().orEmpty()
                result += RawBlock(BlockKind.Image, alt, resolveImageUrl(src, chapterUrl))
            }
            cursor = image.range.last + 1
        }
        val after = clean(content.substring(cursor))
        if (after.isNotBlank()) result += RawBlock(kind, after)
        return result
    }

    private fun resolveImageUrl(src: String, chapterUrl: String): String {
        if (src.startsWith("http://") || src.startsWith("https://") ||
            src.startsWith("data:") || src.startsWith("file:") || src.startsWith("content:")
        ) return src
        if (src.startsWith("//")) return "https:$src"
        if (!chapterUrl.startsWith("http://") && !chapterUrl.startsWith("https://")) return src
        return runCatching { URI(chapterUrl).resolve(src).toString() }.getOrDefault(src)
    }

    private fun clean(raw: String): String {
        val withBreaks = brTag.replace(raw, "\n")
        val withoutTags = anyTag.replace(withBreaks, "")
        val decoded = decodeEntities(withoutTags)
        return decoded
            .lines()
            .joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .trim()
    }

    private fun decodeEntities(text: String): String = entity.replace(text) { match ->
        val value = match.groupValues[1]
        when {
            value.startsWith("#x", ignoreCase = true) ->
                value.drop(2).toIntOrNull(16)?.toChar()?.toString() ?: match.value
            value.startsWith("#") ->
                value.drop(1).toIntOrNull()?.toChar()?.toString() ?: match.value
            else -> namedEntities[value.lowercase()] ?: match.value
        }
    }
}
