package dev.mirutime.app.features.translation.data

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.text.RegexRule
import dev.mirutime.core.text.RegexRuleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTranslationPlannerTest {

    private val bookUrl = "https://site/book"
    private val chapterUrl = "https://site/book/ch/2"

    private fun mode(
        source: String = "en",
        target: String = "ru",
        provider: String = "GEMINI",
    ) = TranslationMode(source = source, target = target, provider = provider)

    private fun document(vararg kinds: Pair<BlockKind, String>) = ReaderDocument(
        bookUrl = bookUrl,
        chapterUrl = chapterUrl,
        chapterIndex = 2,
        title = "Two",
        blocks = kinds.mapIndexed { index, (kind, text) ->
            ReaderBlock(
                id = BlockId(chapterUrl, index),
                kind = kind,
                original = text,
            )
        },
    )

    @Test
    fun `unconfigured mode yields no config`() {
        assertNull(ChapterTranslationPlanner.config(bookUrl, null))
        assertNull(
            ChapterTranslationPlanner.config(
                bookUrl,
                mode(source = "ru", target = "ru"),
            ),
        )
        assertNull(
            ChapterTranslationPlanner.config(
                bookUrl,
                mode(provider = "NOT_A_PROVIDER"),
            ),
        )
    }

    @Test
    fun `configured pair and provider yield a config`() {
        val config = ChapterTranslationPlanner.config(bookUrl, mode())

        assertEquals("en", config?.mode?.source)
        assertEquals("ru", config?.mode?.target)
    }

    @Test
    fun `request keeps only heading and paragraph text`() {
        val doc = document(
            BlockKind.Heading to "  Heading  ",
            BlockKind.Image to "",
            BlockKind.Paragraph to "Body",
            BlockKind.Divider to "",
            BlockKind.Paragraph to "   ",
        )

        val request = ChapterTranslationPlanner.request(
            doc,
            ChapterTranslationConfig(mode = mode(), prompt = null),
        )

        assertEquals(listOf("0", "2"), request?.units?.map { it.id })
        assertEquals(listOf("Heading", "Body"), request?.units?.map { it.text })
        assertEquals(chapterUrl, request?.chapterUrl)
        assertEquals(
            "8464609e86f20b70ffb79270c6d86bfe883ab356f8a6716880180309a22b8f78",
            request?.revision?.sourceHash,
        )
    }

    @Test
    fun `source hash ignores process-local enum identity`() {
        val doc = document(BlockKind.Heading to "Heading", BlockKind.Paragraph to "Body")

        val first = ChapterTranslationPlanner.request(
            doc,
            ChapterTranslationConfig(mode = mode(), prompt = null),
        )
        val second = ChapterTranslationPlanner.request(
            doc.copy(blocks = doc.blocks.toList()),
            ChapterTranslationConfig(mode = mode(), prompt = null),
        )

        assertEquals(first?.revision?.sourceHash, second?.revision?.sourceHash)
        // The old key was List.hashCode(); enum identity made it differ per
        // process and the disk cache never hit.
        assertTrue(first?.revision?.sourceHash != doc.blocks.hashCode().toString())
    }

    @Test
    fun `empty chapter yields no request`() {
        val request = ChapterTranslationPlanner.request(
            document(BlockKind.Image to "", BlockKind.Divider to ""),
            ChapterTranslationConfig(mode = mode(), prompt = null),
        )

        assertNull(request)
    }

    @Test
    fun `html and document requests agree on units and chapter identity`() {
        val html = "<h2>Heading</h2><p>Body</p><p><img src=\"a.png\"/></p>"
        val fromHtml = ChapterTranslationPlanner.requestFromHtml(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            html = html,
            config = ChapterTranslationConfig(mode = mode(), prompt = null),
        )
        val fromDocument = ChapterTranslationPlanner.request(
            document(BlockKind.Heading to "Heading", BlockKind.Paragraph to "Body"),
            ChapterTranslationConfig(mode = mode(), prompt = null),
        )

        assertEquals(fromDocument?.units, fromHtml?.units)
        assertEquals(bookUrl, fromHtml?.bookUrl)
        assertTrue(fromHtml?.revision?.sourceHash?.isNotBlank() == true)
    }

    @Test
    fun `literal angle brackets reach units as typographic brackets`() {
        val html = "<p>You have &lt; 10 HP. &lt;System&gt; Level up!</p>"

        val request = ChapterTranslationPlanner.requestFromHtml(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            html = html,
            config = ChapterTranslationConfig(mode = mode(), prompt = null),
        )

        assertNotNull(request)
        assertEquals(
            listOf("You have ⟨ 10 HP. ⟨System⟩ Level up!"),
            request!!.units.map { it.text },
        )
    }

    @Test
    fun `reader and html requests share the cache key for the same chapter`() {
        val html = "<h2>Heading</h2><p>Body</p>"
        val fromHtml = ChapterTranslationPlanner.requestFromHtml(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            html = html,
            config = ChapterTranslationConfig(mode = mode(), prompt = null),
        )
        val fromDocument = ChapterTranslationPlanner.request(
            document(BlockKind.Heading to "Heading", BlockKind.Paragraph to "Body"),
            ChapterTranslationConfig(mode = mode(), prompt = null),
        )

        assertEquals(fromDocument?.revision?.sourceHash, fromHtml?.revision?.sourceHash)
    }

    @Test
    fun `html request uses the same cleaned blocks as the reader`() {
        AppSettings.novelRegexRules[bookUrl] = listOf(
            RegexRule(
                id = 1,
                pattern = "foo",
                replacement = "baz",
                description = "clean",
                enabled = true,
                wholeWords = false,
            ),
            RegexRule(
                id = 2,
                pattern = "bar",
                replacement = "NO",
                description = "disabled",
                enabled = false,
                wholeWords = false,
            ),
        )
        try {
            val request = ChapterTranslationPlanner.requestFromHtml(
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                html = "<p>foo bar</p>",
                config = ChapterTranslationConfig(mode = mode(), prompt = null),
            )

            assertEquals(listOf("baz bar"), request?.units?.map { it.text })
        } finally {
            AppSettings.novelRegexRules.remove(bookUrl)
        }
    }

    @Test
    fun `only original rules change the translator input and source hash`() {
        val config = ChapterTranslationConfig(mode = mode(), prompt = null)
        val baseline = ChapterTranslationPlanner.requestFromHtml(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            html = "<p>foo bar</p>",
            config = config,
        )
        try {
            AppSettings.novelRegexRules[bookUrl] = listOf(
                RegexRule(
                    id = 1,
                    pattern = "foo",
                    replacement = "",
                    description = "translation only",
                    enabled = true,
                    wholeWords = false,
                    scope = RegexRuleScope.Translation,
                ),
            )
            val translationOnly = ChapterTranslationPlanner.requestFromHtml(
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                html = "<p>foo bar</p>",
                config = config,
            )
            assertEquals(listOf("foo bar"), translationOnly?.units?.map { it.text })
            assertEquals(baseline?.revision?.sourceHash, translationOnly?.revision?.sourceHash)

            AppSettings.novelRegexRules[bookUrl] = listOf(
                RegexRule(
                    id = 1,
                    pattern = "foo",
                    replacement = "",
                    description = "original",
                    enabled = true,
                    wholeWords = false,
                    scope = RegexRuleScope.Original,
                ),
            )
            val original = ChapterTranslationPlanner.requestFromHtml(
                bookUrl = bookUrl,
                chapterUrl = chapterUrl,
                html = "<p>foo bar</p>",
                config = config,
            )
            assertEquals(listOf("bar"), original?.units?.map { it.text })
            assertNotEquals(baseline?.revision?.sourceHash, original?.revision?.sourceHash)
        } finally {
            AppSettings.novelRegexRules.remove(bookUrl)
        }
    }
}
