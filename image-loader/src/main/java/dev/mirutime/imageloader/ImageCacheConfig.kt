package dev.mirutime.imageloader

import java.io.File

// One place for the image cache bounds: the app has exactly one ImageLoader,
// so these numbers are the whole image-cache budget.
object ImageCacheConfig {
    const val DIR_NAME = "image_cache"
    // 24 MB is the bound the app shipped with; a change here is a product
    // decision, not a tuning knob. The cache moved from cacheDir to filesDir,
    // so the old installd cache-quota reason no longer binds, but the size and
    // the behaviour it produced stay identical.
    const val DISK_CACHE_BYTES = 24L * 1024 * 1024
    const val MEMORY_CACHE_PERCENT = 0.25
    const val CROSSFADE_MS = 300
    // 2 bytes/pixel instead of 4: twice as many covers in the memory cache.
    const val RGB565 = true
    const val HARDWARE_BITMAPS = true

    // Durable on purpose: a cold start in airplane mode must still render the
    // covers/icons the last session loaded.
    fun cacheDir(filesDir: File): File = File(filesDir, DIR_NAME)

    // Only 2xx bodies belong in the image cache: a cached 404/text placeholder
    // would otherwise outlive every retry.
    fun isCacheableStatus(code: Int): Boolean = code in 200..299

    // text/* is usually a CDN block/error page; AVIF may be undecodable on the
    // device. Neither is a usable cover, so neither belongs in the image cache.
    fun isCacheableContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim() ?: return true
        return !type.startsWith("text/", ignoreCase = true) &&
            !type.equals("image/avif", ignoreCase = true)
    }
}
