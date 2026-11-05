package dev.mirutime.uikit

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Source wiring checks complement JVM geometry tests; neither is device evidence.
class ControlLayoutRegressionTest {
    private fun source(name: String): String = sequenceOf(
        File("src/main/java/dev/mirutime/uikit/$name.kt"),
        File("ui-kit/src/main/java/dev/mirutime/uikit/$name.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun `chip reserves a minimum target independently of its intrinsic face`() {
        val chip = source("FilterChips").substringAfter("fun MiruFilterChip(")
            .substringBefore("// Right-edge fade")
        assertFalse("The old 40dp outer chip clips the minimum target", chip.contains(".height(40.dp)"))
        assertTrue(chip.contains("minimumInteractiveComponentSize()"))
        assertTrue(chip.contains("LocalMinimumInteractiveComponentSize provides MiruSize.Touch"))
        assertTrue(chip.contains("heightIn(min = MiruSize.Chip)"))
    }

    @Test
    fun `sheet budgets the native handle and owns navigation insets inside the cap`() {
        val sheet = source("AppBottomSheet")
        assertFalse("A screen-height cap on only the inner column is not a whole-sheet cap", sheet.contains("LocalConfiguration.current.screenHeightDp"))
        assertTrue(sheet.contains("BoxWithConstraints"))
        assertTrue(sheet.contains("sheetContentMaxHeight("))
        assertTrue(sheet.contains("MiruSize.SheetHandleBox"))
        assertTrue(sheet.contains("onSizeChanged"))
        assertTrue(sheet.contains("contentWindowInsets = { WindowInsets(0) }"))
        assertTrue(sheet.contains("windowInsetsPadding(WindowInsets.navigationBars)"))
    }

    @Test
    fun `settings page centers its bounded column without changing caller padding`() {
        val page = source("SettingsRows").substringAfter("fun SettingsPage(")
            .substringBefore("private fun RowTexts(")
        assertTrue(page.contains("Alignment.TopCenter"))
        assertTrue(page.contains("widthIn(max = MiruSize.ContentMaxWidth)"))
        assertTrue(page.contains("contentPadding.calculateTopPadding() + MiruSpace.Xs"))
        assertTrue(page.contains("contentPadding.calculateBottomPadding() + MiruSize.Touch"))
    }

    @Test
    fun `section actions can wrap instead of clipping at 200 percent`() {
        assertFalse(source("SectionHeader").contains("maxLines = 1"))
    }

    @Test
    fun `settings values can wrap instead of clipping at 200 percent`() {
        val rows = source("SettingsRows").substringAfter("fun SettingsValueRow(")
            .substringBefore("// Slider with")
        assertFalse(rows.contains("maxLines = 1"))
    }
}
