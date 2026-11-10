package dev.mirutime.feature.reader.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTapTest {
    private val timeout = 300L

    @Test
    fun `in two-tap mode only the second tap of the pair toggles the menu`() {
        assertFalse(menuTapToggles(isDouble = false, doubleTapMenu = true))
        assertTrue(menuTapToggles(isDouble = true, doubleTapMenu = true))
    }

    @Test
    fun `in one-tap mode only the first tap of the pair toggles the menu`() {
        assertTrue(menuTapToggles(isDouble = false, doubleTapMenu = false))
        assertFalse(menuTapToggles(isDouble = true, doubleTapMenu = false))
    }

    @Test
    fun `the double tap window is measured between the taps, not between the callbacks`() {
        // First tap: pressed at 0, released at 200 (held 200 ms).
        // Second tap: pressed at 300 (100 ms after the release), released at 550.
        val firstUpAt = 200L
        val secondDownAt = 300L
        val secondUpAt = 550L

        // The window runs from the release of the first tap to the press of
        // the second one: 100 ms, so it is a double tap.
        assertTrue(isMenuDoubleTap(now = secondDownAt, lastTapAt = firstUpAt, timeout = timeout))
        // Measuring it between the callbacks instead adds the hold of the
        // second tap (250 ms) and misses the window on a real device.
        assertFalse(isMenuDoubleTap(now = secondUpAt, lastTapAt = firstUpAt, timeout = timeout))
    }

    @Test
    fun `a gap of 400 ms between the taps is not a double tap`() {
        val firstUpAt = 200L
        val secondDownAt = 600L

        assertFalse(isMenuDoubleTap(now = secondDownAt, lastTapAt = firstUpAt, timeout = timeout))
    }

    @Test
    fun `the screens take the double tap from the gesture layer instead of the callback clock`() {
        val paged = source("feature-reader/src/main/java/dev/mirutime/feature/reader/ui/ReaderPaged.kt")
        val feed = source("feature-reader/src/main/java/dev/mirutime/feature/reader/api/ReaderScreen.kt")

        // The gesture layer owns the window: it knows when the tap was pressed
        // and when the previous one was released.
        assertTrue(paged.contains("now = down.uptimeMillis"))
        // The screens must not measure the window themselves: the clock read
        // in the callback counts the hold of the finger, not the gap between
        // the taps, which is why the menu did not open on a real device.
        assertFalse(feed.contains("isMenuDoubleTap("))
        assertFalse(tapHandler(paged).contains("SystemClock"))
        assertFalse(tapHandler(feed).contains("SystemClock"))
    }

    private fun source(path: String): String = File(repoRoot, path).readText()

    private fun tapHandler(source: String): String = source.substringAfter("awaitReaderTap(")

    private companion object {
        val repoRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repository root not found")
    }
}
