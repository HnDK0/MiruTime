package dev.mirutime.app.features.dashboard.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRailContractTest {
    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    @Test
    fun `history and updates are the same card rail with +N`() {
        val text = source("src/main/java/dev/mirutime/app/features/dashboard/ui/DashboardScreen.kt")

        assertTrue(text.contains("HistoryRail("))
        assertTrue(text.contains("UpdatesRail("))
        assertTrue(text.contains("newChapters = update.newChapters"))
        assertTrue(text.contains("MiruSpace.CardGap"))
        assertFalse(text.contains("UpdatesPanel("))
    }
}
