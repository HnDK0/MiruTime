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
import dev.mirutime.app.core.AppCacheConfig
import dev.mirutime.app.di.host.AndroidSystemInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// === Ready-made backgrounds (GitHub) ===
// The stock pictures live in the app's repository, folder `testBackgrounds`.
// They are NOT bundled in the APK: the settings picker lists them on demand,
// downloads a preview, and importing one goes through the SAME path as a
// picked file (importBackground), so a chosen preset becomes a normal saved
// background — offline-safe, de-duplicated, deletable.
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

// Fresh listing (cached for 12h), a stale one when the network is down, an
// empty list only when nothing was ever loaded. Never throws.
suspend fun loadPresetBackgrounds(context: Context, force: Boolean = false): List<PresetBackground> =
    withContext(Dispatchers.IO) {
        val (cached, savedAt) = readCachedPresets(context)
        val fresh = AppCacheConfig.isFresh(savedAt, AppCacheConfig.BACKGROUND_PRESET_TTL_MS)
        if (!force && cached.isNotEmpty() && fresh) return@withContext cached
        val loaded = fetchViaApi() ?: probeRawNames()
        if (loaded != null) {
            writeCachedPresets(context, loaded)
            loaded
        } else {
            cached
        }
    }

private fun presetDir(context: Context) = File(context.cacheDir, "bg_presets").apply { mkdirs() }

private fun presetFile(context: Context, preset: PresetBackground) =
    File(presetDir(context), preset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))

// Original bytes of a preset in the cache dir (downloaded once, the OS may
// clear the cache — then it is simply fetched again).
private suspend fun ensurePresetFile(context: Context, preset: PresetBackground): File? =
    withContext(Dispatchers.IO) {
        val target = presetFile(context, preset)
        if (target.exists() && target.length() > 0) return@withContext target
        try {
            val conn = httpOpen(preset.url)
            if (conn.responseCode != 200) return@withContext null
            val tmp = File(target.parentFile, target.name + ".tmp")
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
                            return@withContext null
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (tmp.renameTo(target)) target else null
        } catch (_: Exception) {
            null
        }
    }

// Picking a preset = download (if not yet) + the normal import into app
// storage. Returns the saved local path, null when the download failed.
suspend fun importPresetBackground(context: Context, preset: PresetBackground): String? {
    val file = ensurePresetFile(context, preset) ?: return null
    return importBackground(context, file.absolutePath)
}

// What a preset tile needs: a small preview and the path the picture will
// have once imported (so the tile can show the "active" ring).
class PresetPreview(val thumb: ImageBitmap, val savedPath: String)

private val previewCache = mutableMapOf<String, PresetPreview>()

@Composable
fun rememberPresetPreview(preset: PresetBackground): PresetPreview? {
    val context = LocalContext.current.applicationContext
    var preview by remember(preset.url) {
        mutableStateOf(synchronized(previewCache) { previewCache[preset.url] })
    }
    LaunchedEffect(preset.url) {
        if (preview != null) return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            val file = ensurePresetFile(context, preset) ?: return@withContext null
            try {
                val bytes = file.readBytes()
                val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
                if (probe.outWidth <= 0 || probe.outHeight <= 0) return@withContext null
                var sample = 1
                while (probe.outWidth / sample > PRESET_THUMB_SIDE * 2 ||
                    probe.outHeight / sample > PRESET_THUMB_SIDE * 2
                ) {
                    sample *= 2
                }
                val bitmap = BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@withContext null
                PresetPreview(bitmap.asImageBitmap(), savedBackgroundPath(context, bytes))
                    .also { synchronized(previewCache) { previewCache[preset.url] = it } }
            } catch (_: Exception) {
                null
            }
        }
    }
    return preview
}
