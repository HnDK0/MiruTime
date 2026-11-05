package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.translation.domain.TranslationRequest
import dev.mirutime.core.reader.html.ReaderHtmlParser
import dev.mirutime.core.text.RegexRuleTarget
import dev.mirutime.core.text.applyRulesFor
import dev.mirutime.translator.LanguageCode
import dev.mirutime.translator.PromptSpec
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationRevision
import dev.mirutime.translator.TranslationUnit

/** One resolved translation mode + the prompt the reader/book page uses. */
data class ChapterTranslationConfig(
    val mode: TranslationMode,
    val prompt: PromptSpec?,
)

/**
 * Single place that turns a parsed chapter (or its HTML) into the exact
 * TranslationRequest the reader and the book-list bulk action submit. Keeping
 * the parser, block filter, prompt and revision here means both paths persist
 * compatible translation rows for the same chapter.
 */
object ChapterTranslationPlanner {

    /** Cache identity for persisted reader translations; bump with the parser. */
    const val PIPELINE_VERSION = "reader-1"

    /**
     * Placeholder source hash on a download-intent row: the body is not
     * downloaded yet, so the real block hash cannot be computed. The worker
     * replaces it before the first provider call; it never reaches COMPLETE.
     */
    const val PENDING_SOURCE_HASH = "PENDING"

    /**
     * Stable source identity for the translation cache. [List.hashCode] cannot
     * be used: [ReaderBlock] includes [BlockKind], and enum hash codes are
     * identity-based, so the key changed on every process start and the disk
     * cache never hit.
     */
    fun sourceHash(blocks: List<ReaderBlock>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        blocks.forEach { block ->
            digest.update(block.id.index.toString().toByteArray())
            digest.update(0)
            digest.update(block.kind.name.toByteArray())
            digest.update(0)
            digest.update(block.original.toByteArray())
            digest.update(0)
            digest.update(block.imageRef.orEmpty().toByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Same identity RoomTranslationCache stores; one place, no drift. */
    fun promptHash(prompt: PromptSpec?): String {
        val text = prompt?.text.orEmpty() + "\u0000" + prompt?.version.orEmpty()
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** The active book config, or null when the pair/provider is not usable. */
    fun config(bookUrl: String, mode: TranslationMode?): ChapterTranslationConfig? {
        if (mode == null || !mode.hasPair) return null
        if (ProviderId.from(mode.provider) == null) return null
        if (runCatching { LanguageCode.parse(mode.target) }.getOrNull() == null) return null
        val novelPrompt = AppSettings.novelPrompts.firstOrNull { it.bookUrl == bookUrl }
        val prompt = composePrompt(
            base = AppSettings.activePrompt,
            override = novelPrompt?.prompt.orEmpty(),
            append = novelPrompt?.append == true,
        )
        return ChapterTranslationConfig(mode = mode, prompt = prompt)
    }

    fun request(document: ReaderDocument, config: ChapterTranslationConfig): TranslationRequest? {
        val body = document.bodyBlocks()
        return request(
            bookUrl = document.bookUrl,
            chapterUrl = document.chapterUrl,
            blocks = body,
            config = config,
            // Hash every parsed block, not only the units: requestFromHtml
            // parses the same document and must hit the same cache row.
            sourceHash = sourceHash(document.blocks),
        )
    }

    fun requestFromHtml(
        bookUrl: String,
        chapterUrl: String,
        html: String,
        config: ChapterTranslationConfig,
    ): TranslationRequest? {
        // Match the reader pipeline: cleanup rules are applied before the
        // request is built, so a translated unit is keyed to the same source
        // text the reader shows.
        val rules = AppSettings.effectiveRules(bookUrl)
        val blocks = ReaderHtmlParser.parseDocument(html, chapterUrl)
            .map { block ->
                block.copy(
                    original = applyRulesFor(rules, block.original, RegexRuleTarget.Original),
                )
            }
        return request(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            blocks = blocks,
            config = config,
            sourceHash = sourceHash(blocks),
        )
    }

    fun request(
        bookUrl: String,
        chapterUrl: String,
        blocks: List<ReaderBlock>,
        config: ChapterTranslationConfig,
        sourceHash: String = sourceHash(blocks),
    ): TranslationRequest? {
        val mode = config.mode
        if (!mode.hasPair) return null
        val provider = ProviderId.from(mode.provider) ?: return null
        val target = runCatching { LanguageCode.parse(mode.target) }.getOrNull() ?: return null
        val source = runCatching { LanguageCode.parse(mode.source) }.getOrNull()
        val units = blocks
            .filter { it.kind == BlockKind.Heading || it.kind == BlockKind.Paragraph }
            .mapNotNull { block ->
                block.original.trim().takeIf { it.isNotEmpty() }?.let {
                    TranslationUnit(id = block.id.index.toString(), text = it)
                }
            }
        if (units.isEmpty()) return null
        return TranslationRequest(
            bookId = bookUrl.hashCode().toLong(),
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            units = units,
            source = source,
            target = target,
            provider = provider,
            prompt = config.prompt,
            revision = TranslationRevision(
                sourceHash = sourceHash,
                pipelineVersion = PIPELINE_VERSION,
            ),
        )
    }
}
