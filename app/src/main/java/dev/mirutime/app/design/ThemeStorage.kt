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
import dev.mirutime.core.log.MiruLog
import java.io.File
import org.json.JSONArray
import org.json.JSONException
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
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
// Ready-made presets live in their own durable folder (filesDir/preset_
// backgrounds) and are applied in place: they are NOT library members.
private const val KEY_BG_LIBRARY = "bg_library"
private const val BG_DIR = "backgrounds"
internal const val PRESET_BG_DIR = "preset_backgrounds"
private const val BG_MAX_BYTES = 25 * 1024 * 1024
private const val BG_STORE_SIDE = 1920

internal fun presetBgDir(context: Context) = File(context.filesDir, PRESET_BG_DIR)

internal fun isPresetBackgroundPath(context: Context, source: String): Boolean {
    if (!isLocalBg(source)) return false
    val file = runCatching { File(source).canonicalFile }.getOrNull() ?: return false
    return runCatching { file.parentFile == presetBgDir(context).canonicalFile }.getOrDefault(false)
}

// Raw saved list; callers that show it filter presets out via loadBgLibrary.
internal fun storedBgLibrary(context: Context): List<String> {
    val stored = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_BG_LIBRARY, null)
    return try {
        stored?.let { json ->
            JSONArray(json).let { arr -> List(arr.length()) { arr.getString(it) } }
        }.orEmpty()
    } catch (_: JSONException) {
        emptyList()
    }
}

fun loadBgLibrary(context: Context, active: String?): List<String> {
    // Drop local files that vanished; pull in an active background saved
    // before the library existed. Presets stay outside "My backgrounds".
    val items = storedBgLibrary(context)
    val alive = items.filter { !isLocalBg(it) || File(it).exists() }
    return (alive + listOfNotNull(active?.takeIf { it !in alive }))
        .filterNot { isPresetBackgroundPath(context, it) }
        .distinct()
}

fun saveBgLibrary(context: Context, items: List<String>) {
    val kept = items.filterNot { isPresetBackgroundPath(context, it) }
    context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_BG_LIBRARY, JSONArray(kept).toString())
        .apply()
}

private fun bgDir(context: Context) = File(context.filesDir, BG_DIR)

// Stored name = hash of the ORIGINAL bytes (same picture -> same file).
internal fun bgFileName(digest: ByteArray): String =
    digest.take(8).joinToString("") { "%02x".format(it) } + ".jpg"

// Where importBackground stored a picture whose ORIGINAL bytes' SHA-256 is
// known (used to recognise preset copies made by older builds).
internal fun savedBackgroundPathForDigest(context: Context, digest: ByteArray): String =
    File(bgDir(context), bgFileName(digest)).absolutePath

// SHA-256 of an original picture (streamed; never loads the whole file).
internal fun fileDigest(file: File): ByteArray? = runCatching {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    md.digest()
}.getOrNull()

private fun isLocalBg(source: String) =
    !source.startsWith("http", ignoreCase = true) && !source.startsWith("content:")

enum class BackgroundImportFailure { InvalidScheme, Unreadable, TooLarge, NotAnImage, StoreFailed }

sealed interface BackgroundImportResult {
    data class Success(val path: String) : BackgroundImportResult
    data class Failure(val reason: BackgroundImportFailure) : BackgroundImportResult
}

// User-pasted links arrive with stray whitespace, HTML entities or no scheme.
// Only schemes the importer can actually read are accepted; everything else is
// a visible validation error instead of a silent no-op.
internal fun normalizeBackgroundUrl(raw: String): String? {
    val cleaned = raw
        .replace("\r", "")
        .replace("\n", "")
        .replace("&amp;", "&")
        .trim()
        .trim('<', '>')
        .trim()
    if (cleaned.isEmpty()) return null
    val lower = cleaned.lowercase()
    if (lower.startsWith("data:") || lower.startsWith("blob:")) return null
    val withScheme = when {
        lower.startsWith("http://") || lower.startsWith("https://") -> cleaned
        lower.startsWith("content://") -> cleaned
        lower.contains("://") -> return null
        else -> "https://$cleaned"
    }
    val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?')
    return withScheme.takeIf { host.isNotBlank() }
}

internal fun bytesDigest(bytes: ByteArray): ByteArray =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

private fun backgroundHost(source: String): String =
    runCatching { java.net.URI(source).host }.getOrNull().orEmpty()

private fun importFailure(source: String, reason: BackgroundImportFailure): BackgroundImportResult.Failure {
    MiruLog.event("theme", "bg_import", "ok" to false, "reason" to reason.name, "host" to backgroundHost(source))
    return BackgroundImportResult.Failure(reason)
}

// Copies a picked file / link into app storage. The stored name is the SHA-256
// of the ORIGINAL bytes (same picture -> same file); a raw-bytes prefix would
// collide for every JPEG/PNG. Failures carry a reason for the sheet.
suspend fun importBackground(context: Context, source: String): BackgroundImportResult =
    withContext(Dispatchers.IO) {
        try {
            val normalized = normalizeBackgroundUrl(source)
                ?: return@withContext importFailure(source, BackgroundImportFailure.InvalidScheme)
            val bytes = openBgStream(context.contentResolver, normalized)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > BG_MAX_BYTES) {
                        return@withContext importFailure(source, BackgroundImportFailure.TooLarge)
                    }
                }
                out.toByteArray()
            } ?: return@withContext importFailure(source, BackgroundImportFailure.Unreadable)

            val name = bgFileName(bytesDigest(bytes))
            val dir = bgDir(context).apply { mkdirs() }
            val target = File(dir, name)
            if (target.exists()) return@withContext BackgroundImportResult.Success(target.absolutePath)

            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
            if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                return@withContext importFailure(source, BackgroundImportFailure.NotAnImage)
            }
            var sample = 1
            while (probe.outWidth / sample > BG_STORE_SIDE || probe.outHeight / sample > BG_STORE_SIDE) {
                sample *= 2
            }
            val bmp = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@withContext importFailure(source, BackgroundImportFailure.NotAnImage)
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext importFailure(source, BackgroundImportFailure.StoreFailed)
            }
            MiruLog.event("theme", "bg_import", "ok" to true, "host" to backgroundHost(source))
            BackgroundImportResult.Success(target.absolutePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            importFailure(source, BackgroundImportFailure.StoreFailed)
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
