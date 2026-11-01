package dev.mirutime.app.design

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.mirutime.app.di.host.AndroidSystemInfo
import dev.mirutime.core.log.MiruLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

// === Ready-made backgrounds (GitHub) ===
// The stock pictures live in the app's repository, folder `testBackgrounds`.
// They are NOT bundled in the APK: the settings picker shows the cached
// listing immediately, fetches only when the user taps Refresh, and downloads
// a picture only when its tile is tapped. Presets are applied IN PLACE from
// filesDir/preset_backgrounds — they are never copied into the user's saved
// backgrounds library, so the same picture cannot appear twice.
//
// Listing, in order of preference:
//   1. GitHub contents API (any file added to the folder shows up with no app
//      update; unauthenticated limit is 60 req/h per IP, hence the cache);
//   2. raw.githubusercontent.com probing of N_Backgrounds.jpeg (N = 1, 2, …
//      until the first miss) when the API is rate-limited or blocked;
//   3. the last good listing from prefs when offline.
object PresetBackgroundSource {
    const val Repo = "HnDK0/MiruTime"
    const val Branch = "main"
    const val Folder = "testBackgrounds"
    const val ApiList = "https://api.github.com/repos/$Repo/contents/$Folder?ref=$Branch"
    const val RawBase = "https://raw.githubusercontent.com/$Repo/$Branch/$Folder"
    const val ProbeSuffix = "_Backgrounds.jpeg"
    const val ProbeLimit = 40
}

data class PresetBackground(val name: String, val url: String)

// URL is the preset's identity; a GitHub listing can repeat an entry.
internal fun dedupePresets(items: List<PresetBackground>): List<PresetBackground> =
    items.distinctBy { it.url }

// Saved backgrounds are keyed by their stored path/source.
internal fun dedupeBackgroundPaths(items: List<String>): List<String> = items.distinct()

private const val PREFS = "miru_theme"
private const val KEY_PRESETS = "preset_bg_list"
private const val KEY_PRESETS_AT = "preset_bg_list_at"
private const val PRESET_MAX_BYTES = 25 * 1024 * 1024
private const val PRESET_THUMB_SIDE = 360
private val ImageExt = Regex(".*\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)
private val HttpDate: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC)

private fun httpOpen(url: String, method: String = "GET", accept: String? = null): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 8000
        readTimeout = 12000
        instanceFollowRedirects = true
        // api.github.com rejects requests without a User-Agent; one shared UA.
        setRequestProperty("User-Agent", AndroidSystemInfo.defaultUserAgent)
        accept?.let { setRequestProperty("Accept", it) }
    }

private fun leadingNumber(name: String): Int? =
    name.takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()

private fun sortPresets(items: List<PresetBackground>): List<PresetBackground> =
    items.sortedWith(compareBy({ leadingNumber(it.name) ?: Int.MAX_VALUE }, { it.name }))

private fun fetchViaApi(): List<PresetBackground>? = try {
    val conn = httpOpen(PresetBackgroundSource.ApiList, accept = "application/vnd.github+json")
    if (conn.responseCode != 200) {
        null
    } else {
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val array = JSONArray(body)
        val items = buildList {
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val name = entry.optString("name")
                if (entry.optString("type") != "file" || !ImageExt.matches(name)) continue
                val url = entry.optString("download_url").takeIf { it.startsWith("http") }
                    ?: "${PresetBackgroundSource.RawBase}/$name"
                add(PresetBackground(name, url))
            }
        }
        sortPresets(items).takeIf { it.isNotEmpty() }
    }
} catch (_: Exception) {
    null
}

private fun probeRawNames(): List<PresetBackground>? = try {
    val found = mutableListOf<PresetBackground>()
    for (n in 1..PresetBackgroundSource.ProbeLimit) {
        val name = "$n${PresetBackgroundSource.ProbeSuffix}"
        val url = "${PresetBackgroundSource.RawBase}/$name"
        val code = httpOpen(url, method = "HEAD").responseCode
        if (code != 200) break
        found += PresetBackground(name, url)
    }
    found.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}

private fun readCachedPresets(context: Context): Pair<List<PresetBackground>, Long> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val json = prefs.getString(KEY_PRESETS, null) ?: return emptyList<PresetBackground>() to 0L
    val items = try {
        val array = JSONArray(json)
        List(array.length()) {
            val o = array.getJSONObject(it)
            PresetBackground(o.getString("name"), o.getString("url"))
        }
    } catch (_: Exception) {
        emptyList()
    }
    return items to prefs.getLong(KEY_PRESETS_AT, 0L)
}

private fun writeCachedPresets(context: Context, items: List<PresetBackground>) {
    val array = JSONArray()
    items.forEach { array.put(JSONObject().put("name", it.name).put("url", it.url)) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_PRESETS, array.toString())
        .putLong(KEY_PRESETS_AT, System.currentTimeMillis())
        .apply()
}

private const val PRESET_DOWNLOAD_CONCURRENCY = 4
private const val PRESET_FILE_TIMEOUT_MS = 30_000L

data class PresetRefreshResult(
    val presets: List<PresetBackground>,
    val downloaded: Int,
    val failed: Int,
    val listingFetched: Boolean,
)

// Opening the picker reads the cache only: no listing fetch, no image
// download, even when the cache is old. A refresh is an explicit user action.
suspend fun loadCachedPresetBackgrounds(context: Context): List<PresetBackground> =
    withContext(Dispatchers.IO) {
        migrateLegacyPresetDir(context)
        val (cached, _) = readCachedPresets(context)
        cleanPresetCopies(context, cached)
        loadPresetListing(cached, refresh = false, fetch = ::fetchPresetListing).presets
    }

// One explicit refresh: a fresh listing replaces the cache, then every picture
// is pulled into the durable preset folder with bounded concurrency. A failed
// picture or listing never throws and never stops the rest.
suspend fun refreshPresetBackgrounds(context: Context): PresetRefreshResult =
    withContext(Dispatchers.IO) {
        migrateLegacyPresetDir(context)
        val (cached, _) = readCachedPresets(context)
        loadPresetListing(
            cached = cached,
            refresh = true,
            fetch = ::fetchPresetListing,
            save = { writeCachedPresets(context, it) },
            // Conditional GET: an unchanged cached picture is kept without
            // transferring the body, a stale one (e.g. an old placeholder
            // committed under the same name) is re-downloaded.
            download = { preset ->
                downloadPresetFile(preset, presetFile(context, preset), conditional = true) != null
            },
        ).also { result ->
            // A refreshed file may replace a stale cached picture; the old
            // decoded thumb must not survive.
            synchronized(cachedPreviewCache) { cachedPreviewCache.clear() }
            cleanPresetCopies(context, result.presets)
            MiruLog.event(
                "theme",
                "preset_refresh",
                "total" to result.presets.size,
                "downloaded" to result.downloaded,
                "failed" to result.failed,
            )
        }
    }

private suspend fun fetchPresetListing(): List<PresetBackground>? {
    val viaApi = fetchViaApi()
    val loaded = viaApi ?: probeRawNames()
    MiruLog.event(
        "theme",
        "preset_listing_fetch",
        "api" to (viaApi != null),
        "count" to (loaded?.size ?: 0),
    )
    return loaded
}

// The shared decision: cached-only never invokes the fetcher or the downloader;
// a refresh keeps the cache when the listing fails, otherwise downloads every
// picture (unchanged ones are revalidated and kept by the downloader).
internal suspend fun loadPresetListing(
    cached: List<PresetBackground>,
    refresh: Boolean,
    fetch: suspend () -> List<PresetBackground>?,
    save: (List<PresetBackground>) -> Unit = {},
    download: suspend (PresetBackground) -> Boolean = { true },
): PresetRefreshResult {
    if (!refresh) {
        return PresetRefreshResult(cached, downloaded = 0, failed = 0, listingFetched = false)
    }
    val loaded = fetch()?.let(::dedupePresets)
        ?: return PresetRefreshResult(cached, downloaded = 0, failed = 0, listingFetched = false)
    save(loaded)
    val (downloaded, failed) = downloadPresetFiles(loaded, download)
    return PresetRefreshResult(loaded, downloaded, failed, listingFetched = true)
}

// Four pictures in flight at a time; one slow or dead file cannot hang the
// whole refresh beyond its own timeout.
internal suspend fun downloadPresetFiles(
    presets: List<PresetBackground>,
    download: suspend (PresetBackground) -> Boolean,
): Pair<Int, Int> = coroutineScope {
    var downloaded = 0
    var failed = 0
    presets.chunked(PRESET_DOWNLOAD_CONCURRENCY).forEach { chunk ->
        val results = chunk.map { preset ->
            async {
                try {
                    withTimeout(PRESET_FILE_TIMEOUT_MS) { download(preset) }
                } catch (_: TimeoutCancellationException) {
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
        }.awaitAll()
        downloaded += results.count { it }
        failed += results.count { !it }
    }
    downloaded to failed
}

// Old builds kept presets in cacheDir; move that folder once so existing
// downloads survive the upgrade and duplicate cleanup can still see them.
private fun migrateLegacyPresetDir(context: Context) {
    val legacy = File(context.cacheDir, "bg_presets")
    if (!legacy.isDirectory) return
    val target = presetBgDir(context).apply { mkdirs() }
    legacy.listFiles()?.forEach { file ->
        if (!file.isFile || file.length() <= 0 || file.name.endsWith(".tmp")) return@forEach
        val copy = File(target, file.name)
        if (copy.exists()) {
            file.delete()
        } else {
            runCatching {
                file.copyTo(copy, overwrite = false)
                // Keep the old mtime: the conditional refresh uses it to spot
                // a picture that changed in the repository since download.
                copy.setLastModified(file.lastModified())
                file.delete()
            }
        }
    }
    legacy.delete()
}

private fun presetFile(context: Context, preset: PresetBackground) =
    File(presetBgDir(context).apply { mkdirs() }, preset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

// A file already in the preset folder is used as-is; only a missing one starts
// a download. Shared by the refresh and the per-tile tap path.
internal suspend fun presetFileForUse(
    cached: File?,
    download: suspend () -> File?,
): File? = cached ?: download()

// Original bytes of a preset in durable app storage (downloaded once; unlike
// the old cacheDir copy it is not evicted by the OS).
private suspend fun ensurePresetFile(context: Context, preset: PresetBackground): File? =
    withContext(Dispatchers.IO) {
        val target = presetFile(context, preset)
        val cached = target.takeIf { it.exists() && it.length() > 0 }
        presetFileForUse(cached) { downloadPresetFile(preset, target) }
    }

// Applies a ready-made background in place: returns the durable preset file
// path for the theme and never touches the user's bgLibrary.
suspend fun applyPreset(context: Context, preset: PresetBackground): String? =
    withContext(Dispatchers.IO) {
        migrateLegacyPresetDir(context)
        ensurePresetFile(context, preset)?.absolutePath
    }

// Pure duplicate decision for the library cleanup below.
internal fun dedupePresetCopies(
    library: List<String>,
    presetSavedPaths: Set<String>,
    onDuplicate: (String) -> Unit = {},
): List<String> = library.filterNot { path ->
    (path in presetSavedPaths).also { duplicate -> if (duplicate) onDuplicate(path) }
}

// Older builds imported a preset into filesDir/backgrounds, so the same
// picture showed up in both rails. The saved copy is a re-encoded JPEG, so its
// identity is the deterministic path derived from the preset's ORIGINAL bytes
// (the same rule importBackground used). Removes and deletes only those exact
// duplicates; an active duplicate is switched to the in-place preset file.
internal fun cleanPresetCopies(context: Context, presets: List<PresetBackground>) {
    val stored = storedBgLibrary(context)
    if (stored.isEmpty()) return
    val presetPaths = presets.mapNotNull { preset ->
        val file = presetFile(context, preset)
        val digest = file.takeIf { it.exists() && it.length() > 0 }?.let(::fileDigest)
            ?: return@mapNotNull null
        savedBackgroundPathForDigest(context, digest) to file.absolutePath
    }.toMap()
    if (presetPaths.isEmpty()) return
    val (mode, active) = loadThemePrefs(context)
    var activeMoved = false
    val cleaned = dedupePresetCopies(stored, presetPaths.keys) { duplicate ->
        deleteBackgroundFile(context, duplicate)
        if (active == duplicate) {
            saveThemePrefs(context, mode ?: "Custom", presetPaths.getValue(duplicate))
            activeMoved = true
        }
    }
    if (cleaned != stored) saveBgLibrary(context, cleaned)
    if (cleaned != stored || activeMoved) notifyThemePrefsChanged()
}

private fun presetEtagFile(target: File) = File(target.parentFile, target.name + ".etag")

private suspend fun downloadPresetFile(
    preset: PresetBackground,
    target: File,
    conditional: Boolean = false,
): File? {
    val tmp = File(target.parentFile, target.name + ".tmp")
    return try {
        val cached = target.takeIf { conditional && it.exists() && it.length() > 0 }
        val etagFile = presetEtagFile(target)
        val etag = etagFile.takeIf { cached != null && it.isFile }?.readText()?.trim()
        val conn = httpOpen(preset.url)
        when {
            !etag.isNullOrEmpty() -> conn.setRequestProperty("If-None-Match", etag)
            cached != null -> conn.setRequestProperty(
                "If-Modified-Since",
                HttpDate.format(Instant.ofEpochMilli(cached.lastModified())),
            )
        }
        val code = conn.responseCode
        if (cached != null && code == HttpURLConnection.HTTP_NOT_MODIFIED) {
            cached
        } else if (code != 200) {
            MiruLog.event("theme", "preset_download", "ok" to false, "reason" to "status")
            null
        } else {
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > PRESET_MAX_BYTES) {
                            tmp.delete()
                            MiruLog.event("theme", "preset_download", "ok" to false, "reason" to "size")
                            return null
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (tmp.renameTo(target)) {
                val freshEtag = conn.getHeaderField("ETag")
                runCatching {
                    if (freshEtag.isNullOrBlank()) etagFile.delete() else etagFile.writeText(freshEtag)
                }
                MiruLog.event("theme", "preset_download", "ok" to true)
                target
            } else {
                tmp.delete()
                null
            }
        }
    } catch (e: CancellationException) {
        tmp.delete()
        throw e
    } catch (_: Exception) {
        tmp.delete()
        MiruLog.event("theme", "preset_download", "ok" to false, "reason" to "error")
        null
    }
}

// A preset tile preview: a small thumb plus the in-place preset path (so the
// tile can show the "active" ring). Only a file already in the preset folder
// is decoded — composition never starts a download. The revision re-runs the
// local check after a refresh or a successful tap.
class CachedPresetPreview(val thumb: ImageBitmap, val savedPath: String)

private val cachedPreviewCache = mutableMapOf<String, CachedPresetPreview>()

@Composable
fun rememberCachedPresetPreview(
    preset: PresetBackground,
    revision: Int = 0,
): CachedPresetPreview? {
    val context = LocalContext.current.applicationContext
    var preview by remember(preset.url, revision) {
        mutableStateOf(synchronized(cachedPreviewCache) { cachedPreviewCache[preset.url] })
    }
    LaunchedEffect(preset.url, revision) {
        if (preview != null) return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            val file = presetFile(context, preset).takeIf { it.exists() && it.length() > 0 }
                ?: return@withContext null
            try {
                val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, probe)
                if (probe.outWidth <= 0 || probe.outHeight <= 0) return@withContext null
                var sample = 1
                while (probe.outWidth / sample > PRESET_THUMB_SIDE * 2 ||
                    probe.outHeight / sample > PRESET_THUMB_SIDE * 2
                ) {
                    sample *= 2
                }
                val bitmap = BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@withContext null
                CachedPresetPreview(bitmap.asImageBitmap(), file.absolutePath)
                    .also { synchronized(cachedPreviewCache) { cachedPreviewCache[preset.url] = it } }
            } catch (_: Exception) {
                null
            }
        }
    }
    return preview
}
