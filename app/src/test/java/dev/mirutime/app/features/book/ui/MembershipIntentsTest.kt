package dev.mirutime.app.features.book.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MembershipIntentsTest {

    @Test
    fun `add remove add coalesces to the latest target once`() = runTest {
        val intents = Channel<Boolean>(Channel.CONFLATED)
        val applied = mutableListOf<Boolean>()
        val job = launch {
            consumeMembershipIntents(intents) { applied += it }
        }

        intents.send(true)
        intents.send(false)
        intents.send(true)
        advanceTimeBy(MembershipDebounceMs + 1)
        runCurrent()

        assertEquals(listOf(true), applied)

        intents.close()
        job.cancel()
    }

    @Test
    fun `repeated identical taps do not duplicate work`() = runTest {
        val intents = Channel<Boolean>(Channel.CONFLATED)
        val applied = mutableListOf<Boolean>()
        val job = launch {
            consumeMembershipIntents(intents) { applied += it }
        }

        repeat(8) { intents.send(false) }
        advanceTimeBy(MembershipDebounceMs + 1)
        runCurrent()

        assertEquals(listOf(false), applied)

        intents.close()
        job.cancel()
    }

    @Test
    fun `separate windows apply in order`() = runTest {
        val intents = Channel<Boolean>(Channel.CONFLATED)
        val applied = mutableListOf<Boolean>()
        val job = launch {
            consumeMembershipIntents(intents) { applied += it }
        }

        intents.send(true)
        advanceTimeBy(MembershipDebounceMs + 1)
        runCurrent()
        intents.send(false)
        advanceTimeBy(MembershipDebounceMs + 1)
        runCurrent()

        assertEquals(listOf(true, false), applied)

        intents.close()
        job.cancel()
    }
}
