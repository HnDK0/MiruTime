package dev.mirutime.feature.tts

import kotlin.math.roundToInt

/**
 * NoveLA-style estimate for the floating player: character count divided by a
 * speaking rate, scaled by the user's speed. It is an estimate, not a wall
 * clock, so the UI must not present it as exact elapsed time.
 */
const val TtsBaseCharsPerSecond = 13f

fun ttsEstimatedSeconds(charCount: Int, speed: Float): Int {
    if (charCount <= 0) return 0
    val charsPerSecond = TtsBaseCharsPerSecond * clampTtsSpeed(speed)
    if (charsPerSecond <= 0f) return 0
    return (charCount / charsPerSecond).roundToInt().coerceAtLeast(1)
}

/** Same clock shape as NoveLA: h:mm:ss only when there is an hour part. */
fun formatTtsDuration(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}
