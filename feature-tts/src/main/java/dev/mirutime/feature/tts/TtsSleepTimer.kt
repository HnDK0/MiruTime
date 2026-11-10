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

/** One-tap minute presets, shown inside the Minutes row before the custom field. */
val TtsSleepTimerPresetMinutes: List<Int> = listOf(15, 30, 45, 60)

/** One-tap chapter presets, shown inside the Chapters row before the custom field. */
val TtsSleepTimerPresetChapters: List<Int> = listOf(2, 5, 8, 12, 15)

fun isSleepTimerPresetActive(timer: TtsSleepTimerState?, minutes: Int): Boolean =
    timer != null &&
        timer.mode == TtsSleepTimerMode.Minutes &&
        timer.totalSeconds == minutes * 60

fun isSleepTimerChaptersPresetActive(timer: TtsSleepTimerState?, chapters: Int): Boolean =
    timer != null &&
        timer.mode == TtsSleepTimerMode.Chapters &&
        timer.chaptersTotal == chapters

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
