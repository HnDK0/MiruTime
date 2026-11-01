package dev.mirutime.imageloader

import android.content.Context
import android.content.pm.ApplicationInfo
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.CacheStrategy
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.NetworkFetcher
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath

// The app's single Coil instance, with the cache bounds in one place. Every
// image goes through [fetch] (the plugin image lane), so the WebView cookies
// and per-host headers apply; the caller resolves that lane on demand, never
// during the first frame.
@OptIn(ExperimentalCoilApi::class)
fun buildImageLoader(context: Context, fetch: ImageFetchPort): ImageLoader =
    ImageLoader.Builder(context)
        .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(8))
        .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(3))
        .allowHardware(ImageCacheConfig.HARDWARE_BITMAPS)
        .allowRgb565(ImageCacheConfig.RGB565)
        .components {
            add(
                NetworkFetcher.Factory(
                    networkClient = { PluginImageNetworkClient(fetch) },
                    cacheStrategy = { RevalidatingImageCacheStrategy },
                    concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
                ),
            )
        }
        .diskCache(
            DiskCache.Builder()
                .directory(ImageCacheConfig.cacheDir(context.filesDir).absolutePath.toPath())
                .maxSizeBytes(ImageCacheConfig.DISK_CACHE_BYTES)
                .build(),
        )
        .memoryCache(
            MemoryCache.Builder()
                .maxSizePercent(context, ImageCacheConfig.MEMORY_CACHE_PERCENT)
                .build(),
        )
        .crossfade(ImageCacheConfig.CROSSFADE_MS)
        .apply { if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) logger(DebugLogger()) }
        .build()

// Coil parity with NoveLA: an error page or AVIF body must never become a
// permanent cache entry. 404s were exactly why hitomi covers stayed broken
// after the URL itself was fixed: the retry re-read the poisoned record.
@OptIn(ExperimentalCoilApi::class)
private object RevalidatingImageCacheStrategy : CacheStrategy {

    override suspend fun read(
        cacheResponse: NetworkResponse,
        networkRequest: NetworkRequest,
        options: Options,
    ): CacheStrategy.ReadResult =
        if (ImageCacheConfig.isCacheableStatus(cacheResponse.code) && ImageCacheConfig.isCacheableContentType(cacheResponse.headers["content-type"])) {
            CacheStrategy.ReadResult(cacheResponse)
        } else {
            CacheStrategy.ReadResult(networkRequest)
        }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult {
        if (ImageCacheConfig.isCacheableStatus(networkResponse.code) && ImageCacheConfig.isCacheableContentType(networkResponse.headers["content-type"])) {
            return CacheStrategy.WriteResult(networkResponse)
        }
        // DISABLED while a snapshot is open leaks its FD; copy(body=null) closes
        // the snapshot without writing the unusable body.
        return if (cacheResponse != null) {
            CacheStrategy.WriteResult(networkResponse.copy(body = null))
        } else {
            CacheStrategy.WriteResult.DISABLED
        }
    }
}
