package dev.mirutime.app.di

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Test

// fetchTextResult must honor the same charset priority as the plugin bridge:
// plugin-declared charset, then the response Content-Type, then UTF-8. The old
// bodyAsText() honored the server header; reading raw bytes must not regress a
// GBK/Big5 page whose plugin declares no charset.
class FetchedTextCharsetTest {

    private val gbkBytes = "测试".toByteArray(Charset.forName("GBK"))
    private val utf8Bytes = "привет".toByteArray(Charsets.UTF_8)

    @Test
    fun serverContentTypeCharsetWinsWithoutDeclaredCharset() {
        assertEquals(
            "测试",
            decodeFetchedText(gbkBytes, declaredCharset = null, contentTypeHeader = "text/html; charset=GBK"),
        )
    }

    @Test
    fun declaredPluginCharsetWinsOverContentType() {
        assertEquals(
            "测试",
            decodeFetchedText(gbkBytes, declaredCharset = "GBK", contentTypeHeader = "text/html; charset=UTF-8"),
        )
    }

    @Test
    fun metaCharsetIsSniffedWhenNeitherDeclaredNorHeaderHasOne() {
        val html = """<meta charset="gbk">测试""".toByteArray(Charset.forName("GBK"))
        assertEquals(
            """<meta charset="gbk">测试""",
            decodeFetchedText(html, declaredCharset = null, contentTypeHeader = null),
        )
    }

    @Test
    fun utf8WhenNothingDeclaresACharset() {
        assertEquals(
            "привет",
            decodeFetchedText(utf8Bytes, declaredCharset = null, contentTypeHeader = null),
        )
    }
}
