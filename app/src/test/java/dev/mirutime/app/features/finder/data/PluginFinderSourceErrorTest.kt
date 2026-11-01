package dev.mirutime.app.features.finder.data

import dev.mirutime.core.api.PluginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The Add-by-link reason is rendered to the user and written to the log: it
// names the class/HTTP status/scheme+host+path, never an unsafe raw plugin
// detail, which can quote a URL, a query or page text.
class PluginFinderSourceErrorTest {

    @Test
    fun unsafeScriptErrorDetailNeverReachesTheReason() {
        val reason = sourceErrorReason(
            PluginError.ScriptError(
                "getChapterList failed at https://site.example/book/1?token=SECRET-TOKEN",
            ),
        )

        assertEquals("class=ScriptError exception=ScriptError detail=redacted", reason)
        assertFalse(reason, "SECRET" in reason)
        assertFalse(reason, "?" in reason)
        assertFalse(reason, "site.example" in reason)
    }

    @Test
    fun returnedNilKeepsTheNotFoundHint() {
        val reason = sourceErrorReason(
            PluginError.ScriptError("getBookTitle returned nil for https://site.example/?q=1"),
        )

        assertEquals("page not found or site layout changed", reason)
    }

    @Test
    fun networkErrorNamesTheHttpStatusAndHostPath() {
        val reason = sourceErrorReason(
            PluginError.Network(
                cause = "ServerResponseException https://site.example/book/1?token=SECRET",
                status = 503,
                host = "site.example",
                path = "/book/1?token=SECRET",
            ),
        )

        assertTrue(reason, "HTTP 503" in reason)
        assertTrue(reason, "request=site.example/book/1" in reason)
        assertFalse(reason, "SECRET" in reason)
        assertFalse(reason, "?" in reason)
    }
}
