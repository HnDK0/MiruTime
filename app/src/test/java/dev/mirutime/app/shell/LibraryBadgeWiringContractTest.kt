package dev.mirutime.app.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The badges are DB truth, not catalogue data: a membership write must reach
 * them without a restart. These are wiring assertions only — a source-string
 * contract is the only lever this project has without Compose UI tests.
 */
class LibraryBadgeWiringContractTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
        File("../$relative"),
    ).first { it.isFile }.readText()

    @Test
    fun `a catalogue delete invalidates cached badges`() {
        val screen = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")

        assertTrue(screen.contains("action == BulkAction.Delete && result.succeeded.isNotEmpty()"))
        assertTrue(screen.contains("onLibraryChanged()"))
    }

    @Test
    fun `the library route drops caches without a revision bump`() {
        val shell = source("src/main/java/dev/mirutime/app/shell/MiruShell.kt")

        assertTrue(shell.contains("val invalidateCachedBadges"))
        assertTrue(shell.contains("onLibraryChanged = invalidateCachedBadges"))
        assertTrue(shell.contains("catalogSessions.invalidateAll()"))
    }

    @Test
    fun `adding by link invalidates cached badges`() {
        val finder = source("src/main/java/dev/mirutime/app/features/finder/ui/FinderScreen.kt")
        val shell = source("src/main/java/dev/mirutime/app/shell/MiruShell.kt")

        assertTrue(finder.contains("onLibraryChanged()"))
        val finderRoute = shell.substringAfter("FinderScreen(").substringBefore("\n            )")
        assertTrue(finderRoute.contains("onLibraryChanged = onLibraryChanged"))
    }

    @Test
    fun `the catalogue rereads membership for loaded cards on entry`() {
        val screen = source("src/main/java/dev/mirutime/app/features/finder/ui/SourceCatalogScreen.kt")

        assertTrue(screen.contains("provider.withMembership(loaded)"))
        assertTrue(screen.contains("session.updateBooks"))
        // OPDS membership comes from the feed and must not be rewritten here.
        assertTrue(screen.contains("if (isOpds) return@LaunchedEffect"))
        assertFalse(screen.contains("libraryRevision"))
    }
}
