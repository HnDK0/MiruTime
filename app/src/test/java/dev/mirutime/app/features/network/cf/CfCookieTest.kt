package dev.mirutime.app.features.network.cf

import dev.mirutime.app.di.host.parseCookieHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfCookieTest {

    @Test
    fun `webview cookie header parses into name value pairs`() {
        assertEquals(
            mapOf("cf_clearance" to "abc", "session" to "1", "empty" to ""),
            parseCookieHeader("cf_clearance=abc; session=1; empty=; malformed"),
        )
        assertEquals(emptyMap<String, String>(), parseCookieHeader(null))
    }

    @Test
    fun `solver cookies are forwarded in the same name value shape`() {
        val cookies = mapOf("cf_clearance" to "abc", "__cf_bm" to "xyz")
        assertEquals(
            listOf("cf_clearance=abc", "__cf_bm=xyz"),
            cfCookieSetHeaders(cookies),
        )
    }

    @Test
    fun `cookie polling wakes only on a new clearance`() {
        assertTrue(hasNewClearance(null, mapOf("cf_clearance" to "abc")))
        assertTrue(hasNewClearance("old", mapOf("cf_clearance" to "new")))
        assertFalse(hasNewClearance("abc", mapOf("cf_clearance" to "abc")))
        assertFalse(hasNewClearance(null, mapOf("session" to "1")))
    }
}
