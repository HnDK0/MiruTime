package dev.mirutime.app.design

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
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
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

// Theme background storage: prefs, saved-background library, SAF/file import,
// delete and the decoded-bitmap cache. The sealed ThemeBackground type lives
// in :ui-kit; no image-loading libraries here — BitmapFactory + a tiny
// in-memory cache cover test wallpapers and user picks.
internal const val THEME_PREFS = "miru_theme"
internal const val THEME_KEY_MODE = "mode"
internal const val THEME_KEY_BG = "custom_bg"
internal const val THEME_KEY_BG_LIBRARY = "bg_library"
internal val THEME_BACKUP_KEYS = setOf(THEME_KEY_MODE, THEME_KEY_BG, THEME_KEY_BG_LIBRARY)

// Restore writes the theme prefs outside the Compose tree; the activity
// observes this revision and reloads its hoisted state.
private val themePrefsRevision = MutableStateFlow(0L)
val themePrefsChanges: StateFlow<Long> = themePrefsRevision.asStateFlow()

fun notifyThemePrefsChanged() {
    themePrefsRevision.update { it + 1 }
}

fun loadThemePrefs(context: Context): Pair<String?, String?> {
    val prefs = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
    return prefs.getString(THEME_KEY_MODE, null) to prefs.getString(THEME_KEY_BG, null)
}

fun saveThemePrefs(context: Context, modeName: String, customBg: String?) {
    context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(THEME_KEY_MODE, modeName)
        .putString(THEME_KEY_BG, customBg)
        .apply()
}

// === Saved backgrounds ===
// Every picked picture lives in the app's own storage (filesDir/backgrounds),
// re-encoded to <=1920px JPEG and named by a hash of the ORIGINAL bytes. So a
// saved background survives a revoked picker permission or a dead link, and
// picking the same picture twice resolves to the same file (no duplicates).
// The library itself is a JSON array of sources in SharedPreferences.
private const val KEY_BG_LIBRARY = "bg_library"
private const val BG_DIR = "backgrounds"
private const val BG_MAX_BYTES = 25 * 1024 * 1024
private const val BG_STORE_SIDE = 1920

fun loadBgLibrary(context: Context, active: String?): List<String> {
    val prefs = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
    val stored = prefs.getString(KEY_BG_LIBRARY, null)
    val items = try {
        stored?.let { json ->
            JSONArray(json).let { arr -> List(arr.length()) { arr.getString(it) } }
        }.orEmpty()
    } catch (_: JSONException) {
        emptyList()
    }
    // Drop local files that vanished; pull in an active background saved
    // before the library existed.
    val alive = items.filter { !isLocalBg(it) || File(it).exists() }
    return (alive + listOfNotNull(active?.takeIf { it !in alive })).distinct()
}

fun saveBgLibrary(context: Context, items: List<String>) {
    context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_BG_LIBRARY, JSONArray(items).toString())
        .apply()
}

private fun bgDir(context: Context) = File(context.filesDir, BG_DIR)

// Stored name = hash of the ORIGINAL bytes (same picture -> same file).
private fun bgFileName(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.take(8).joinToString("") { "%02x".format(it) } + ".jpg"
}

// Where importBackground WOULD store these bytes. Lets a ready-made preset
// tile know it is the active background without importing it first.
internal fun savedBackgroundPath(context: Context, bytes: ByteArray): String =
    File(bgDir(context), bgFileName(bytes)).absolutePath

private fun isLocalBg(source: String) =
    !source.startsWith("http", ignoreCase = true) && !source.startsWith("content:")

// Copies a picked file / link into app storage and returns its local path, or
// null when it cannot be read or is not an image. Same picture -> same path.
suspend fun importBackground(context: Context, source: String): String? =
    withContext(Dispatchers.IO) {
        try {
            val bytes = openBgStream(context.contentResolver, source)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > BG_MAX_BYTES) return@withContext null
                }
                out.toByteArray()
            } ?: return@withContext null

            val name = bgFileName(bytes)
            val dir = bgDir(context).apply { mkdirs() }
            val target = File(dir, name)
            if (target.exists()) return@withContext target.absolutePath

            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return@withContext null
            var sample = 1
            while (probe.outWidth / sample > BG_STORE_SIDE || probe.outHeight / sample > BG_STORE_SIDE) {
                sample *= 2
            }
            val bmp = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@withContext null
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext null
            }
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }

// Removes the stored copy (only files inside our own directory) and the
// decoded bitmap from memory.
fun deleteBackgroundFile(context: Context, source: String) {
    synchronized(bgCache) { bgCache.remove(source) }
    val file = File(source)
    if (isLocalBg(source) && file.parentFile == bgDir(context)) file.delete()
}

private val bgCache = mutableMapOf<String, ImageBitmap>()

private fun decodeBg(resolver: ContentResolver, source: String): ImageBitmap? {
    return try {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openBgStream(resolver, source)?.use { BitmapFactory.decodeStream(it, null, probe) }
        val maxSide = 1080
        var sample = 1
        while (probe.outWidth / sample > maxSide || probe.outHeight / sample > maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = openBgStream(resolver, source)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        bmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

// Hosts like Codeberg's raw server refuse clients without a browser UA, so the
// user-supplied background lane sends the same UA as the plugin network stack.
internal fun backgroundArtHeaders(): Map<String, String> = mapOf(
    "User-Agent" to AndroidSystemInfo.defaultUserAgent,
    "Accept" to "image/*",
)

private fun openBgStream(resolver: ContentResolver, source: String) = try {
    when {
        source.startsWith("http", ignoreCase = true) ->
            URL(source).openConnection().apply {
                connectTimeout = 8000
                readTimeout = 8000
                backgroundArtHeaders().forEach { (name, value) -> setRequestProperty(name, value) }
            }.getInputStream()
        source.startsWith("content:") -> resolver.openInputStream(android.net.Uri.parse(source))
        else -> File(source).inputStream()
    }
} catch (_: Exception) {
    null
}

// Null while loading or on any decode failure — callers fall back to the
// flat palette, never crash on a bad file or dead link.
@Composable
fun rememberBackgroundBitmap(source: String?): ImageBitmap? {
    val resolver = LocalContext.current.contentResolver
    var bitmap by remember(source) { mutableStateOf(source?.let { synchronized(bgCache) { bgCache[it] } }) }
    LaunchedEffect(source) {
        if (source == null) {
            bitmap = null
            return@LaunchedEffect
        }
        val cached = synchronized(bgCache) { bgCache[source] }
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) { decodeBg(resolver, source) }
        if (decoded != null) {
            synchronized(bgCache) { bgCache[source] = decoded }
        }
        bitmap = decoded
    }
    return bitmap
}
