package dev.mirutime.imageloader

import android.content.ComponentCallbacks2

// What to do with the Coil memory cache for one onTrimMemory level. Pure on
// purpose: the Application callback only reads and applies the result, and the
// mapping is unit-tested without an Activity.
sealed interface MemoryTrim {
    data object None : MemoryTrim
    data object Clear : MemoryTrim
    data class Trim(val bytes: Long) : MemoryTrim
}

// The level fields are marked deprecated in newer SDKs because the platform
// stopped emitting some of them, but OEM and older releases still deliver
// them and the values are stable — the mapping keeps them on purpose.
@Suppress("DEPRECATION")
object ImageMemoryTrimPolicy {
    // Levels arrive in this order:
    // RUNNING_MODERATE(5) < RUNNING_LOW(10) < RUNNING_CRITICAL(15)
    // < UI_HIDDEN(20) < BACKGROUND(40) < MODERATE(60) < COMPLETE(80).
    //
    // UI_HIDDEN trims to half instead of clearing: clearing on every trip to
    // the background makes the next foreground re-decode the whole first
    // screen. RUNNING_CRITICAL also trims to half — the system is already
    // asking for memory urgently while the app is visible. COMPLETE (process
    // may not come back) clears.
    //
    // trimToSize is an LRU cut, not an immediate free: entries leave the cache
    // now, their bitmaps are collected once no composition holds them.
    fun forLevel(currentBytes: Long, level: Int): MemoryTrim = when {
        currentBytes <= 0L -> MemoryTrim.None
        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryTrim.Clear
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryTrim.Trim(currentBytes / 2)
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryTrim.Trim(currentBytes / 2)
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryTrim.Trim(currentBytes * 3 / 4)
        else -> MemoryTrim.None
    }
}
