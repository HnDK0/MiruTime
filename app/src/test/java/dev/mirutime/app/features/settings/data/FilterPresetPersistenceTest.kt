package dev.mirutime.app.features.settings.data

import dev.mirutime.app.features.finder.domain.CatalogFilters
import dev.mirutime.app.features.finder.domain.CatalogSort
import dev.mirutime.app.features.finder.domain.FilterPreset
import dev.mirutime.app.features.finder.domain.PluginFilter
import dev.mirutime.app.features.finder.domain.PluginFilterOption
import dev.mirutime.app.features.finder.domain.PluginFilterType
import dev.mirutime.app.features.finder.domain.restrictToSchema
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterPresetPersistenceTest {

    @Test
    fun `plugin values round-trip with the old genre fields`() {
        val presets = mapOf(
            "alpha" to listOf(
                FilterPreset(
                    name = "Mix; with: punctuation",
                    filters = CatalogFilters(
                        query = "dragon",
                        sort = CatalogSort.Latest,
                        descending = false,
                        genres = setOf("Fantasy", "Action"),
                        ongoingOnly = true,
                        pluginValues = mapOf(
                            "order" to listOf("new"),
                            "order_ascending" to listOf("true"),
                            "genre_included" to listOf("action", "fantasy"),
                            "status_excluded" to listOf("completed"),
                        ),
                    ),
                ),
            ),
        )

        val decoded = SettingsStore.decodeFilterPresets(
            SettingsStore.encodeFilterPresets(presets),
        )

        assertEquals(presets, decoded)
    }

    @Test
    fun `old preset records decode with empty plugin values`() {
        // Pre-plugin format: source, name, sort, descending, ongoing, query,
        // then the genre list.
        val legacy = SettingsStore.encodeRecords(
            listOf(
                listOf(
                    "alpha",
                    "Old",
                    "Popular",
                    "true",
                    "false",
                    "",
                    "Fantasy",
                    "Action",
                ),
            ),
        )

        val decoded = SettingsStore.decodeFilterPresets(legacy)

        val preset = decoded.getValue("alpha").single()
        assertEquals("Old", preset.name)
        assertEquals(setOf("Fantasy", "Action"), preset.filters.genres)
        assertEquals(emptyMap<String, List<String>>(), preset.filters.pluginValues)
    }

    @Test
    fun `stale preset keys are dropped against the current schema`() {
        val schema = listOf(
            PluginFilter(
                key = "order",
                type = PluginFilterType.Sort,
                label = "Sort",
                options = listOf(PluginFilterOption("weekly", "Weekly")),
            ),
            PluginFilter("genre", PluginFilterType.Checkbox, "Genres"),
        )
        val filters = CatalogFilters(
            genres = setOf("Fantasy"),
            ongoingOnly = true,
            pluginValues = mapOf(
                "order" to listOf("weekly"),
                "order_ascending" to listOf("false"),
                "genre_included" to listOf("action"),
                "removed_included" to listOf("gone"),
            ),
        )

        val restricted = filters.restrictToSchema(schema)

        assertEquals(emptySet<String>(), restricted.genres)
        assertEquals(false, restricted.ongoingOnly)
        assertEquals(
            mapOf(
                "order" to listOf("weekly"),
                "order_ascending" to listOf("false"),
                "genre_included" to listOf("action"),
            ),
            restricted.pluginValues,
        )
    }
}
