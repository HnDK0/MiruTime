package dev.mirutime.uikit

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiruControlsContractTest {
    private fun source(name: String): String = sequenceOf(
        File("src/main/java/dev/mirutime/uikit/$name.kt"),
        File("ui-kit/src/main/java/dev/mirutime/uikit/$name.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun `button and icon roles keep visual and minimum target sizes separate`() {
        assertEquals(52.dp, MiruButtonStyle.Primary.minVisualHeight())
        assertEquals(52.dp, MiruButtonStyle.InversePrimary.minVisualHeight())
        listOf(MiruButtonStyle.Secondary, MiruButtonStyle.Destructive, MiruButtonStyle.Text).forEach {
            assertEquals(44.dp, it.minVisualHeight())
        }
        assertEquals(48.dp, MiruSize.Touch)
        assertEquals(40.dp, MiruSize.Control)
        assertEquals(22.dp, MiruSize.Icon)
        assertEquals(listOf(MiruIconButtonStyle.Bare, MiruIconButtonStyle.Plate), MiruIconButtonStyle.entries.toList())
    }

    @Test
    fun `whole sheet budget follows the bounded window including IME resized windows`() {
        for (windowHeight in listOf(1000.dp, 600.dp, 480.dp)) {
            for (handle in listOf(48.dp, 72.dp)) {
                for (fraction in listOf(0.55f, 0.7f, 1f)) {
                    val available = windowHeight - handle - MiruSpace.M
                    val body = sheetContentMaxHeight(available, handle, fraction)
                    val whole = body + handle + MiruSpace.M
                    assertEquals(windowHeight.value * fraction.coerceAtMost(0.7f), whole.value, 0.01f)
                    assertTrue(body >= 0.dp)
                }
            }
        }
    }

    @Test
    fun `sheet cap is a maximum and keeps native handle and scroll contracts`() {
        val sheet = source("AppBottomSheet")
        assertTrue(sheet.contains("heightIn(max = contentMaxHeight)"))
        assertFalse(sheet.contains("height(contentMaxHeight)"))
        assertTrue(sheet.contains("rememberModalBottomSheetState(skipPartiallyExpanded = true)"))
        assertTrue(sheet.contains("weight(1f, fill = false)"))
        assertTrue(sheet.contains("verticalScroll(scroll)"))
        assertTrue(sheet.contains("if (contentScrollable)"))
        assertTrue(sheet.contains("enabled = doneEnabled"))
        assertTrue(sheet.contains("busy = doneBusy"))
    }

    @Test
    fun `busy presentation never derives enabled and exposes native progress semantics`() {
        val buttons = source("MiruButtons")
        assertEquals(2, Regex("clickable\\(enabled = enabled, role = Role.Button").findAll(buttons).count())
        assertFalse(buttons.contains("enabled && !busy"))
        assertTrue(buttons.contains("ProgressBarRangeInfo.Indeterminate"))
        assertTrue(buttons.contains("LocalMinimumInteractiveComponentSize provides MiruSize.Touch"))
        assertTrue(buttons.contains("Color.Transparent"))
        assertTrue(buttons.contains("selected: Boolean? = null"))
        assertTrue(buttons.contains("selected?.let { this.selected = it }"))
    }

    @Test
    fun `picker trailing targets remain outside the disabled selectable main`() {
        val picker = source("MiruPickerRows").substringAfter("fun MiruPickerRow(")
        assertTrue(picker.contains("selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)"))
        assertFalse(picker.contains(".alpha("))
        assertFalse(picker.contains("clickable(enabled = enabled"))
        assertTrue(picker.contains("trailing?.invoke()"))
        assertTrue(source("SettingsRows").contains("MiruPickerRow("))
        assertTrue(source("AppBottomSheet").contains("MiruPickerHeader("))
    }

    @Test
    fun `selected picker paints a mirrored start edge inside clip without layout or interaction changes`() {
        val picker = source("MiruPickerRows").substringAfter("fun MiruPickerRow(")
        val root = picker.substringAfter("modifier = modifier").substringBefore("verticalAlignment =")
        assertTrue("Selected marker must be wired on the common row", root.contains(".drawBehind {"))
        assertTrue(root.indexOf(".clip(") < root.indexOf(".drawBehind {"))
        val paint = root.substringAfter(".drawBehind {")
        assertTrue(paint.contains("if (selected)"))
        assertTrue(paint.contains("val edgeWidth = MiruSpace.Xxs.toPx()"))
        assertTrue(paint.contains("layoutDirection == LayoutDirection.Rtl"))
        assertTrue(paint.contains("size.width - edgeWidth else 0f"))
        assertTrue(paint.contains("color = extra.accent"))
        assertTrue(paint.contains("Size(edgeWidth, size.height)"))
        assertFalse(paint.contains(".padding("))
        assertFalse(paint.contains(".offset("))
        assertFalse(paint.contains(".clickable("))
        assertFalse(paint.contains(".semantics"))
    }

    @Test
    fun `search decoration accepts an inner field and leaves keyboard and query policy to callers`() {
        val search = source("MiruSearchField")
        assertTrue(search.contains("innerTextField: @Composable () -> Unit"))
        assertTrue(search.contains("keyboardOptions: KeyboardOptions = KeyboardOptions.Default"))
        assertTrue(search.contains("keyboardActions: KeyboardActions = KeyboardActions.Default"))
        assertFalse(search.contains("ImeAction.Search"))
        assertFalse(search.contains("delay("))
        assertFalse(search.contains("mutableStateOf"))
        assertTrue(search.contains("clearContentDescription: String"))
    }

    @Test
    fun `simple choices select immediately and keep the sheet open`() {
        val choice = source("SettingsRows").substringAfter("fun <T> ChoiceSheet(")
        assertTrue(choice.indexOf("onSelect(option.key)") >= 0)
        assertTrue(!choice.contains("onClose()"))
    }
}
