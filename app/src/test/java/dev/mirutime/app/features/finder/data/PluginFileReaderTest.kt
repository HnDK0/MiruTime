package dev.mirutime.app.features.finder.data

import dev.mirutime.app.features.finder.domain.PluginFileRejection
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginFileReaderTest {

    @Test
    fun `reads a valid stream`() {
        val bytes = "id = \"demo\"\n".toByteArray()

        val result = readPluginStream(ByteArrayInputStream(bytes), maxBytes = 1024)

        assertArrayEquals(bytes, (result as PluginFileRead.Ok).bytes)
    }

    @Test
    fun `empty and oversized streams are rejected without allocating them`() {
        assertEquals(
            PluginFileRejection.EMPTY,
            (readPluginStream(ByteArrayInputStream(ByteArray(0))) as PluginFileRead.Rejected).reason,
        )
        val nine = ByteArrayInputStream(ByteArray(9) { 'a'.code.toByte() })
        assertEquals(
            PluginFileRejection.TOO_LARGE,
            (readPluginStream(nine, maxBytes = 8) as PluginFileRead.Rejected).reason,
        )
    }

    @Test
    fun `io failure maps to unreadable`() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("gone")
        }

        assertEquals(
            PluginFileRejection.UNREADABLE,
            (readPluginStream(broken) as PluginFileRead.Rejected).reason,
        )
    }

    @Test
    fun `only lua extensions are accepted`() {
        assertTrue(isLuaFileName("plugin.lua"))
        assertTrue(isLuaFileName("PLUGIN.LUA"))
        assertTrue(isLuaFileName(" plugin.Lua "))
        assertFalse(isLuaFileName("plugin.zip"))
        assertFalse(isLuaFileName("lua"))
    }
}
