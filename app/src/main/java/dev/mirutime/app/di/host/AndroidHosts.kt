package dev.mirutime.app.di.host

import android.content.Context
import android.os.Build
import dev.mirutime.core.log.MiruLog
import dev.mirutime.core.host.CookieStore
import dev.mirutime.core.host.FileSystem
import dev.mirutime.app.features.settings.data.PluginKvBackup
import dev.mirutime.app.features.settings.data.PluginKvBackupStore
import dev.mirutime.app.features.settings.data.mergePluginKv
import dev.mirutime.core.host.KeyValueStore
import dev.mirutime.core.host.Logger
import dev.mirutime.core.host.SystemInfo
import java.io.File
import java.util.Locale

// Android host adapters for the plugin runtime. Single source of truth: the
// TranslationGraph and PluginGraph both draw these from here.

object AndroidSystemInfo : SystemInfo {
    override val defaultUserAgent: String =
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    override val acceptLanguage: String
        get() = Locale.getDefault().toLanguageTag()
}

object MiruCoreLogger : Logger {
    override fun d(tag: String, msg: String) = MiruLog.d(tag, msg)

    override fun w(tag: String, msg: String) = MiruLog.w(tag, msg)

    override fun e(tag: String, msg: String, t: Throwable?) = MiruLog.e(tag, msg, t)
}

// One app-wide store: the plugin HTTP client, the CF WebView and native
// requests must read/write the same cookies, so every graph draws this instance.
object AppCookieStore {
    @Volatile
    private var instance: CookieStore? = null

    fun get(): CookieStore =
        instance ?: synchronized(this) {
            instance ?: WebViewCookieStore(AndroidWebCookieStorage())
                .also { instance = it }
        }
}

// FileSystem rooted at an app-private directory. Every plugin path is relative
// (libDir/installDir), so nothing escapes [rootDir]. Never throws: a broken
// filesystem must degrade to "missing file", not crash the plugin runtime.
class AndroidFileSystem(private val rootDir: File) : FileSystem {
    init {
        runCatching { rootDir.mkdirs() }
    }

    override fun read(path: String): ByteArray? =
        runCatching { resolve(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun write(path: String, data: ByteArray) {
        val target = resolve(path)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            target.parentFile?.mkdirs()
            tmp.writeBytes(data)
            // renameTo is atomic on the same volume; fall back to a direct
            // write when the mount refuses it (temp then cleaned up).
            if (!tmp.renameTo(target)) {
                target.writeBytes(data)
                tmp.delete()
            }
        } catch (e: Exception) {
            // No path/host in the log: it can name a plugin or user source.
            MiruLog.w("fs", "event=fs-write-failed error=${e::class.simpleName}")
            runCatching { tmp.delete() }
        }
    }

    override fun exists(path: String): Boolean =
        runCatching { resolve(path).exists() }.getOrDefault(false)

    override fun delete(path: String) {
        runCatching { resolve(path).delete() }
    }

    override fun rename(from: String, to: String) {
        val renamed = runCatching {
            val target = resolve(to)
            target.parentFile?.mkdirs()
            resolve(from).renameTo(target)
        }.getOrDefault(false)
        if (!renamed) MiruLog.w("fs", "event=fs-rename-failed")
    }

    override fun list(dir: String): List<String> =
        runCatching { resolve(dir).list()?.sorted()?.map { "$dir/$it" } }.getOrNull().orEmpty()

    private fun resolve(path: String): File {
        // Preserve access to absolute paths that already lie inside rootDir
        // (compatibility with older callers), while blocking everything else.
        val absolute = File(path)
        if (absolute.isAbsolute) {
            val canonical = runCatching { absolute.canonicalFile }.getOrNull()
            val rootCanonical = rootDir.canonicalFile
            if (canonical != null &&
                (canonical == rootCanonical ||
                    canonical.path.startsWith(rootCanonical.path + File.separator))
            ) {
                return absolute
            }
        }
        // Normalize traversal so "../" cannot escape rootDir, while keeping the
        // ordinary relative semantics (a/../b -> b). Invalid/empty results land
        // on a sentinel that never exists, so mutations are no-ops.
        val segments = mutableListOf<String>()
        for (part in path.replace('\\', '/').split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += part
            }
        }
        if (segments.isEmpty()) return File(rootDir, ".invalid")
        val candidate = segments.fold(rootDir) { acc, segment -> File(acc, segment) }
        val rootCanonical = rootDir.canonicalFile
        val candidateCanonical = runCatching { candidate.canonicalFile }.getOrNull() ?: candidate
        return if (candidateCanonical == rootCanonical ||
            candidateCanonical.path.startsWith(rootCanonical.path + File.separator)
        ) {
            candidate
        } else {
            File(rootDir, ".invalid")
        }
    }
}

// One SharedPreferences file; keys namespaced as "<namespace>:<key>".
class PrefsKeyValueStore(context: Context) : KeyValueStore, PluginKvBackupStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("mirutime_kv", Context.MODE_PRIVATE)

    override fun get(namespace: String, key: String): String? =
        prefs.getString("$namespace:$key", null)

    override fun set(namespace: String, key: String, value: String) {
        prefs.edit().putString("$namespace:$key", value).apply()
    }

    override fun remove(namespace: String, key: String) {
        prefs.edit().remove("$namespace:$key").apply()
    }

    override fun snapshot(): PluginKvBackup = PluginKvBackup(
        entries = prefs.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap(),
    )

    override fun restore(backup: PluginKvBackup) {
        // Keys are stored verbatim ("namespace:key"); never route them through set().
        val editor = prefs.edit()
        mergePluginKv(snapshot().entries, backup.entries).forEach { (key, value) ->
            editor.putString(key, value)
        }
        check(editor.commit()) { "Could not restore plugin settings" }
    }
}