package dev.mirutime.app.features.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Source guards for the chip-rail and plugin-avatar standards. They catch the
// wiring regressing; they do not prove pixels, scrolling or icon loading.
class ChipRailContractTest {

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
        File("../$relative"),
    ).firstOrNull { it.isFile }?.readText()
        ?: error("source not found: $relative")

    // Every chip strip scrolls on one line; a wrapping FlowRow of chips was
    // the Mass Migration defect.
    @Test
    fun `migration chips never wrap`() {
        val steps = source("src/main/java/dev/mirutime/app/features/migration/ui/MigrationSteps.kt")
        val screen = source("src/main/java/dev/mirutime/app/features/migration/ui/MigrationScreen.kt")
        assertFalse(steps.contains("FlowRow("))
        assertFalse(screen.contains("FlowRow("))
        assertTrue(steps.contains("ChipRail {"))
        assertTrue(screen.contains("ChipRail {"))
    }

    @Test
    fun `library mini filters use the shared rail`() {
        val text = source("src/main/java/dev/mirutime/app/features/library/ui/LibraryScreen.kt")
        assertTrue(text.contains("ChipRail {"))
    }

    // Icons are unconditional: the settings toggle and its key are gone, and
    // every plugin row renders the shared avatar instead of a letter tile.
    @Test
    fun `plugin icons are not gated by a setting`() {
        for (relative in listOf(
            "src/main/java/dev/mirutime/app/features/settings/data/AppSettings.kt",
            "src/main/java/dev/mirutime/app/features/settings/data/SettingsPersistence.kt",
            "src/main/java/dev/mirutime/app/features/finder/ui/ManageExtensionsScreen.kt",
        )) {
            assertFalse(relative, source(relative).contains("showPluginIcons"))
        }
    }

    @Test
    fun `plugin rows use the shared avatar`() {
        val extensions = source("src/main/java/dev/mirutime/app/features/finder/ui/ManageExtensionsScreen.kt")
        assertFalse(extensions.contains("MonogramTile(plugin.name)"))
        assertTrue(extensions.contains("PluginAvatar(name = plugin.name, iconUrl = plugin.iconUrl)"))
    }
}
