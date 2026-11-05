package dev.mirutime.app.features.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

// Guards the cover-retry wiring: a reloaded list must retry a failed cover even
// when the URL string is unchanged, and a repaired URL must outlive a disposed
// tile. These are source checks, not pixel tests.
class CoverReactivityContractTest {

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
        File("../$relative"),
    ).firstOrNull { it.isFile }?.readText()
        ?: error("source not found: $relative")

    @Test
    fun `cover reload key resets every remembered load state`() {
        val cover = source("src/main/java/dev/mirutime/app/design/components/BookCover.kt")
        assertTrue(cover.contains("reloadKey: Any? = null"))
        for (field in listOf("displayedUrl", "attempt", "failed", "repaired")) {
            assertTrue(
                "$field must be keyed on reloadKey",
                cover.contains("var $field by remember(coverUrl, reloadKey)"),
            )
        }
    }

    @Test
    fun `catalogue cards pass a retry that persists the repaired url`() {
        val catalog = source("src/main/java/dev/mirutime/app/features/finder/ui/SourceCatalogScreen.kt")
        assertTrue(catalog.contains("coverFixes = coverFixes + (book.bookUrl to fresh)"))
        assertTrue(catalog.contains("provider.refreshCatalogCover(sourceId, book.bookUrl)"))
        assertTrue(catalog.contains("reloadKey = reloadTick"))
    }

    @Test
    fun `library cards persist a repaired cover across disposal`() {
        val library = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")
        assertTrue(library.contains("coverFixes = coverFixes + (book.bookUrl to fresh)"))
        assertTrue(library.contains("val fixCover: (LibraryBook) -> LibraryBook"))
        assertTrue(library.contains("book = fixCover(book)"))
    }
}
