package dev.mirutime.app.features.finder.data

import dev.mirutime.app.di.InstalledPlugin
import dev.mirutime.app.features.finder.domain.isWebUrl
import dev.mirutime.core.api.ContentType
import dev.mirutime.core.api.PluginError
import dev.mirutime.core.installer.IndexEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHealthMappingTest {

    private fun plugin(
        id: String = "broken",
        enabled: Boolean = true,
        error: PluginError? = null,
    ) = InstalledPlugin(
        id = id,
        name = "Broken",
        language = "en",
        version = "1.0.0",
        contentType = ContentType.NOVEL,
        enabled = enabled,
        error = error?.toString(),
        errorType = error,
    )

    private fun entry(id: String = "broken") = IndexEntry(
        id = id,
        name = "Broken",
        version = "2.0.0",
        url = "https://example.com/$id.lua",
        sha256 = null,
    )

    @Test
    fun `typed load error reaches the extension and keeps the user switch`() {
        val error = PluginError.ScriptError("unexpected symbol near '<'")

        val extension = mergeExtensions(listOf(plugin(error = error)), emptyList()).single()

        assertEquals(error, extension.error)
        assertTrue(extension.broken)
        // Not usable as a source, but the health sheet still knows the plugin
        // was enabled and can offer Disable.
        assertFalse(extension.enabled)
        assertTrue(extension.enabledByUser)
    }

    @Test
    fun `healthy plugin is unchanged and has no health state`() {
        val extension = mergeExtensions(listOf(plugin(error = null)), emptyList()).single()

        assertNull(extension.error)
        assertFalse(extension.broken)
        assertTrue(extension.enabled)
    }

    @Test
    fun `repo availability gates reset`() {
        val withoutRepo = mergeExtensions(listOf(plugin(error = PluginError.ScriptError("x"))), emptyList())
        assertFalse(withoutRepo.single().repoAvailable)

        val withRepo = mergeExtensions(
            listOf(plugin(error = PluginError.ScriptError("x"))),
            listOf(entry()),
        )
        assertTrue(withRepo.single().repoAvailable)
    }

    @Test
    fun `only http and https auth links are openable`() {
        assertTrue(isWebUrl("https://example.com/login"))
        assertTrue(isWebUrl(" HTTP://example.com "))
        assertFalse(isWebUrl("file:///etc/passwd"))
        assertFalse(isWebUrl("intent://evil"))
        assertFalse(isWebUrl(""))
    }
}
