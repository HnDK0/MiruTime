package dev.mirutime.app.di.host

import android.content.Context
import android.os.Build
import dev.mirutime.core.log.MiruLog
import dev.mirutime.core.host.CookieStore
import dev.mirutime.core.host.FileSystem
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
        } catch (_: Exception) {
            runCatching { tmp.delete() }
        }
    }

    override fun exists(path: String): Boolean =
        runCatching { resolve(path).exists() }.getOrDefault(false)

    override fun delete(path: String) {
        runCatching { resolve(path).delete() }
    }

    override fun rename(from: String, to: String) {
        runCatching {
            val target = resolve(to)
            target.parentFile?.mkdirs()
            resolve(from).renameTo(target)
        }
    }

    override fun list(dir: String): List<String> =
        runCatching { resolve(dir).list()?.sorted()?.map { "$dir/$it" } }.getOrNull().orEmpty()

    private fun resolve(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(rootDir, path)
    }
}

// One SharedPreferences file; keys namespaced as "<namespace>:<key>".
class PrefsKeyValueStore(context: Context) : KeyValueStore {
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
}