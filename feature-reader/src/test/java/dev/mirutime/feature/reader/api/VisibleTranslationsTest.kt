package dev.mirutime.feature.reader.api

import dev.mirutime.feature.reader.api.ReaderChapterTranslation
import dev.mirutime.feature.reader.api.ReaderTranslationMode
import dev.mirutime.feature.reader.api.ReaderTranslationStatus
import dev.mirutime.feature.reader.api.visibleTranslations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTranslationsTest {

    private val mode = ReaderTranslationMode(
        source = "en",
        target = "ru",
        provider = "GEMINI",
        full = false,
    )

    private fun entry(configKey: String) = ReaderChapterTranslation(
        status = ReaderTranslationStatus.Ready,
        translations = mapOf(0 to "text"),
        configKey = configKey,
        bookUrl = "book",
    )

    @Test
    fun `an entry from an older parse is hidden`() {
        val visible = visibleTranslations(
            mode = mode,
            bookUrl = "book",
            translations = mapOf(
                "old" to entry("key-old"),
                "new" to entry("key-new"),
            ),
            expectedConfigKeys = mapOf("old" to "key-new", "new" to "key-new"),
        )

        assertEquals(setOf("new"), visible.keys)
    }

    @Test
    fun `an entry without a known expected key is kept`() {
        val visible = visibleTranslations(
            mode = mode,
            bookUrl = "book",
            translations = mapOf("ch" to entry("key")),
        )

        assertTrue(visible.containsKey("ch"))
    }

    @Test
    fun `no pair hides everything`() {
        val visible = visibleTranslations(
            mode = mode.copy(source = "ru", target = "ru"),
            bookUrl = "book",
            translations = mapOf("ch" to entry("key")),
        )

        assertTrue(visible.isEmpty())
    }
}
