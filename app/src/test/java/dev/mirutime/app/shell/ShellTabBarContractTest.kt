package dev.mirutime.app.shell

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTabBarContractTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
        File("../$relative"),
    ).first { it.isFile }.readText()

    @Test
    fun `tab bar uses labelled items and a circular selection plate`() {
        val shell = source("src/main/java/dev/mirutime/app/shell/MiruShell.kt")
        val item = source("ui-kit/src/main/java/dev/mirutime/uikit/MiruTabItem.kt")

        assertTrue(shell.contains("MiruTabItem("))
        assertTrue(shell.contains("titleRes"))
        assertFalse(shell.contains("width(44.dp).height(28.dp)"))
        assertTrue(item.contains("CircleShape"))
        assertTrue(item.contains("contentDescription = label"))
        assertTrue(item.contains("selectable("))
    }

    @Test
    fun `main tabs fade above the opaque plate`() {
        val shell = source("src/main/java/dev/mirutime/app/shell/MiruShell.kt")
        assertTrue(shell.contains("Brush.verticalGradient"))
        assertTrue(shell.contains("Color.Transparent"))
    }
}
