package dev.mirutime.core.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

// The debug gate is what keeps release logcat free of trace lines: d() must be
// dead when debugEnabled is false and reach the sink (with the area tag) when
// it is on.
class DebugLogGateTest {

    private val calls = mutableListOf<Pair<String, String>>()

    @After
    fun tearDown() {
        MiruLog.debugSink = null
        MiruLog.debugEnabled = false
    }

    @Test
    fun `disabled debug writes nothing to the sink`() {
        MiruLog.debugSink = { tag, message -> calls += tag to message }
        MiruLog.debugEnabled = false

        MiruLog.d("net", "event=http")

        assertEquals(0, calls.size)
    }

    @Test
    fun `enabled debug reaches the sink with the area tag`() {
        MiruLog.debugSink = { tag, message -> calls += tag to message }
        MiruLog.debugEnabled = true

        MiruLog.d("net", "event=http")

        assertEquals(1, calls.size)
        assertEquals("MiruTime.net", calls.single().first)
        assertEquals("event=http", calls.single().second)
    }
}
