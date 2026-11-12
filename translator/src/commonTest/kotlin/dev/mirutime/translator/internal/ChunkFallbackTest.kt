package dev.mirutime.translator.internal

import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import dev.mirutime.translator.TranslationUnit
import dev.mirutime.translator.provider.PA_PARAGRAPH_END
import dev.mirutime.translator.provider.stripParagraphMarkup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// One chunk with a mismatched line count must not fail the whole batch and
// must not explode into one request per unit: the chunk is bisected, so only
// the damaged units cost extra requests (CJK descriptions/chapter lists).
class ChunkFallbackTest {
    private val provider = ProviderId.GOOGLE_FREE
    private val newline = Regex("\\r?\\n")
    private val units = listOf(
        TranslationUnit("a", "one"),
        TranslationUnit("b", "two"),
    )
    private val chunk = TextChunk(listOf(0, 1), "one\ntwo")

    @Test
    fun `a matching chunk maps directly`() = runTest {
        val result = applyChunkWithFallback(
            chunk = chunk,
            units = units,
            provider = provider,
            separatorRegex = newline,
            translateChunk = { "eins\nzwei" },
        )
        assertEquals(mapOf("a" to "eins", "b" to "zwei"), result)
    }

    @Test
    fun `a merged chunk is recovered per unit`() = runTest {
        var batchCalls = 0
        val result = applyChunkWithFallback(
            chunk = chunk,
            units = units,
            provider = provider,
            separatorRegex = newline,
            translateChunk = { single ->
                if (single.indices.size == 1) {
                    "${single.text}!"
                } else {
                    batchCalls++
                    "one two" // one line for two units -> mismatch
                }
            },
        )
        assertEquals(mapOf("a" to "one!", "b" to "two!"), result)
        assertEquals(1, batchCalls)
    }

    @Test
    fun `a truncated batch failure falls back per unit`() = runTest {
        val result = applyChunkWithFallback(
            chunk = chunk,
            units = units,
            provider = provider,
            separatorRegex = newline,
            translateChunk = { single ->
                if (single.indices.size == 1) {
                    "${single.text}!"
                } else {
                    throw TranslationFailure(TranslationError.Truncated(provider, 2, 1))
                }
            },
        )
        assertEquals(mapOf("a" to "one!", "b" to "two!"), result)
    }

    @Test
    fun `a real failure still propagates`() = runTest {
        assertFailsWith<TranslationFailure> {
            applyChunkWithFallback(
                chunk = chunk,
                units = units,
                provider = provider,
                separatorRegex = newline,
                translateChunk = { throw TranslationFailure(TranslationError.Network(provider)) },
            )
        }
    }

    @Test
    fun `one damaged unit is isolated by bisection, not per-unit requests`() = runTest {
        val eight = (0 until 8).map { TranslationUnit("u$it", "text $it") }
        val bigChunk = TextChunk(eight.indices.toList(), eight.joinToString("\n") { it.text })
        var calls = 0
        val result = applyChunkWithFallback(
            chunk = bigChunk,
            units = eight,
            provider = provider,
            separatorRegex = newline,
            translateChunk = { request ->
                calls++
                if (request.indices.size > 1 && 6 in request.indices) {
                    "merged into one line"
                } else {
                    request.text.uppercase()
                }
            },
        )

        assertEquals(eight.associate { it.id to it.text.uppercase() }, result)
        // Full chunk + the path to the damaged unit: far below one request per
        // unit (8 here, hundreds for a real metadata batch).
        assertTrue(calls < eight.size, "expected fewer than 8 requests, was $calls")
    }

    @Test
    fun `paragraph blocks map one translated block per unit`() = runTest {
        val chunk = TextChunk(listOf(0, 1), "one</p><p>two", "</p><p>")
        val result = applyChunkWithFallback(
            chunk = chunk,
            units = units,
            provider = provider,
            separatorRegex = PA_PARAGRAPH_END,
            transformLine = ::stripParagraphMarkup,
            translateChunk = { "eins</p><p> zwei" },
        )

        assertEquals(mapOf("a" to "eins", "b" to "zwei"), result)
    }

    @Test
    fun `mapped lines keep the old trim`() = runTest {
        val result = applyChunkWithFallback(
            chunk = chunk,
            units = units,
            provider = provider,
            separatorRegex = newline,
            translateChunk = { " eins \n zwei " },
        )

        assertEquals(mapOf("a" to "eins", "b" to "zwei"), result)
    }

    @Test
    fun `sequential spacing also covers recovery requests`() = runTest {
        val sentAt = mutableListOf<Long>()
        val translate = throttledTranslator(concurrency = 1, gapMs = 1_000) {
            sentAt += testScheduler.currentTime
            ""
        }

        translate(chunk)
        translate(chunk)
        translate(chunk)

        // The gap lives in the translator, so bisection children (which go
        // through the same lambda) cannot bypass it.
        assertEquals(listOf(0L, 1_000L, 2_000L), sentAt)
    }

    @Test
    fun `unit with internal newline recovers when bisected to single unit`() = runTest {
        val multiLineUnits = listOf(
            TranslationUnit("a", "line one\nline two"),
            TranslationUnit("b", "line three"),
        )
        val multiLineChunk = TextChunk(listOf(0, 1), "line one\nline two\nline three")
        val result = applyChunkWithFallback(
            chunk = multiLineChunk,
            units = multiLineUnits,
            provider = provider,
            separatorRegex = newline,
            translateChunk = { req -> req.text.uppercase() },
        )

        assertEquals(
            mapOf(
                "a" to "LINE ONE\nLINE TWO",
                "b" to "LINE THREE",
            ),
            result,
        )
    }
}
