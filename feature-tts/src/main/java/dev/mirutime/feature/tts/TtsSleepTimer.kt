package dev.mirutime.feature.tts

/**
 * Sleep timer for TTS. It is a runtime session decision: the timer is not
 * persisted, because after process death the playback service is gone and a
 * resurrected deadline would stop a new session the user never set.
 */
enum class TtsSleepTimerMode { Minutes, Chapters }

data class TtsSleepTimerState(
    val mode: TtsSleepTimerMode,
    val remainingSeconds: Int = 0,
    val totalSeconds: Int = 0,
    /** Minutes only: wall-clock deadline used for the notification label. */
    val endsAtEpochMillis: Long = 0,
    val chaptersRemaining: Int = 0,
    val chaptersTotal: Int = 0,
)

const val TtsSleepTimerMaxMinutes = 180
const val TtsSleepTimerMaxChapters = 99

fun clampSleepTimerMinutes(minutes: Int): Int = minutes.coerceIn(1, TtsSleepTimerMaxMinutes)

fun clampSleepTimerChapters(chapters: Int): Int = chapters.coerceIn(1, TtsSleepTimerMaxChapters)

/**
 * Notification de-duplication key. A countdown tick changes [remainingSeconds]
 * but must not republish the notification, so only the mode and the stable
 * boundary are part of the key.
 */
fun ttsSleepTimerNotificationKey(timer: TtsSleepTimerState?): String = when (timer?.mode) {
    null -> "off"
    TtsSleepTimerMode.Minutes -> "min:${timer.endsAtEpochMillis}"
    TtsSleepTimerMode.Chapters -> "ch:${timer.chaptersRemaining}"
}
