package dev.mirutime.app.features.book.ui

import dev.mirutime.app.features.book.domain.BookDownloadOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpdsSelectedOptionTest {

    private fun option(
        key: String,
        format: String = key.uppercase(),
        isDefault: Boolean = false,
        unavailableReason: String? = null,
    ) = BookDownloadOption(
        key = key,
        format = format,
        url = "https://catalog.test/$key",
        isDefault = isDefault,
        unavailableReason = unavailableReason,
    )

    @Test
    fun `no options leaves the choice to the provider`() {
        assertNull(opdsSelectedOption(emptyList(), null))
        assertNull(opdsSelectedOption(emptyList(), "epub"))
    }

    @Test
    fun `the feed default is preselected before the user picks`() {
        val epub = option("epub", isDefault = true)
        val fb2 = option("fb2")

        assertEquals(epub, opdsSelectedOption(listOf(fb2, epub), null))
    }

    @Test
    fun `a user pick beats the default`() {
        val epub = option("epub", isDefault = true)
        val fb2 = option("fb2")

        assertEquals(fb2, opdsSelectedOption(listOf(epub, fb2), "fb2"))
    }

    @Test
    fun `a stale pick that is no longer downloadable falls back to the default`() {
        val epub = option("epub", isDefault = true)
        val mobi = option("mobi", unavailableReason = "Buy")

        assertEquals(epub, opdsSelectedOption(listOf(epub, mobi), "mobi"))
    }

    @Test
    fun `an unavailable default yields the first downloadable option`() {
        val mobi = option("mobi", isDefault = true, unavailableReason = "Sample")
        val fb2 = option("fb2")
        val cbz = option("cbz")

        assertEquals(fb2, opdsSelectedOption(listOf(mobi, fb2, cbz), null))
    }

    @Test
    fun `all unavailable yields nothing instead of a bogus key`() {
        val options = listOf(
            option("mobi", isDefault = true, unavailableReason = "Sample"),
            option("pdf", unavailableReason = "UnsupportedType"),
        )

        assertNull(opdsSelectedOption(options, null))
        assertNull(opdsSelectedOption(options, "pdf"))
    }
}
