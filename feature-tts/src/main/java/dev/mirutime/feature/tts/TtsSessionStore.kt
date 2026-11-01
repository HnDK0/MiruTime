package dev.mirutime.feature.tts

import android.annotation.SuppressLint
import android.content.Context

/**
 * One global TTS slot, not per book: resuming TTS is only meaningful for the
 * book that was last spoken. [immediate] uses commit() for pause/stop writes so
 * a process kill right after the action cannot lose the cursor.
 */
interface TtsSessionStore {
    fun load(): TtsSession?
    fun save(session: TtsSession, immediate: Boolean = false)
    fun clear(immediate: Boolean = false)
}

class InMemoryTtsSessionStore : TtsSessionStore {
    private var session: TtsSession? = null

    override fun load(): TtsSession? = session

    override fun save(session: TtsSession, immediate: Boolean) {
        this.session = session
    }

    override fun clear(immediate: Boolean) {
        session = null
    }
}

// commit() is deliberate: pause/stop must survive an immediate process kill.
@SuppressLint("ApplySharedPref")
class PrefsTtsSessionStore(context: Context) : TtsSessionStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): TtsSession? =
        runCatching { TtsSessionCodec.decode(prefs.getString(KEY_SESSION, null)) }.getOrNull()

    override fun save(session: TtsSession, immediate: Boolean) {
        val editor = prefs.edit().putString(KEY_SESSION, TtsSessionCodec.encode(session))
        if (immediate) editor.commit() else editor.apply()
    }

    override fun clear(immediate: Boolean) {
        val editor = prefs.edit().remove(KEY_SESSION)
        if (immediate) editor.commit() else editor.apply()
    }

    companion object {
        const val PREFS_NAME = "reader_tts"
        const val KEY_SESSION = "session"
        const val KEY_VOICE_ID = "settings_voice_id"
        const val KEY_ENGINE_ID = "settings_engine_id"
        const val KEY_SPEED = "settings_speed"
        const val KEY_PITCH = "settings_pitch"
        const val KEY_AUTO_NEXT = "settings_auto_next"
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
        val BackupKeys = setOf(
            KEY_SESSION,
            KEY_VOICE_ID,
            KEY_ENGINE_ID,
            KEY_SPEED,
            KEY_PITCH,
            KEY_AUTO_NEXT,
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
