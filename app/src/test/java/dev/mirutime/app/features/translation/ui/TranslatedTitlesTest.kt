package dev.mirutime.app.features.translation.ui

import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.app.features.settings.data.TranslationConfig
import dev.mirutime.app.features.translation.data.TitleSurface
import dev.mirutime.data.library.libraryBookKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranslatedTitlesTest {
    private val oneUrl = "fake://lib/one"
    private val twoUrl = "fake://lib/two"
    private val one = libraryBookKey("lib", oneUrl)
    private val two = libraryBookKey("lib", twoUrl)

    @Before
    @After
    fun cleanup() {
        AppSettings.novelTranslation.remove(one)
        AppSettings.novelTranslation.remove(two)
    }

    private fun config(
        enabled: Boolean = true,
        full: Boolean = true,
        hideLibrary: Boolean = false,
        hideHistory: Boolean = false,
    ) = TranslationConfig(
        enabled = enabled,
        source = "en",
        target = "ru",
        provider = "GOOGLE_PA",
        full = full,
        hideLibrary = hideLibrary,
        hideHistory = hideHistory,
    )

    private fun items(vararg item: TitleItem) = item.toList()

    @Test
    fun `hidden library surface yields no job`() {
        AppSettings.novelTranslation[one] = config(hideLibrary = true)

        assertTrue(titleJobsFor(items(TitleItem(one, "One")), TitleSurface.Library).isEmpty())
    }

    @Test
    fun `hidden history surface keeps the library job`() {
        AppSettings.novelTranslation[one] = config(hideHistory = true)
        val items = items(TitleItem(one, "One"))

        assertTrue(titleJobsFor(items, TitleSurface.History).isEmpty())
        assertEquals(1, titleJobsFor(items, TitleSurface.Library).size)
    }

    @Test
    fun `standard scope yields no metadata job`() {
        AppSettings.novelTranslation[one] = config(full = false)

        assertTrue(titleJobsFor(items(TitleItem(one, "One")), TitleSurface.Library).isEmpty())
    }

    @Test
    fun `books sharing a config produce jobs carrying the same config`() {
        AppSettings.novelTranslation[one] = config()
        AppSettings.novelTranslation[two] = config()

        val jobs = titleJobsFor(
            items(TitleItem(one, "One"), TitleItem(two, "Two")),
            TitleSurface.Library,
        )

        assertEquals(2, jobs.size)
        assertEquals(jobs[0].config, jobs[1].config)
        assertEquals(listOf(one, two), jobs.map { it.bookKey })
    }

    @Test
    fun `blank title is skipped`() {
        AppSettings.novelTranslation[one] = config()

        assertTrue(
            titleJobsFor(items(TitleItem(one, "  ")), TitleSurface.Library).isEmpty(),
        )
    }
}
