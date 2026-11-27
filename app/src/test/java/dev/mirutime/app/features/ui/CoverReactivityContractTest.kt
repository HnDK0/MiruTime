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
        for (field in listOf("displayedUrl", "attempt", "failed", "repaired", "loaded")) {
            assertTrue(
                "$field must be keyed on reloadKey",
                cover.contains("var $field by remember(coverUrl, reloadKey)"),
            )
        }
    }

    @Test
    fun `cover retries the url before asking the source to re-resolve it`() {
        val cover = source("src/main/java/dev/mirutime/app/design/components/BookCover.kt")
        assertTrue(cover.contains("AUTO_RETRY_ATTEMPTS = 2"))
        assertTrue(cover.contains("remote && autoRetries < AUTO_RETRY_ATTEMPTS"))
        assertTrue(cover.contains("onSuccess = { failed = false; loaded = true }"))
    }

    @Test
    fun `fallback letter hides once the image renders`() {
        // A transparent PNG used to show the monogram through its own holes:
        // the letter must be fallback-only, not a permanent underlay.
        val avatar = source("src/main/java/dev/mirutime/app/design/components/PluginAvatar.kt")
        assertTrue(avatar.contains("var loaded by remember(iconUrl)"))
        assertTrue(avatar.contains("if (!loaded) {"))
        assertTrue(avatar.contains("onSuccess = { loaded = true }"))
        assertTrue(avatar.contains("onError = { loaded = false }"))

        val cover = source("src/main/java/dev/mirutime/app/design/components/BookCover.kt")
        assertTrue(cover.contains("if (showInitial && !loaded)"))
        assertTrue(cover.contains("else if (placeholderIcon != null && !loaded)"))
    }

    @Test
    fun `catalogue cards pass a retry that persists the repaired url`() {
        val catalog = source("src/main/java/dev/mirutime/app/features/finder/ui/SourceCatalogScreen.kt")
        assertTrue(catalog.contains("mutableStateMapOf<String, String>()"))
        assertTrue(catalog.contains("coverFixes[book.bookUrl] = fresh"))
        assertTrue(catalog.contains("provider.refreshCatalogCover(sourceId, book.bookUrl)"))
        assertTrue(catalog.contains("reloadKey = reloadTick"))
        assertTrue(catalog.contains("val coverDone = remember(sourceId, filters, reloadTick)"))
        assertTrue(catalog.contains("snapshotFlow {"))
        assertTrue(catalog.contains("pageState.books to (pageState.active && !pageState.loading)"))
        assertTrue(catalog.contains("coverDone.add(url)"))
        // A resolved cover and a "this book has none" answer both have to
        // outlive the screen, or every visit re-asks the whole page.
        assertTrue(catalog.contains("CatalogCoverMemory.cover(sourceId, book.bookUrl)"))
        assertTrue(catalog.contains("\"\" -> CatalogCoverMemory.putMiss(sourceId, url)"))
        assertTrue(catalog.contains("if (isLocal || isOpds) return@LaunchedEffect"))
    }

    @Test
    fun `library cards persist a repaired cover across disposal`() {
        val library = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")
        assertTrue(library.contains("mutableStateMapOf<String, String>()"))
        assertTrue(library.contains("coverFixes[book.bookKey] = fresh"))
        assertTrue(library.contains("val fixCover: (LibraryBook) -> LibraryBook"))
        assertTrue(library.contains("book = fixCover(book)"))
        assertTrue(library.contains("reloadKey = pager.generation"))
        // The shared continue block gets the same repair callback; the screen
        // now owns it instead of the deleted private resume card.
        assertTrue(library.contains("onRetry = {"))
        assertTrue(library.contains("provider.refreshCover(book.bookKey).getOrNull()?.also { fresh ->"))
    }
}
