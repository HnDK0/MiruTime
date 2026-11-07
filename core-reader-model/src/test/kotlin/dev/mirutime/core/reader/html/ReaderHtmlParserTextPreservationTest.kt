package dev.mirutime.core.reader.html

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic property checks for the parser: known/unknown tags, literal
 * angle prose, declarations and malformed markup must never drop visible text.
 */
class ReaderHtmlParserTextPreservationTest {

    private data class Piece(val text: String, val removed: Boolean)

    // No block tags (p/div/li/h1-3) here: this test targets the inline cleanup,
    // block extraction is covered by ReaderHtmlParserTest.
    private val tags = listOf(
        "<b>", "</b>", "<span class=\"x\">", "</span>", "<details open>", "</details>",
        "<custom data-x=\"1\">", "</custom>", "<a href = \"u\">", "</a>",
        "<!DOCTYPE html>", "<!-- note -->", "<marquee>", "</marquee>", "<br/>", "<br>",
    )
    private val literals = listOf(
        "<System>", "<Alice>", "<cosmic announcement>", "<x = y>", "<custom hidden>",
        "<stronghold>", "< 10", "5 > 3", "a < b > c",
    )
    private val words = listOf(
        "The", "quick", "brown", "fox", " jumps ", "over", "the", "lazy", "dog.",
        "Он", "пришёл", "домой.", "Она", "спала?", "你好", "世界。",
    )

    @Test
    fun `generated markup keeps every visible piece in order`() {
        for (seed in 0 until 300) {
            val random = Random(seed.toLong())
            val pieces = List(random.nextInt(1, 30)) { randomPiece(random) }
            val html = pieces.joinToString("") { it.text }

            val blocks = ReaderHtmlParser.parse(html)

            val expected = visible(pieces)
            if (expected.isEmpty()) {
                assertTrue("seed=$seed no visible text", blocks.isEmpty())
            } else {
                assertEquals("seed=$seed block count", 1, blocks.size)
                val actual = (blocks.single() as ParsedBlock.Paragraph).text
                assertEquals("seed=$seed text", expected, actual)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `malformed fuzz does not crash or leak ascii angles`() {
        val random = Random(99)
        val alphabet = "<>/=\"' abcXYZ012&;[]"
        repeat(300) { seed ->
            val length = random.nextInt(0, 2_000)
            val input = buildString(length) {
                repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
            }

            val text = ReaderHtmlParser.parseDocument(input, "chapter")
                .joinToString("\n") { it.original }

            assertFalse("seed=$seed ascii less-than", text.contains('<'))
            assertFalse("seed=$seed ascii greater-than", text.contains('>'))
        }
    }

    private fun randomPiece(random: Random): Piece {
        val kind = random.nextInt(10)
        return when {
            kind < 4 -> Piece(words[random.nextInt(words.size)], removed = false)
            kind < 7 -> Piece(tags[random.nextInt(tags.size)], removed = true)
            else -> Piece(literals[random.nextInt(literals.size)], removed = false)
        }
    }

    // Independent reference: deliberately does not call the production
    // normalizeAngles or reuse its whitespace regex, so a bug in either is
    // caught instead of being mirrored by the oracle.
    private fun visible(pieces: List<Piece>): String {
        val raw = buildString {
            for (piece in pieces) {
                when {
                    piece.text == "<br/>" || piece.text == "<br>" -> append('\n')
                    piece.removed -> Unit
                    else -> append(piece.text.replace('<', '⟨').replace('>', '⟩'))
                }
            }
        }
        return raw.lines()
            .joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .trim()
    }
}
