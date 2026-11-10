package dev.mirutime.app.features.settings.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupSettingsTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `plugin kv merge keeps existing and lets the archive win on conflicts`() {
        val merged = mergePluginKv(
            existing = mapOf("a:1" to "old", "keep:me" to "yes"),
            backup = mapOf("a:1" to "archive", "b:2" to "new"),
        )

        assertEquals(
            mapOf("a:1" to "archive", "keep:me" to "yes", "b:2" to "new"),
            merged,
        )
    }

    @Test
    fun `folder merge unions without duplicates`() {
        assertEquals(
            setOf("a", "b", "c"),
            mergeFolders(listOf("a", "b"), listOf("b", "c")),
        )
    }

    @Test
    fun `plugin kv backup round-trips`() {
        val backup = PluginKvBackup(
            mapOf(
                "wtr:mode" to "ai",
                "repo:url" to "https://example.test/repo",
                "enabled:p1" to "true",
            ),
        )

        val decoded = json.decodeFromString(
            PluginKvBackup.serializer(),
            json.encodeToString(PluginKvBackup.serializer(), backup),
        )

        assertEquals(backup, decoded)
    }

    @Test
    fun `local sources backup round-trips`() {
        val backup = LocalSourcesBackup(
            books = listOf("content://tree/books"),
            manga = listOf("content://tree/manga"),
            registry = mapOf("id:abc" to "content://tree/file", "uri:abc" to "content://tree/file"),
        )

        val decoded = json.decodeFromString(
            LocalSourcesBackup.serializer(),
            json.encodeToString(LocalSourcesBackup.serializer(), backup),
        )

        assertEquals(backup, decoded)
    }
}
