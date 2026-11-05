package dev.mirutime.uikit

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Source wiring checks for the shared sheet shell and its collapsible section.
class SheetStandardTest {
    private fun source(name: String): String = sequenceOf(
        File("src/main/java/dev/mirutime/uikit/$name.kt"),
        File("ui-kit/src/main/java/dev/mirutime/uikit/$name.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun `sections start collapsed`() {
        val section = source("AppBottomSheet").substringAfter("fun SheetSection(")
        assertTrue(section.contains("initiallyExpanded: Boolean = false"))
    }

    @Test
    fun `expanding a section scrolls it into view`() {
        val section = source("AppBottomSheet").substringAfter("fun SheetSection(")
        assertTrue(section.contains("BringIntoViewRequester"))
        assertTrue(section.contains("bringIntoViewRequester(requester)"))
        assertTrue(section.contains("requester.bringIntoView()"))
        assertTrue(section.contains("AnimatedVisibility"))
    }

    @Test
    fun `sheet header uses a compact handle and a compact title`() {
        val sheet = source("AppBottomSheet")
        assertFalse(sheet.contains("BottomSheetDefaults.DragHandle()"))
        assertTrue(sheet.contains("MiruSize.SheetHandleBox"))
        assertFalse(sheet.contains("typography.titleLarge"))
        assertTrue(sheet.contains("padding(top = MiruSpace.S)"))
    }

    @Test
    fun `reset is a header action shared by every sheet`() {
        val action = source("SheetHeaderAction")
        assertTrue(action.contains("fun SheetHeaderAction("))
        assertTrue(action.contains("minimumInteractiveComponentSize()"))
        assertTrue(action.contains("MiruSize.Control"))
    }
}
