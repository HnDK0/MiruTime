package dev.mirutime.app.features.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Source guards for the two alpha-mask defects and the duplicated CTA gap.
// They catch the wiring regressing; they do not prove pixels or hit boxes.
class ContentFadeContractTest {

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
        File("../$relative"),
    ).firstOrNull { it.isFile }?.readText()
        ?: error("source not found: $relative")

    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("missing start delimiter: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("missing end delimiter: $end", to > from)
        return text.substring(from, to)
    }

    @Test
    fun `library mini filters are not alpha masked`() {
        val text = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")
        val mini = slice(text, "private fun MiniFilters(", "private fun LibrarySort.label()")
        assertFalse(mini.contains("miruEdgeFade"))
    }

    @Test
    fun `shelf tabs are not alpha masked`() {
        val text = source("src/main/java/dev/mirutime/app/features/library/ui/ShelfTabs.kt")
        assertFalse(text.contains("miruEdgeFade"))
    }

    @Test
    fun `the shared edge fade helper is gone`() {
        val text = source("ui-kit/src/main/java/dev/mirutime/uikit/FilterChips.kt")
        assertFalse(text.contains("fun Modifier.miruEdgeFade"))
    }

    @Test
    fun `migration list is not alpha masked`() {
        val text = source("src/main/java/dev/mirutime/app/features/migration/ui/MigrationScreen.kt")
        assertFalse(text.contains("fadeBottomEdge"))
    }

    // One owner: the list reserves the CTA's measured height only while the
    // CTA is visible. The old code also added a token constant unconditionally.
    @Test
    fun `migration cta clearance follows visibility`() {
        val text = source("src/main/java/dev/mirutime/app/features/migration/ui/MigrationScreen.kt")
        val screen = slice(text, "fun MigrationScreen(", "private data class MigrationCta")
        assertFalse(screen.contains("FloatingCtaClearance"))
        assertFalse(screen.contains("padding(bottom = ctaHeight"))
        assertTrue(screen.contains("if (cta.visible) ctaHeight + MiruSpace.M else 0.dp"))
        assertTrue(
            screen.contains("bottom = contentPadding.calculateBottomPadding() + ctaClearance"),
        )
    }
}
