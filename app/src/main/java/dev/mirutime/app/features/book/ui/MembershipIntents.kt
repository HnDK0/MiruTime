package dev.mirutime.app.features.book.ui

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay

internal const val MembershipDebounceMs = 260L

/**
 * Consumes explicit membership targets and applies only the newest one after a
 * short debounce. Add→remove→add inside the window performs exactly one add;
 * an intent that arrives while the action is running waits for the next pass.
 */
internal suspend fun consumeMembershipIntents(
    intents: ReceiveChannel<Boolean>,
    debounceMs: Long = MembershipDebounceMs,
    apply: suspend (Boolean) -> Unit,
) {
    for (first in intents) {
        var target = first
        delay(debounceMs)
        // Conflated channel: take the newest queued target and drop the middle.
        while (true) {
            target = intents.tryReceive().getOrNull() ?: break
        }
        apply(target)
    }
}
