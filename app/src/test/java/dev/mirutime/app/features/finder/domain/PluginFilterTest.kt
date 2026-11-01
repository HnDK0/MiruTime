package dev.mirutime.app.features.finder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginFilterTest {

    private fun filter(
        key: String,
        type: String,
        label: String = key,
        options: List<Pair<String, String>> = emptyList(),
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        put("key", key)
        put("type", type)
        put("label", label)
        put("options.count", options.size.toString())
        options.forEachIndexed { index, (value, optionLabel) ->
            put("options.$index.value", value)
            put("options.$index.label", optionLabel)
        }
        putAll(extra)
    }

    @Test
    fun `parser reads every filter type with options and defaults`() {
        val parsed = parsePluginFilters(
            listOf(
                filter(
                    "order",
                    "sort",
                    "Sort by",
                    listOf("weekly" to "Weekly", "new" to "Newest"),
                    mapOf("defaultValue" to "new", "defaultAscending" to "true"),
                ),
                filter(
                    "kind",
                    "select",
                    "Kind",
                    listOf("all" to "All", "novel" to "Novel"),
                    mapOf("defaultValue" to "all"),
                ),
                filter(
                    "genre",
                    "checkbox",
                    "Genres",
                    listOf("action" to "Action"),
                    mapOf("multiselect" to "false"),
                ),
                filter("status", "tristate", "Status", listOf("ongoing" to "Ongoing")),
                filter(
                    "completed",
                    "switch",
                    "Completed only",
                    extra = mapOf("defaultValue" to "true"),
                ),
                filter("author", "text", "Author", extra = mapOf("defaultValue" to "Ann")),
                filter(
                    "tags",
                    "tag_input",
                    "Tags",
                    listOf("isekai" to "Isekai"),
                    mapOf("allowCustom" to "false"),
                ),
            ),
        )

        assertEquals(7, parsed.size)
        assertEquals(PluginFilterType.Sort, parsed[0].type)
        assertEquals("new", parsed[0].defaultValue)
        assertTrue(parsed[0].defaultAscending)
        assertEquals("Newest", parsed[0].options[1].label)
        assertEquals(PluginFilterType.Select, parsed[1].type)
        assertEquals("all", parsed[1].defaultValue)
        assertEquals(PluginFilterType.Checkbox, parsed[2].type)
        assertEquals(false, parsed[2].multiselect)
        assertEquals(PluginFilterType.Tristate, parsed[3].type)
        assertEquals(PluginFilterType.Switch, parsed[4].type)
        assertEquals("true", parsed[4].defaultValue)
        assertEquals(PluginFilterType.Text, parsed[5].type)
        assertEquals("Ann", parsed[5].defaultValue)
        assertEquals(PluginFilterType.TagInput, parsed[6].type)
        assertEquals(false, parsed[6].allowCustom)
    }

    @Test
    fun `unknown type is dropped and missing type defaults to select`() {
        val parsed = parsePluginFilters(
            listOf(
                filter("future", "rating", "Future"),
                filter("kind", "select", "Kind", listOf("all" to "All")),
                mapOf(
                    "key" to "no-type",
                    "label" to "No type",
                    "options.count" to "1",
                    "options.0.value" to "one",
                    "options.0.label" to "One",
                ),
            ),
        )

        assertEquals(listOf("kind", "no-type"), parsed.map { it.key })
        assertEquals(PluginFilterType.Select, parsed[1].type)
    }

    @Test
    fun `settings-shaped current value is reused as the default`() {
        val parsed = parsePluginFilters(
            listOf(
                mapOf(
                    "key" to "token",
                    "type" to "select",
                    "label" to "Token",
                    "current" to "saved",
                    "options.count" to "1",
                    "options.0.value" to "saved",
                    "options.0.label" to "Saved",
                ),
            ),
        ).single()

        assertEquals("saved", parsed.defaultValue)
    }

    @Test
    fun `missing key and option-less choice filters are dropped`() {
        val parsed = parsePluginFilters(
            listOf(
                mapOf("type" to "select", "label" to "No key"),
                filter("empty", "select", "Empty"),
                filter("tags", "tag_input", "Tags"),
            ),
        )

        assertEquals(listOf("tags"), parsed.map { it.key })
    }

    @Test
    fun `blank option value with a label is kept as the All choice`() {
        val parsed = parsePluginFilters(
            listOf(
                filter(
                    "language",
                    "select",
                    "Language",
                    listOf("" to "All", "english" to "English"),
                    mapOf("defaultValue" to ""),
                ),
            ),
        ).single()

        assertEquals(
            listOf("" to "All", "english" to "English"),
            parsed.options.map { it.value to it.label },
        )
        assertEquals("", parsed.defaultValue)
    }

    @Test
    fun `option with no value and no label is dropped`() {
        val map = buildMap {
            put("key", "kind")
            put("type", "select")
            put("label", "Kind")
            put("options.count", "2")
            put("options.0.value", "")
            put("options.0.label", "")
            put("options.1.value", "novel")
            put("options.1.label", "Novel")
        }

        val parsed = parsePluginFilters(listOf(map)).single()

        assertEquals(listOf("novel" to "Novel"), parsed.options.map { it.value to it.label })
    }

    @Test
    fun `sort defaults to the first option when defaultValue is absent`() {
        val parsed = parsePluginFilters(
            listOf(filter("order", "sort", options = listOf("weekly" to "Weekly", "new" to "Newest"))),
        ).single()

        assertEquals("weekly", parsed.defaultValue)
        assertEquals(false, parsed.defaultAscending)
    }

    @Test
    fun `serialization uses the exact Lua keys per type`() {
        val sort = PluginFilter(
            "order",
            PluginFilterType.Sort,
            "Sort",
            listOf(PluginFilterOption("weekly", "Weekly"), PluginFilterOption("new", "Newest")),
            defaultValue = "weekly",
            defaultAscending = false,
        )
        val select = PluginFilter(
            "kind",
            PluginFilterType.Select,
            "Kind",
            listOf(PluginFilterOption("all", "All")),
            defaultValue = "all",
        )
        val checkbox = PluginFilter(
            "genre",
            PluginFilterType.Checkbox,
            "Genres",
            listOf(PluginFilterOption("action", "Action")),
        )
        val tristate = PluginFilter(
            "status",
            PluginFilterType.Tristate,
            "Status",
            listOf(PluginFilterOption("ongoing", "Ongoing")),
        )
        val switch = PluginFilter("completed", PluginFilterType.Switch, "Completed")
        val text = PluginFilter("author", PluginFilterType.Text, "Author")
        val tags = PluginFilter(
            "tags",
            PluginFilterType.TagInput,
            "Tags",
            listOf(PluginFilterOption("isekai", "Isekai")),
        )

        assertEquals(
            mapOf("order" to listOf("new"), "order_ascending" to listOf("true")),
            sort.toLuaValues(value = "new", ascending = true),
        )
        assertEquals(mapOf("kind" to listOf("novel")), select.toLuaValues(value = "novel"))
        assertEquals(
            mapOf("genre_included" to listOf("action", "fantasy")),
            checkbox.toLuaValues(included = listOf("action", "fantasy")),
        )
        assertEquals(
            mapOf(
                "status_included" to listOf("ongoing"),
                "status_excluded" to listOf("completed"),
            ),
            tristate.toLuaValues(
                included = listOf("ongoing"),
                excluded = listOf("completed"),
            ),
        )
        assertEquals(mapOf("completed" to listOf("false")), switch.toLuaValues())
        assertEquals(mapOf("author" to listOf("Ann")), text.toLuaValues(value = "Ann"))
        assertEquals(
            mapOf("tags_included" to listOf("custom", "isekai")),
            tags.toLuaValues(included = listOf("custom", "isekai")),
        )
    }

    @Test
    fun `request map merges schema defaults with active values`() {
        val schema = listOf(
            PluginFilter(
                "order",
                PluginFilterType.Sort,
                "Sort",
                listOf(PluginFilterOption("weekly", "Weekly")),
                defaultValue = "weekly",
            ),
            PluginFilter(
                "kind",
                PluginFilterType.Select,
                "Kind",
                listOf(PluginFilterOption("all", "All")),
                defaultValue = "all",
            ),
            PluginFilter("completed", PluginFilterType.Switch, "Completed"),
            PluginFilter("genre", PluginFilterType.Checkbox, "Genres"),
            PluginFilter("author", PluginFilterType.Text, "Author"),
        )

        val map = pluginFilterMap(
            CatalogFilters(
                pluginValues = mapOf(
                    "order" to listOf("new"),
                    "order_ascending" to listOf("true"),
                    "genre_included" to listOf("action"),
                    "author" to listOf("Ann"),
                ),
            ),
            schema,
        )

        assertEquals(
            mapOf(
                "order" to listOf("new"),
                "order_ascending" to listOf("true"),
                "kind" to listOf("all"),
                "completed" to listOf("false"),
                "genre_included" to listOf("action"),
                "author" to listOf("Ann"),
            ),
            map,
        )
    }

    @Test
    fun `capabilityComposesSearchFilters`() {
        val schema = listOf(
            PluginFilter(
                "kind",
                PluginFilterType.Select,
                "Kind",
                listOf(PluginFilterOption("all", "All")),
                defaultValue = "all",
            ),
            PluginFilter(
                "genre",
                PluginFilterType.Checkbox,
                "Genres",
                listOf(PluginFilterOption("action", "Action")),
            ),
        )

        // Capable plugin: schema defaults + user values + the reserved `_query`.
        assertEquals(
            mapOf(
                "kind" to listOf("all"),
                "genre_included" to listOf("action"),
                "_query" to listOf("dragon"),
            ),
            pluginFilterRequest(
                CatalogFilters(
                    query = "dragon",
                    pluginValues = mapOf("genre_included" to listOf("action")),
                ),
                schema,
                supportsSearchFilters = true,
            ),
        )

        // No schema means no filter request: the capability alone cannot
        // compose one, so the provider stays on the search path.
        assertNull(
            pluginFilterRequest(
                CatalogFilters(query = "dragon"),
                emptyList(),
                supportsSearchFilters = true,
            ),
        )

        // A blank query keeps the old catalog contract: no `_query` key.
        assertEquals(
            emptyMap<String, List<String>>(),
            pluginFilterRequest(CatalogFilters(), schema, supportsSearchFilters = true),
        )
    }

    @Test
    fun `query mode never composes a filter request`() {
        val schema = listOf(
            PluginFilter(
                "kind",
                PluginFilterType.Select,
                "Kind",
                listOf(PluginFilterOption("all", "All")),
                defaultValue = "all",
            ),
        )

        assertNull(pluginFilterRequest(CatalogFilters(query = "dragon"), schema))
        // NoveLA parity: schema defaults alone do not switch the catalog to
        // getCatalogFiltered; only a user-changed value does.
        assertEquals(emptyMap<String, List<String>>(), pluginFilterRequest(CatalogFilters(), schema))
        assertEquals(
            mapOf("kind" to listOf("all")),
            pluginFilterRequest(
                CatalogFilters(pluginValues = mapOf("kind" to listOf("all"))),
                schema,
            ),
        )
    }

    @Test
    fun `restrictToSchema keeps declared keys and drops legacy promises`() {
        val schema = listOf(
            PluginFilter(
                "order",
                PluginFilterType.Sort,
                "Sort",
                listOf(PluginFilterOption("weekly", "Weekly")),
            ),
            PluginFilter("genre", PluginFilterType.Checkbox, "Genres"),
            PluginFilter("status", PluginFilterType.Tristate, "Status"),
        )
        val filters = CatalogFilters(
            genres = setOf("Fantasy"),
            ongoingOnly = true,
            pluginValues = mapOf(
                "order" to listOf("weekly"),
                "order_ascending" to listOf("false"),
                "order_removed" to listOf("x"),
                "genre_included" to listOf("action"),
                "status_excluded" to listOf("completed"),
                "ghost_included" to listOf("x"),
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
                "status_excluded" to listOf("completed"),
            ),
            restricted.pluginValues,
        )
    }
}
