package dev.mirutime.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostOptionsRegistryTest {

    @Test
    fun `setAuth never steals a host from another owner`() {
        val registry = HostOptionsRegistry()

        registry.setAuth("c.test", "first", "Basic first")
        registry.setAuth("c.test", "second", "Basic second")
        assertEquals("Basic first", registry.auth("c.test"))

        // The owner itself still rotates its own header.
        registry.setAuth("c.test", "first", "Basic rotated")
        assertEquals("Basic rotated", registry.auth("c.test"))

        // Another owner cannot clear it either.
        registry.clear("c.test", "second")
        assertEquals("Basic rotated", registry.auth("c.test"))

        registry.clear("c.test", "first")
        assertNull(registry.auth("c.test"))

        // A freed host accepts the next owner.
        registry.setAuth("c.test", "second", "Basic second")
        assertEquals("Basic second", registry.auth("c.test"))
    }

    @Test
    fun `setReferer never steals a host from another owner`() {
        val registry = HostOptionsRegistry()

        registry.setReferer("c.test", "plugin", "https://site.example/")
        registry.setReferer("c.test", "opds:catalog", "https://c.test/")
        assertEquals("https://site.example/", registry.referer("c.test"))

        // The owner itself still rotates its own value.
        registry.setReferer("c.test", "plugin", "https://site.example/chapter/2")
        assertEquals("https://site.example/chapter/2", registry.referer("c.test"))

        registry.clear("c.test", "opds:catalog")
        assertEquals("https://site.example/chapter/2", registry.referer("c.test"))
    }
}
