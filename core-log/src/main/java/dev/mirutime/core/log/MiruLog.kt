package dev.mirutime.core.log

import android.util.Log
import java.util.Locale

/** Logcat for every level; WARN/ERROR (and INFO) also land in the file export. */
object MiruLog {
    private const val TAG = "MiruTime"
    private const val MAX_FIELD_CHARS = 120
    private val UNSAFE_FIELD_CHARS = Regex("[\\s=]+")

    // Process-level verbose gate, set ONCE by the app from BuildConfig.DEBUG
    // (core/KMP must not reference Android BuildConfig). Release keeps this
    // false: debug/trace lines are dropped, warn/error/event reach logcat.
    @Volatile
    var debugEnabled: Boolean = false

    // Test seam: null in production so d() writes to Log.d. Unit tests install
    // a sink to observe the gate without logcat.
    @Volatile
    internal var debugSink: ((String, String) -> Unit)? = null

    fun d(area: String, message: String) {
        if (!debugEnabled) return
        val tag = "$TAG.$area"
        val sink = debugSink
        if (sink != null) sink(tag, message) else Log.d(tag, message)
    }

    /**
     * One structured transition line: `event=<name> key=value ...`.
     * Logged at INFO (logcat + file). Callers pass ids/counts/status only —
     * never chapter text, queries, cookies or keys. The file sink still
     * applies its own redaction.
     */
    fun event(area: String, event: String, vararg fields: Pair<String, Any?>) {
        i(area, formatEvent(event, fields))
    }

    /** Stable short hash for ids/URLs that must never be written raw. */
    fun idHash(value: String): String = Integer.toHexString(value.hashCode())

    internal fun formatEvent(event: String, fields: Array<out Pair<String, Any?>>): String =
        buildString {
            append("event=").append(event)
            for ((key, value) in fields) {
                if (value == null) continue
                append(' ').append(key).append('=').append(formatValue(value))
            }
        }

    private fun formatValue(value: Any): String = when (value) {
        is Boolean, is Number -> value.toString()
        is Enum<*> -> value.name.lowercase(Locale.US)
        else -> sanitize(value.toString())
    }

    // Keeps one record on one line so a log reader can split key=value.
    private fun sanitize(text: String): String {
        val flat = text.replace(UNSAFE_FIELD_CHARS, "_")
        return if (flat.length <= MAX_FIELD_CHARS) flat else flat.take(MAX_FIELD_CHARS) + "…"
    }

    fun i(area: String, message: String) {
        val tag = "$TAG.$area"
        Log.i(tag, message)
        MiruFileLogger.write("INFO", tag, message)
    }

    fun w(area: String, message: String, throwable: Throwable? = null) {
        val tag = "$TAG.$area"
        if (throwable == null) Log.w(tag, message)
        else Log.w(tag, message, throwable)
        MiruFileLogger.write("WARN", tag, message, throwable)
    }

    fun e(area: String, message: String, throwable: Throwable? = null) {
        val tag = "$TAG.$area"
        if (throwable == null) Log.e(tag, message)
        else Log.e(tag, message, throwable)
        MiruFileLogger.write("ERROR", tag, message, throwable)
    }
}
