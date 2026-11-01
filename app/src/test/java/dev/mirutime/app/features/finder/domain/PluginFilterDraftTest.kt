package dev.mirutime.app.features.finder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Widget-state machine of the schema-driven filter sheet. Pure functions, so
// the sheet itself only paints this model.
class PluginFilterDraftTest {

    private val sort = PluginFilter(
        key = "order",
        type = PluginFilterType.Sort,
        label = "Sort by",
        options = listOf(
            PluginFilterOption("weekly", "Weekly"),
            PluginFilterOption("new", "Newest"),
            PluginFilterOption("rating", "Rating"),
        ),
        defaultValue = "weekly",
        defaultAscending = false,
    )
    private val select = PluginFilter(
        key = "kind",
        type = PluginFilterType.Select,
        label = "Kind",
        options = listOf(
            PluginFilterOption("all", "All"),
            PluginFilterOption("novel", "Novel"),
        ),
        defaultValue = "all",
    )
    private val checkbox = PluginFilter(
        key = "genre",
        type = PluginFilterType.Checkbox,
        label = "Genres",
        options = listOf(
            PluginFilterOption("action", "Action"),
            PluginFilterOption("fantasy", "Fantasy"),
        ),
    )
    private val singleCheckbox = checkbox.copy(multiselect = false)
    private val tristate = PluginFilter(
        key = "status",
        type = PluginFilterType.Tristate,
        label = "Status",
        options = listOf(
            PluginFilterOption("ongoing", "Ongoing"),
            PluginFilterOption("completed", "Completed"),
        ),
    )
    private val switch = PluginFilter(
        key = "completed",
        type = PluginFilterType.Switch,
        label = "Completed only",
        defaultValue = "false",
    )
    private val text = PluginFilter(
        key = "author",
        type = PluginFilterType.Text,
        label = "Author",
    )
    private val tags = PluginFilter(
        key = "tags",
        type = PluginFilterType.TagInput,
        label = "Tags",
        options = listOf(PluginFilterOption("isekai", "Isekai")),
    )
    private val schema = listOf(sort, select, checkbox, tristate, switch, text, tags)

    @Test
    fun `defaults are built from the schema for every widget type`() {
        assertEquals(
            mapOf<String, PluginFilterValue>(
                "order" to PluginFilterValue.Sort("weekly", false),
                "kind" to PluginFilterValue.Select("all"),
                "genre" to PluginFilterValue.Checkbox(emptySet()),
                "status" to PluginFilterValue.Tristate(emptyMap()),
                "completed" to PluginFilterValue.Toggle(false),
                "author" to PluginFilterValue.TextInput(""),
                "tags" to PluginFilterValue.Tags(emptyList()),
            ),
            pluginFilterDefaults(schema),
        )
        assertTrue(pluginFilterDefaults(schema).toPluginValues(schema).isEmpty())
    }

    @Test
    fun `applied plugin values map into widget state and back unchanged`() {
        val applied = mapOf(
            "order" to listOf("new"),
            "order_ascending" to listOf("true"),
            "kind" to listOf("novel"),
            "genre_included" to listOf("action", "fantasy"),
            "status_included" to listOf("ongoing"),
            "status_excluded" to listOf("completed"),
            "completed" to listOf("true"),
            "author" to listOf("Ann"),
            "tags_included" to listOf("isekai", "custom"),
        )

        val draft = CatalogFilters(pluginValues = applied).toPluginFilterDraft(schema)

        assertEquals(PluginFilterValue.Sort("new", true), draft["order"])
        assertEquals(PluginFilterValue.Select("novel"), draft["kind"])
        assertEquals(
            PluginFilterValue.Checkbox(setOf("action", "fantasy")),
            draft["genre"],
        )
        assertEquals(PluginFilterValue.Toggle(true), draft["completed"])
        assertEquals(PluginFilterValue.TextInput("Ann"), draft["author"])
        assertEquals(
            PluginFilterValue.Tags(listOf("isekai", "custom")),
            draft["tags"],
        )
        assertEquals(
            mapOf(
                "order" to listOf("new"),
                "order_ascending" to listOf("true"),
                "kind" to listOf("novel"),
                "genre_included" to listOf("action", "fantasy"),
                "status_included" to listOf("ongoing"),
                "status_excluded" to listOf("completed"),
                "completed" to listOf("true"),
                "author" to listOf("Ann"),
                "tags_included" to listOf("isekai", "custom"),
            ),
            draft.toPluginValues(schema),
        )
    }

    @Test
    fun `only non-default values are stored and the active count is per filter`() {
        val draft = pluginFilterDefaults(schema)
            .withSort(sort, "rating")
            .toggleCheckbox(checkbox, "action")

        val filters = CatalogFilters().withPluginDraft(schema, draft)

        assertEquals(
            mapOf(
                "order" to listOf("rating"),
                "genre_included" to listOf("action"),
            ),
            filters.pluginValues,
        )
        // A sort writes two Lua keys but is still ONE changed filter.
        assertEquals(2, filters.pluginActiveFilterCount(schema))
        assertEquals(2, filters.pluginValues.count { it.value.isNotEmpty() })
    }

    @Test
    fun `reset returns every widget to the schema defaults`() {
        val edited = pluginFilterDefaults(schema)
            .withSort(sort, "new")
            .toggleSortDirection(sort)
            .withSelect(select, "novel")
            .toggleCheckbox(checkbox, "fantasy")
            .cycleTristate(tristate, "ongoing")
            .withSwitch(switch, true)
            .withText(text, "Ann")
            .toggleTag(tags, "custom")

        assertTrue(edited.toPluginValues(schema).isNotEmpty())
        val reset = pluginFilterDefaults(schema)
        assertEquals(pluginFilterDefaults(schema), reset)
        assertTrue(reset.toPluginValues(schema).isEmpty())
    }

    @Test
    fun `with plugin draft removes fixed app sort genres and status`() {
        val filters = CatalogFilters(
            query = "dragon",
            sort = CatalogSort.Latest,
            descending = false,
            genres = setOf("Fantasy"),
            ongoingOnly = true,
            pluginValues = mapOf("kind" to listOf("novel")),
        )

        val applied = filters.withPluginDraft(
            schema,
            pluginFilterDefaults(schema).withSelect(select, "novel"),
        )

        assertEquals("dragon", applied.query)
        assertEquals(CatalogFilters().sort, applied.sort)
        assertTrue(applied.descending)
        assertTrue(applied.genres.isEmpty())
        assertEquals(false, applied.ongoingOnly)
        assertEquals(mapOf("kind" to listOf("novel")), applied.pluginValues)
    }

    @Test
    fun `an empty schema keeps applied plugin values instead of wiping them`() {
        val filters = CatalogFilters(pluginValues = mapOf("kind" to listOf("novel")))

        assertEquals(filters.pluginValues, filters.withPluginDraft(emptyList(), emptyMap()).pluginValues)
    }

    @Test
    fun `sort keeps one direction while options change and flips on re-tap`() {
        val draft = pluginFilterDefaults(schema)

        val next = draft
            .withSort(sort, "rating")
            .toggleSortDirection(sort)
            .withSort(sort, "new")

        assertEquals(PluginFilterValue.Sort("new", true), next["order"])
    }

    @Test
    fun `checkbox honours multiselect and single-select semantics`() {
        val multi = pluginFilterDefaults(schema)
            .toggleCheckbox(checkbox, "action")
            .toggleCheckbox(checkbox, "fantasy")
        assertEquals(
            PluginFilterValue.Checkbox(setOf("action", "fantasy")),
            multi["genre"],
        )

        val single = pluginFilterDefaults(schema)
            .toggleCheckbox(singleCheckbox, "action")
            .toggleCheckbox(singleCheckbox, "fantasy")
        assertEquals(PluginFilterValue.Checkbox(setOf("fantasy")), single["genre"])

        val cleared = single.toggleCheckbox(singleCheckbox, "fantasy")
        assertEquals(PluginFilterValue.Checkbox(emptySet()), cleared["genre"])
    }

    @Test
    fun `tristate cycles neutral to include to exclude to neutral`() {
        var draft = pluginFilterDefaults(schema)

        draft = draft.cycleTristate(tristate, "ongoing")
        assertEquals(
            PluginFilterValue.Tristate(mapOf("ongoing" to TriState.Include)),
            draft["status"],
        )

        draft = draft.cycleTristate(tristate, "ongoing")
        assertEquals(
            PluginFilterValue.Tristate(mapOf("ongoing" to TriState.Exclude)),
            draft["status"],
        )

        draft = draft.cycleTristate(tristate, "ongoing")
        assertEquals(PluginFilterValue.Tristate(emptyMap()), draft["status"])
    }

    @Test
    fun `tags toggle and keep insertion order`() {
        val draft = pluginFilterDefaults(schema)
            .toggleTag(tags, " isekai ")
            .toggleTag(tags, "custom")

        assertEquals(
            PluginFilterValue.Tags(listOf("isekai", "custom")),
            draft["tags"],
        )

        assertEquals(
            PluginFilterValue.Tags(listOf("custom")),
            draft.toggleTag(tags, "isekai")["tags"],
        )
    }
}
