package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.PluginSetting
import dev.mirutime.core.api.BookMeta
import dev.mirutime.core.api.CatalogPage as CoreCatalogPage
import dev.mirutime.core.api.Chapter
import dev.mirutime.core.api.ChapterContent
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.Outcome
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.api.PluginMetadata
import dev.mirutime.core.api.PluginSource
import dev.mirutime.core.api.getOrNull
import dev.mirutime.core.host.KeyValueStore
import dev.mirutime.core.host.PLUGIN_PREFERENCES_NAMESPACE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSettingsLoaderTest {

    private fun settings(result: Outcome<List<PluginSetting>>): List<PluginSetting> =
        (result as Outcome.Ok).value

    private val ranobes = listOf(
        mapOf(
            "key" to "ranobes_chapter_list",
            "type" to "select",
            "label" to "Chapter list (re-add books after changing)",
            "current" to "full",
            "options.count" to "2",
            "options.0.value" to "full",
            "options.0.label" to "Full — real titles",
            "options.1.value" to "lite",
            "options.1.label" to "Lite — numbered chapters",
        ),
    )

    // tomatomtl declares a static `current`: only the stored value can tell
    // what the user picked.
    private val tomatomtl = listOf(
        mapOf(
            "key" to "tomatomtl_mode",
            "type" to "select",
            "label" to "Translation mode",
            "current" to "google",
            "options.count" to "3",
            "options.0.value" to "raw",
            "options.0.label" to "Raw (Chinese)",
            "options.1.value" to "google",
            "options.1.label" to "Google Translate (fast)",
            "options.2.value" to "cina",
            "options.2.label" to "CinaNMT",
        ),
    )

    private fun select(settings: List<PluginSetting>, index: Int = 0): PluginSetting.Select =
        settings[index] as PluginSetting.Select

    @Test
    fun `the namespace is the one get_preference and set_preference use`() {
        assertEquals("lua_preferences", PLUGIN_PREFERENCES_NAMESPACE)
    }

    @Test
    fun `a plugin that is not loaded is an error, not an empty schema`() = runBlocking {
        val result = loadPluginSettings("ranobes", source = null, store = MemoryStore())

        assertTrue(result is Outcome.Err)
    }

    @Test
    fun `a plugin without settings gives an empty list`() = runBlocking {
        val source = fakeSource(Outcome.Ok(emptyList()))

        assertEquals(
            emptyList<PluginSetting>(),
            settings(loadPluginSettings("ranobes", source, MemoryStore())),
        )
    }

    @Test
    fun `a failing getSettingsSchema is an error, never a throw`() = runBlocking {
        val source = fakeSource(Outcome.Err(PluginError.ScriptError("boom")))

        val result = loadPluginSettings("ranobes", source, MemoryStore())

        assertTrue(result is Outcome.Err)
    }

    @Test
    fun `nothing stored shows the plugin's own current`() = runBlocking {
        val settings = settings(
            loadPluginSettings("ranobes", fakeSource(Outcome.Ok(ranobes)), MemoryStore()),
        )

        val chapterList = select(settings)
        assertEquals("ranobes_chapter_list", chapterList.key)
        assertEquals("full", chapterList.current())
        assertEquals(listOf("full", "lite"), chapterList.options.map { it.key })
    }

    @Test
    fun `the stored value wins over a static plugin current`() = runBlocking {
        val store = MemoryStore()
        store.set(PLUGIN_PREFERENCES_NAMESPACE, "tomatomtl_mode", "raw")

        val settings = settings(
            loadPluginSettings("tomatomtl", fakeSource(Outcome.Ok(tomatomtl)), store),
        )

        assertEquals("raw", select(settings).current())
    }

    @Test
    fun `a stored value that is no longer an option falls back to the plugin current`() = runBlocking {
        val store = MemoryStore()
        store.set(PLUGIN_PREFERENCES_NAMESPACE, "tomatomtl_mode", "removed-in-an-update")

        val settings = settings(
            loadPluginSettings("tomatomtl", fakeSource(Outcome.Ok(tomatomtl)), store),
        )

        assertEquals("google", select(settings).current())
    }

    // wtrlab's getMode() echoes whatever is stored, so a retired value comes
    // back as the plugin's `current` too. Both are invalid: the first option is
    // shown and the stale entry is left alone, so it is visible nowhere and one
    // tap replaces it.
    @Test
    fun `a retired value echoed by the plugin shows the first option and writes nothing`() = runBlocking {
        val wtrlab = listOf(
            mapOf(
                "key" to "wtrlab_mode",
                "type" to "select",
                "current" to "legacy",
                "options.count" to "3",
                "options.0.value" to "ai",
                "options.0.label" to "AI (Beta)",
                "options.1.value" to "raw",
                "options.1.label" to "Raw (Web)",
                "options.2.value" to "webplus",
                "options.2.label" to "Web+ (Beta)",
            ),
        )
        val store = MemoryStore()
        store.set(PLUGIN_PREFERENCES_NAMESPACE, "wtrlab_mode", "legacy")

        val settings = settings(loadPluginSettings("wtr-lab", fakeSource(Outcome.Ok(wtrlab)), store))

        assertEquals("ai", select(settings).current())
        assertEquals("legacy", store.get(PLUGIN_PREFERENCES_NAMESPACE, "wtrlab_mode"))
    }

    @Test
    fun `picking an option writes the entry the plugin reads and reports the plugin`() = runBlocking {
        val store = MemoryStore()
        val changed = mutableListOf<String>()
        val settings = settings(
            loadPluginSettings(
                sourceId = "ranobes",
                source = fakeSource(Outcome.Ok(ranobes)),
                store = store,
                onChanged = { changed += it },
            ),
        )

        select(settings).onChange("lite")

        // Exactly what get_preference("ranobes_chapter_list") returns in Lua.
        assertEquals("lite", store.get("lua_preferences", "ranobes_chapter_list"))
        // The same Select reads it back: current() is not a snapshot taken
        // when the sheet opened.
        assertEquals("lite", select(settings).current())
        assertEquals(listOf("ranobes"), changed)
    }

    @Test
    fun `a pick survives reopening the sheet`() = runBlocking {
        val store = MemoryStore()
        val source = fakeSource(Outcome.Ok(ranobes))

        select(settings(loadPluginSettings("ranobes", source, store))).onChange("lite")
        val reopened = settings(loadPluginSettings("ranobes", source, store))

        assertEquals("lite", select(reopened).current())
    }

    @Test
    fun `nothing is written until the user picks`() = runBlocking {
        val store = MemoryStore()

        loadPluginSettings("ranobes", fakeSource(Outcome.Ok(ranobes)), store)

        assertTrue(store.entries.isEmpty())
    }

    // Picking the value that is already stored must not look like a change:
    // the caller drops the plugin's filter schema on every onChanged.
    @Test
    fun `re-picking the stored value writes once`() = runBlocking {
        val store = MemoryStore()
        val changed = mutableListOf<String>()
        val setting = select(
            settings(
                loadPluginSettings(
                    sourceId = "ranobes",
                    source = fakeSource(Outcome.Ok(ranobes)),
                    store = store,
                    onChanged = { changed += it },
                ),
            ),
        )

        setting.onChange("lite")
        setting.onChange("lite")

        assertEquals(listOf("ranobes"), changed)
        assertEquals("lite", store.get(PLUGIN_PREFERENCES_NAMESPACE, "ranobes_chapter_list"))
    }

    @Test
    fun `fake provider exposes the wtrlab settings through the suspend contract`() = runBlocking {
        val provider = FakeFinderDataProvider()

        assertEquals(
            listOf("wtrlab_mode", "wtrlab_chapter_delay"),
            settings(provider.settingsSchema("wtr-lab")).map { it.key },
        )
        assertEquals(emptyList<PluginSetting>(), settings(provider.settingsSchema("local-books")))
    }

    // A preview has no PluginGraph: the fake keeps values under the real
    // namespace, so a pick behaves like it does with a live plugin.
    @Test
    fun `the fake provider persists a pick in the plugin preference namespace`() = runBlocking {
        val provider = FakeFinderDataProvider()
        val mode = select(settings(provider.settingsSchema("wtr-lab")))

        mode.onChange("raw")

        assertEquals("raw", select(settings(provider.settingsSchema("wtr-lab"))).current())
        assertEquals("raw", mode.current())
    }

    private class MemoryStore : KeyValueStore {
        val entries = mutableMapOf<String, String>()

        override fun get(namespace: String, key: String): String? = entries["$namespace:$key"]

        override fun set(namespace: String, key: String, value: String) {
            entries["$namespace:$key"] = value
        }

        override fun remove(namespace: String, key: String) {
            entries.remove("$namespace:$key")
        }
    }

    private fun fakeSource(
        schema: Outcome<List<Map<String, String>>>,
    ): PluginSource = object : PluginSource {
        override val metadata = PluginMetadata(
            id = "alpha",
            name = "Alpha",
            baseUrl = "https://example.com",
            contentType = ContentType.NOVEL,
            language = "en",
            api = 1,
            version = "1.0.0",
            sha256 = "",
        )

        override suspend fun catalog(index: Int): Outcome<CoreCatalogPage> =
            Outcome.Ok(CoreCatalogPage(emptyList(), hasNext = false))

        override suspend fun search(index: Int, query: String): Outcome<CoreCatalogPage> =
            Outcome.Ok(CoreCatalogPage(emptyList(), hasNext = false))

        override suspend fun catalogFiltered(
            index: Int,
            filters: Map<String, List<String>>,
        ): Outcome<CoreCatalogPage> = Outcome.Ok(CoreCatalogPage(emptyList(), hasNext = false))

        override suspend fun book(url: String): Outcome<BookMeta> =
            Outcome.Err(PluginError.ScriptError("unused"))

        override suspend fun chapters(url: String): Outcome<List<Chapter>> =
            Outcome.Err(PluginError.ScriptError("unused"))

        override suspend fun chapterContent(html: String, url: String): Outcome<ChapterContent> =
            Outcome.Err(PluginError.ScriptError("unused"))

        override suspend fun filters(): Outcome<List<Map<String, String>>> = Outcome.Ok(emptyList())

        override suspend fun settingsSchema(): Outcome<List<Map<String, String>>> = schema
    }
}
