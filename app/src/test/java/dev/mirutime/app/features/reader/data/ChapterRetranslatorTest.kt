package dev.mirutime.app.features.reader.data

import dev.mirutime.app.features.reader.document.BlockId
import dev.mirutime.app.features.reader.document.BlockKind
import dev.mirutime.app.features.reader.document.ReaderBlock
import dev.mirutime.app.features.reader.document.ReaderDocument
import dev.mirutime.app.features.translation.data.ChapterTranslationConfig
import dev.mirutime.app.features.translation.data.TranslationMode
import dev.mirutime.app.features.translation.domain.TranslationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRetranslatorTest {

    private fun document(chapterUrl: String) = ReaderDocument(
        bookUrl = "book",
        chapterUrl = chapterUrl,
        chapterIndex = 1,
        title = "Глава 1",
        blocks = listOf(
            ReaderBlock(
                id = BlockId(chapterUrl, 0),
                kind = BlockKind.Paragraph,
                original = "Hello",
            ),
        ),
    )

    private fun config() = ChapterTranslationConfig(
        mode = TranslationMode(source = "en", target = "ru", provider = "GEMINI"),
        prompt = null,
    )

    @Test
    fun `submits the document currently on screen`() = runTest {
        var submitted: TranslationRequest? = null
        val retranslator = ChapterRetranslator(
            currentDocument = { _, _ -> document("current") },
            config = { config() },
            submit = { request -> submitted = request; true },
        )

        assertTrue(retranslator.retranslate("book", 1))
        assertEquals("Hello", submitted?.units?.single()?.text)
        assertEquals("current", submitted?.chapterUrl)
    }

    @Test
    fun `a failed submit returns false and keeps the chapter retryable`() = runTest {
        val retranslator = ChapterRetranslator(
            currentDocument = { _, _ -> document("current") },
            config = { config() },
            submit = { false },
        )

        assertFalse(retranslator.retranslate("book", 1))
        // The running marker was released: a second tap starts a new attempt.
        assertTrue(
            ChapterRetranslator(
                currentDocument = { _, _ -> document("current") },
                config = { config() },
                submit = { true },
            ).retranslate("book", 1),
        )
    }

    @Test
    fun `a duplicate tap reports started without a second submit`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var submits = 0
        val retranslator = ChapterRetranslator(
            currentDocument = { _, _ -> document("current") },
            config = { config() },
            submit = { submits++; gate.await(); true },
        )

        val job = launch { retranslator.retranslate("book", 1) }
        runCurrent()

        // A second tap reports the running request as started, so the reader
        // does not show a false "could not translate" toast.
        assertTrue(retranslator.retranslate("book", 1))

        gate.complete(Unit)
        job.join()

        assertEquals(1, submits)
    }

    @Test
    fun `missing translation config submits nothing`() = runTest {
        var submits = 0
        val retranslator = ChapterRetranslator(
            currentDocument = { _, _ -> document("current") },
            config = { null },
            submit = { submits++; true },
        )

        assertFalse(retranslator.retranslate("book", 1))
        assertEquals(0, submits)
    }

    @Test
    fun `a translation-only document is never re-translated`() = runTest {
        var submits = 0
        val retranslator = ChapterRetranslator(
            currentDocument = { _, _ -> document("current").copy(translationOnly = true) },
            config = { config() },
            submit = { submits++; true },
        )

        assertFalse(retranslator.retranslate("book", 1))
        assertEquals(0, submits)
    }

    @Test
    fun `a missing document submits nothing`() = runTest {
        var submits = 0
        val retranslator = ChapterRetranslator(
            currentDocument = { _, _ -> null },
            config = { config() },
            submit = { submits++; true },
        )

        assertFalse(retranslator.retranslate("book", 1))
        assertEquals(0, submits)
    }
}
