package dev.mirutime.app.features.finder.data

import dev.mirutime.app.di.InstalledPlugin
import dev.mirutime.app.di.RepoCatalog
import dev.mirutime.app.features.library.domain.ContentType
import dev.mirutime.core.api.ContentType as CoreContentType
import dev.mirutime.core.installer.IndexEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeExtensionsTest {

    @Test
    fun `installed and repository-only entries merge`() {
        val merged = mergeExtensions(
            installed = listOf(plugin("alpha", version = "1.0.0")),
            repository = listOf(
                entry(
                    "alpha",
                    name = "Alpha Repo",
                    version = "2.0.0",
                    icon = "  https://cdn.example/alpha.png  ",
                ),
                entry(
                    "beta",
                    name = "Beta",
                    version = "3.1.0",
                    contentType = "manga",
                    language = "ru",
                    icon = "https://cdn.example/beta.png",
                ),
            ),
        )

        assertEquals(listOf("alpha", "beta"), merged.map { it.id })
        val alpha = merged.first { it.id == "alpha" }
        assertEquals("Alpha", alpha.name)
        assertEquals("1.0.0", alpha.installedVersion)
        assertEquals("2.0.0", alpha.availableVersion)
        assertEquals("https://cdn.example/alpha.png", alpha.iconUrl)
        assertTrue(alpha.updateAvailable)
        val beta = merged.first { it.id == "beta" }
        assertNull(beta.installedVersion)
        assertEquals("RU", beta.language)
        assertEquals(ContentType.Manga, beta.type)
        assertEquals("https://cdn.example/beta.png", beta.iconUrl)
        assertFalse(beta.installed)
    }

    @Test
    fun `installed wins on duplicate id`() {
        val merged = mergeExtensions(
            installed = listOf(plugin("alpha")),
            repository = listOf(entry("alpha", name = "Repo Alpha", version = "9.9.9")),
        )

        assertEquals(1, merged.size)
        assertEquals("Alpha", merged.single().name)
        assertEquals("1.0.0", merged.single().installedVersion)
    }

    @Test
    fun `missing index version falls back to installed version`() {
        val merged = mergeExtensions(
            installed = listOf(plugin("alpha", version = "1.2.3")),
            repository = listOf(entry("alpha", version = null)),
        )

        assertEquals("1.2.3", merged.single().availableVersion)
        assertFalse(merged.single().updateAvailable)
    }

    @Test
    fun `local rows survive without a repository catalog`() {
        val merged = mergeExtensions(
            installed = listOf(plugin("alpha", version = "1.2.3")),
            repository = emptyList(),
        )

        val alpha = merged.single()
        assertEquals("alpha", alpha.id)
        assertEquals("1.2.3", alpha.installedVersion)
        assertEquals("1.2.3", alpha.availableVersion)
        assertFalse(alpha.repoAvailable)
        assertFalse(alpha.updateAvailable)
    }

    @Test
    fun `failed refresh keeps cached repository rows`() {
        val cached = RepoCatalog(entries = listOf(entry("beta", version = "3.0.0")))

        val entries = refreshRepoEntries(
            fresh = RepoCatalog(error = "offline"),
            cached = cached,
        )

        assertEquals(listOf("beta"), entries.map { it.id })
    }

    @Test
    fun `partial refresh wins over the cached catalog`() {
        val cached = RepoCatalog(entries = listOf(entry("beta", version = "3.0.0")))

        val entries = refreshRepoEntries(
            fresh = RepoCatalog(entries = listOf(entry("beta", version = "4.0.0")), error = "ru: offline"),
            cached = cached,
        )

        assertEquals("4.0.0", entries.single().version)
    }

    private fun plugin(id: String, version: String = "1.0.0") = InstalledPlugin(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        language = "en",
        version = version,
        contentType = CoreContentType.NOVEL,
        enabled = true,
        error = null,
    )

    private fun entry(
        id: String,
        name: String? = id,
        version: String? = "1.0.0",
        contentType: String? = null,
        language: String? = null,
        icon: String? = null,
    ) = IndexEntry(
        id = id,
        name = name,
        version = version,
        url = "https://example.com/$id.lua",
        sha256 = null,
        contentType = contentType,
        language = language,
        icon = icon,
    )
}
