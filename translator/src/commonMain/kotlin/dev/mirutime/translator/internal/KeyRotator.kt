package dev.mirutime.translator.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class KeyRotator(
    keys: List<String>,
    private val cooldown: Duration = 1.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class KeyState(
        val key: String,
        var coolingUntil: TimeMark? = null,
    )

    private val mutex = Mutex()
    private val states = keys.filter { it.isNotBlank() }.map(::KeyState)
    private var nextIndex = 0

    suspend fun next(): String? = mutex.withLock {
        if (states.isEmpty()) return@withLock null
        repeat(states.size) {
            val state = states[nextIndex]
            nextIndex = (nextIndex + 1) % states.size
            if (!state.isCooling()) return@withLock state.key
        }
        null
    }

    suspend fun availableCount(): Int = mutex.withLock {
        states.count { !it.isCooling() }
    }

    suspend fun reset(key: String) = mutex.withLock {
        states.firstOrNull { it.key == key }?.coolingUntil = null
    }

    suspend fun coolDown(key: String, duration: Duration = cooldown) = mutex.withLock {
        states.firstOrNull { it.key == key }?.coolingUntil = timeSource.markNow() + duration
    }

    private fun KeyState.isCooling(): Boolean =
        coolingUntil?.hasPassedNow() == false
}
