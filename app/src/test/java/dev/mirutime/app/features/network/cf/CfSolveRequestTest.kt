package dev.mirutime.app.features.network.cf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CfSolveRequestTest {

    @Test
    fun `http and https urls with a host are accepted`() {
        assertEquals(
            CfSolveRequest(7, "example.com", "https://example.com/book/1"),
            CfSolveRequestPolicy.from(7, "https://example.com/book/1"),
        )
        assertEquals(
            CfSolveRequest(7, "example.com", "http://example.com"),
            CfSolveRequestPolicy.from(7, " http://example.com "),
        )
    }

    @Test
    fun `local empty and non-http targets are rejected`() {
        assertNull(CfSolveRequestPolicy.from(7, "local://book/1"))
        assertNull(CfSolveRequestPolicy.from(7, "file:///sdcard/book.epub"))
        assertNull(CfSolveRequestPolicy.from(7, "content://provider/book"))
        assertNull(CfSolveRequestPolicy.from(7, ""))
        assertNull(CfSolveRequestPolicy.from(7, null))
        assertNull(CfSolveRequestPolicy.from(7, "not a url"))
        assertNull(CfSolveRequestPolicy.from(7, "https:///no-host"))
    }

    @Test
    fun `a missing book id never opens the solver`() {
        assertNull(CfSolveRequestPolicy.from(0, "https://example.com"))
        assertNull(CfSolveRequestPolicy.from(-1, "https://example.com"))
    }
}
