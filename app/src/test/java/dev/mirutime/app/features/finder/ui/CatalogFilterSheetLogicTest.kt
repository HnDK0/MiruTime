package dev.mirutime.app.features.finder.ui

import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.PluginFilterOption
import dev.mirutime.app.features.finder.domain.PluginFilterType
import dev.mirutime.app.features.finder.domain.PluginFilterValue
import dev.mirutime.app.features.finder.domain.pluginFilterDefaults
import dev.mirutime.app.features.finder.domain.withPluginDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure decision table of the filter sheet: opening the sheet or seeding the
// schema must never fire a catalogue request, and Reset must stay reachable
// while stale plugin values survive in the applied snapshot.
class CatalogFilterSheetLogicTest {

    private val schema = listOf(
        PluginFilter(
            key = "kind",
            type = PluginFilterType.Select,
            label = "Kind",
            options = listOf(PluginFilterOption("all", "All")),
            defaultValue = "all",
        ),
    )
    private val defaults = pluginFilterDefaults(schema)

    @Test
    fun `first composition never applies`() {
        assertFalse(
            CatalogFilterEmission.shouldApply(
                previous = null,
                current = CatalogFilters(),
                userEdited = false,
            ),
        )
        assertFalse(
            CatalogFilterEmission.shouldApply(
                previous = null,
                current = CatalogFilters(query = "dragon"),
                userEdited = false,
            ),
        )
    }

    @Test
    fun `schema arrival never applies`() {
        val seeded = CatalogFilters().withPluginDraft(schema, defaults)

        assertFalse(
            CatalogFilterEmission.shouldApply(
                previous = CatalogFilters(),
                current = seeded,
                userEdited = false,
            ),
        )
    }

    @Test
    fun `a user edit applies once and an unchanged draft does not`() {
        val edited = CatalogFilters(pluginValues = mapOf("kind" to listOf("all")))

        assertTrue(
            CatalogFilterEmission.shouldApply(
                previous = CatalogFilters(),
                current = edited,
                userEdited = true,
            ),
        )
        assertFalse(
            CatalogFilterEmission.shouldApply(
                previous = edited,
                current = edited,
                userEdited = true,
            ),
        )
    }

    @Test
    fun `reset is enabled for stale plugin values under an empty schema`() {
        val applied = CatalogFilters(pluginValues = mapOf("ghost_included" to listOf("x")))

        assertTrue(
            CatalogFilterEmission.canReset(
                applied = applied,
                draft = CatalogFilters(),
                local = false,
                pluginDraft = emptyMap(),
                pluginDefaults = emptyMap(),
            ),
        )
    }

    @Test
    fun `reset is disabled when nothing differs`() {
        assertFalse(
            CatalogFilterEmission.canReset(
                applied = CatalogFilters(),
                draft = CatalogFilters(),
                local = false,
                pluginDraft = defaults,
                pluginDefaults = defaults,
            ),
        )
        assertFalse(
            CatalogFilterEmission.canReset(
                applied = CatalogFilters(),
                draft = CatalogFilters(),
                local = true,
                pluginDraft = emptyMap(),
                pluginDefaults = emptyMap(),
            ),
        )
    }

    @Test
    fun `reset is enabled for a changed plugin draft and a local draft`() {
        val changed = defaults + ("kind" to PluginFilterValue.Select("novel"))

        assertTrue(
            CatalogFilterEmission.canReset(
                applied = CatalogFilters(),
                draft = CatalogFilters(),
                local = false,
                pluginDraft = changed,
                pluginDefaults = defaults,
            ),
        )
        assertTrue(
            CatalogFilterEmission.canReset(
                applied = CatalogFilters(sort = CatalogSort.Latest),
                draft = CatalogFilters(sort = CatalogSort.Latest),
                local = true,
                pluginDraft = emptyMap(),
                pluginDefaults = emptyMap(),
            ),
        )
    }
}
