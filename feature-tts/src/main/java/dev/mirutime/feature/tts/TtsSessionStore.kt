package dev.mirutime.feature.tts

import android.annotation.SuppressLint
import android.content.Context
import java.util.concurrent.Executors

/**
 * One global TTS slot, not per book: resuming TTS is only meaningful for the
 * book that was last spoken. Writes never block the caller: [immediate] asks
 * for commit() on the writer thread. A process kill in the few milliseconds
 * before the writer runs can lose the last write; accepted so pause/stop never
 * block the main thread.
 * shortcut: last write may be lost on a hard kill; only the current paragraph
 * cursor is affected, recoverable on next open.
 */
interface TtsSessionStore {
    fun load(): TtsSession?
    fun save(session: TtsSession, immediate: Boolean = false, background: Boolean = false)
    fun clear(immediate: Boolean = false, background: Boolean = false)

    /** Drops the in-memory snapshot after settings/prefs were written externally. */
    fun invalidate() = Unit
}

class InMemoryTtsSessionStore : TtsSessionStore {
    private var session: TtsSession? = null
    override fun load(): TtsSession? = session
    override fun save(session: TtsSession, immediate: Boolean, background: Boolean) {
        this.session = session
    }
    override fun clear(immediate: Boolean, background: Boolean) {
        session = null
    }
}

// In-memory first: the UI reads the cursor back immediately, while the
// single-thread writer persists it. No caller ever blocks on the disk.
internal class TtsSessionCache(
    private val read: () -> String?,
    private val write: (String?, Boolean, Int) -> Unit,
    private val encode: (TtsSession) -> String = TtsSessionCodec::encode,
    private val decode: (String?) -> TtsSession? = TtsSessionCodec::decode,
) {
    private var loaded = false
    private var cached: TtsSession? = null

    fun load(): TtsSession? {
        if (!loaded) {
            cached = decode(read())
            loaded = true
        }
        return cached
    }

    fun save(session: TtsSession, immediate: Boolean, generation: Int = 0) {
        cached = session
        loaded = true
        write(encode(session), immediate, generation)
    }

    fun clear(immediate: Boolean, generation: Int = 0) {
        cached = null
        loaded = true
        write(null, immediate, generation)
    }

    fun invalidate() {
        loaded = false
        cached = null
    }
}

@SuppressLint("ApplySharedPref")
class PrefsTtsSessionStore(context: Context) : TtsSessionStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tts-session-write").apply { isDaemon = true }
    }
    @Volatile
    private var generation = 0
    private val cache = TtsSessionCache(
        read = { prefs.getString(KEY_SESSION, null) },
        write = { encoded, immediate, gen ->
            writer.execute {
                // Ignore stale writes from before an invalidate().
                if (gen != generation) return@execute
                val editor = prefs.edit()
                if (encoded == null) {
                    editor.remove(KEY_SESSION)
                } else {
                    editor.putString(KEY_SESSION, encoded)
                }
                if (immediate) editor.commit() else editor.apply()
            }
        },
    )

    // First read is one small getString; later reads are the in-memory snapshot.
    override fun load(): TtsSession? = cache.load()

    // `background` is kept for API compatibility: every write is queued.
    override fun save(session: TtsSession, immediate: Boolean, background: Boolean) {
        cache.save(session, immediate, generation)
    }

    override fun clear(immediate: Boolean, background: Boolean) {
        cache.clear(immediate, generation)
    }

    override fun invalidate() {
        generation++
        cache.invalidate()
    }

    companion object {
        const val PREFS_NAME = "reader_tts"
        const val KEY_SESSION = "session"
        const val KEY_VOICE_ID = "settings_voice_id"
        const val KEY_ENGINE_ID = "settings_engine_id"
        const val KEY_SPEED = "settings_speed"
        const val KEY_PITCH = "settings_pitch"
        const val KEY_AUTO_NEXT = "settings_auto_next"
        const val KEY_MINI_PLAYER_ALWAYS = "settings_mini_player_always"
        const val KEY_FLOATING_PLAYER = "settings_floating_player"
        const val KEY_FLOATING_BUBBLE_X = "settings_floating_bubble_x"
        const val KEY_FLOATING_BUBBLE_Y = "settings_floating_bubble_y"
        const val KEY_FLOATING_PANEL_X = "settings_floating_panel_x"
        const val KEY_FLOATING_PANEL_Y = "settings_floating_panel_y"
        const val KEY_FLOATING_PANEL_WIDTH = "settings_floating_panel_width"
        const val KEY_FLOATING_OPACITY = "settings_floating_opacity"
        const val KEY_FLOATING_SHOW_TEXT = "settings_floating_show_text"
        const val KEY_FLOATING_GLOW = "settings_floating_glow"
        const val KEY_FLOATING_PARAGRAPH_MODE = "settings_floating_paragraph_mode"
        const val KEY_SAVED_VOICES = "settings_saved_voices"
        const val KEY_TRANSLATED_VOICE_ID = "settings_translated_voice_id"
        const val KEY_TRANSLATED_ENGINE_ID = "settings_translated_engine_id"
        val BackupKeys = setOf(
            KEY_SESSION,
            KEY_VOICE_ID,
            KEY_ENGINE_ID,
            KEY_TRANSLATED_VOICE_ID,
            KEY_TRANSLATED_ENGINE_ID,
            KEY_SPEED,
            KEY_PITCH,
            KEY_AUTO_NEXT,
            KEY_MINI_PLAYER_ALWAYS,
            KEY_FLOATING_PLAYER,
            KEY_FLOATING_BUBBLE_X,
            KEY_FLOATING_BUBBLE_Y,
            KEY_FLOATING_PANEL_X,
            KEY_FLOATING_PANEL_Y,
            KEY_FLOATING_PANEL_WIDTH,
            KEY_FLOATING_OPACITY,
            KEY_FLOATING_SHOW_TEXT,
            KEY_FLOATING_GLOW,
            KEY_FLOATING_PARAGRAPH_MODE,
            KEY_SAVED_VOICES,
        )
    }
}
