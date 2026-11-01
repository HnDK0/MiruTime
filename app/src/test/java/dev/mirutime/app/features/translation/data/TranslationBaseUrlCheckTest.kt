package dev.mirutime.app.features.translation.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationBaseUrlCheckTest {

    @Test
    fun `a host without a scheme suggests the full form`() {
        assertEquals(TranslationBaseUrlCheck.MissingScheme, checkTranslationBaseUrl("192.168.1.1"))
        assertEquals(TranslationBaseUrlCheck.MissingScheme, checkTranslationBaseUrl("192.168.1.1/v1"))
        assertEquals(TranslationBaseUrlCheck.MissingScheme, checkTranslationBaseUrl("localhost:11434/v1"))
        assertEquals(TranslationBaseUrlCheck.MissingScheme, checkTranslationBaseUrl("http:/192.168.1.1/v1"))
    }

    @Test
    fun `only http and https are supported`() {
        assertEquals(TranslationBaseUrlCheck.UnsupportedScheme, checkTranslationBaseUrl("ftp://x"))
        assertEquals(TranslationBaseUrlCheck.UnsupportedScheme, checkTranslationBaseUrl("ws://192.168.1.1/v1"))
        assertEquals(TranslationBaseUrlCheck.UnsupportedScheme, checkTranslationBaseUrl("file:///tmp/x"))
    }

    @Test
    fun `a parsed url without a host is missing host`() {
        assertEquals(TranslationBaseUrlCheck.MissingHost, checkTranslationBaseUrl("http://"))
        assertEquals(TranslationBaseUrlCheck.MissingHost, checkTranslationBaseUrl("http:///v1"))
        assertEquals(TranslationBaseUrlCheck.MissingHost, checkTranslationBaseUrl("https://"))
        assertEquals(TranslationBaseUrlCheck.MissingHost, checkTranslationBaseUrl("http://:8080/v1"))
    }

    @Test
    fun `unparsable input and base-url-only violations are invalid`() {
        assertEquals(TranslationBaseUrlCheck.InvalidUrl, checkTranslationBaseUrl("http://exa mple.com/v1"))
        assertEquals(TranslationBaseUrlCheck.InvalidUrl, checkTranslationBaseUrl("""http:\\192.168.1.1\v1"""))
        assertEquals(TranslationBaseUrlCheck.InvalidUrl, checkTranslationBaseUrl("http://192.168.1.1/v1?key=x"))
        assertEquals(TranslationBaseUrlCheck.InvalidUrl, checkTranslationBaseUrl("http://192.168.1.1/v1#frag"))
        assertEquals(TranslationBaseUrlCheck.InvalidUrl, checkTranslationBaseUrl("http://user:pass@192.168.1.1/v1"))
        assertEquals(TranslationBaseUrlCheck.InvalidUrl, checkTranslationBaseUrl("https://user@api.example.com/v1"))
        // java.net.URI keeps "host:abc" as a registry authority without a host.
        assertEquals(TranslationBaseUrlCheck.MissingHost, checkTranslationBaseUrl("http://host:abc/v1"))
    }

    @Test
    fun `valid http and https bases are ok`() {
        assertEquals(TranslationBaseUrlCheck.Ok, checkTranslationBaseUrl("http://192.168.1.10:11434/v1"))
        assertEquals(TranslationBaseUrlCheck.Ok, checkTranslationBaseUrl("https://api.example.com/v1"))
        assertEquals(TranslationBaseUrlCheck.Ok, checkTranslationBaseUrl("http://[::1]:8080/v1"))
        assertEquals(TranslationBaseUrlCheck.Ok, checkTranslationBaseUrl("http://h:4000"))
        assertEquals(TranslationBaseUrlCheck.Ok, checkTranslationBaseUrl("http://h/openai/v1"))
        assertEquals(TranslationBaseUrlCheck.Ok, checkTranslationBaseUrl("http://h/v1/chat/completions"))
        assertEquals(TranslationBaseUrlCheck.Ok, checkTranslationBaseUrl("  https://api.example.com/v1  "))
    }

    @Test
    fun `a blank field is not an error`() {
        assertEquals(TranslationBaseUrlCheck.Ok, checkTranslationBaseUrl(""))
        assertEquals(TranslationBaseUrlCheck.Ok, checkTranslationBaseUrl("   "))
    }
}
