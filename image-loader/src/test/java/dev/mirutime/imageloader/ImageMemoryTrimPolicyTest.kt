package dev.mirutime.imageloader

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// See ImageMemoryTrimPolicy: the level fields are deprecated in new SDKs but
// still delivered by OEM/older platforms.
@Suppress("DEPRECATION")
class ImageMemoryTrimPolicyTest {

    @Test
    fun `complete clears the cache`() {
        assertEquals(
            MemoryTrim.Clear,
            ImageMemoryTrimPolicy.forLevel(1_000L, ComponentCallbacks2.TRIM_MEMORY_COMPLETE),
        )
    }

    @Test
    fun `ui hidden trims to half`() {
        assertEquals(
            MemoryTrim.Trim(500L),
            ImageMemoryTrimPolicy.forLevel(1_000L, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN),
        )
    }

    @Test
    fun `running critical trims to half`() {
        assertEquals(
            MemoryTrim.Trim(500L),
            ImageMemoryTrimPolicy.forLevel(1_000L, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL),
        )
    }

    @Test
    fun `running low trims to three quarters`() {
        assertEquals(
            MemoryTrim.Trim(750L),
            ImageMemoryTrimPolicy.forLevel(1_000L, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW),
        )
    }

    @Test
    fun `running moderate keeps the cache`() {
        assertEquals(
            MemoryTrim.None,
            ImageMemoryTrimPolicy.forLevel(1_000L, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE),
        )
    }

    @Test
    fun `empty cache is a no-op at every level`() {
        val levels = intArrayOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        for (level in levels) {
            assertEquals(MemoryTrim.None, ImageMemoryTrimPolicy.forLevel(0L, level))
        }
    }

    @Test
    fun `trim target is always smaller than the current size`() {
        for (size in 1L..2_000L) {
            val level = if (size % 2 == 0L) {
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            } else {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            }
            val action = ImageMemoryTrimPolicy.forLevel(size, level)
            assertTrue(action is MemoryTrim.Trim)
            assertTrue((action as MemoryTrim.Trim).bytes < size)
        }
    }
}
