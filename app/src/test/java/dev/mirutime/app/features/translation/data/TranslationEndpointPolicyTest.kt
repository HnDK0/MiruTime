package dev.mirutime.app.features.translation.data

import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationEndpointPolicyTest {

    @Test
    fun `http public endpoint is rejected for both editable OpenAI URLs`() {
        assertEquals(
            TranslationError.CleartextNotAllowed(ProviderId.OPENAI_COMPATIBLE),
            endpoint("http://api.example.com/v1", sdkInt = 37, granted = true),
        )
        assertEquals(
            TranslationError.CleartextNotAllowed(ProviderId.OPENAI),
            translationEndpointError(
                provider = ProviderId.OPENAI,
                baseUrl = "http://api.example.com/v1",
                sdkInt = 37,
                granted = true,
            ),
        )
    }

    @Test
    fun `http local endpoint is accepted when the grant exists`() {
        assertNull(endpoint("http://10.0.2.2:18080/v1", sdkInt = 37, granted = true))
        assertNull(
            translationEndpointError(
                provider = ProviderId.OPENAI,
                baseUrl = "http://192.168.1.10:8080/v1",
                sdkInt = 37,
                granted = true,
            ),
        )
    }

    @Test
    fun `https public endpoint is accepted`() {
        assertNull(endpoint("https://api.example.com/v1", sdkInt = 37, granted = false))
    }

    @Test
    fun `missing grant on a local endpoint is a typed error`() {
        assertEquals(
            TranslationError.LocalNetworkRequired(ProviderId.OPENAI_COMPATIBLE),
            endpoint("https://192.168.1.10:11434/v1", sdkInt = 37, granted = false),
        )
    }

    @Test
    fun `the OpenAI provider also needs the local grant`() {
        assertEquals(
            TranslationError.LocalNetworkRequired(ProviderId.OPENAI),
            translationEndpointError(
                provider = ProviderId.OPENAI,
                baseUrl = "http://10.0.2.2:18080/v1",
                sdkInt = 37,
                granted = false,
            ),
        )
    }

    @Test
    fun `url preflight applies the owning provider policy`() {
        assertEquals(
            TranslationError.CleartextNotAllowed(ProviderId.OPENAI),
            translationUrlError(
                url = "http://api.example.com/v1/v1/responses",
                openAiBaseUrl = "http://api.example.com/v1",
                compatibleBaseUrl = "",
                sdkInt = 37,
                granted = true,
            ),
        )
        assertEquals(
            TranslationError.LocalNetworkRequired(ProviderId.OPENAI_COMPATIBLE),
            translationUrlError(
                url = "http://192.168.1.10:11434/v1/chat/completions",
                openAiBaseUrl = "https://api.openai.com",
                compatibleBaseUrl = "http://192.168.1.10:11434/v1",
                sdkInt = 37,
                granted = false,
            ),
        )
        assertNull(
            translationUrlError(
                url = "https://translate.googleapis.com/translate_a/single",
                openAiBaseUrl = "https://api.openai.com",
                compatibleBaseUrl = "http://192.168.1.10:11434/v1",
                sdkInt = 37,
                granted = false,
            ),
        )
    }

    @Test
    fun `a malformed base URL fails the preflight before cleartext or the network`() {
        assertEquals(
            TranslationError.InvalidRequest(ProviderId.OPENAI_COMPATIBLE, "invalid base URL: MissingScheme"),
            endpoint("192.168.1.1/v1", sdkInt = 37, granted = false),
        )
        assertEquals(
            TranslationError.InvalidRequest(ProviderId.OPENAI, "invalid base URL: UnsupportedScheme"),
            translationEndpointError(
                provider = ProviderId.OPENAI,
                baseUrl = "ftp://192.168.1.1/v1",
                sdkInt = 37,
                granted = true,
            ),
        )
        // Syntax wins over the cleartext rule; the typed reason stays visible.
        assertEquals(
            TranslationError.InvalidRequest(ProviderId.OPENAI_COMPATIBLE, "invalid base URL: InvalidUrl"),
            endpoint("http://api.example.com/v1?key=x", sdkInt = 37, granted = true),
        )
        assertEquals(
            TranslationError.InvalidRequest(ProviderId.OPENAI_COMPATIBLE, "invalid base URL: MissingHost"),
            endpoint("http:///v1", sdkInt = 37, granted = true),
        )
    }

    @Test
    fun `a pasted full chat completions endpoint still matches the preflight`() {
        assertEquals(
            TranslationError.LocalNetworkRequired(ProviderId.OPENAI_COMPATIBLE),
            translationUrlError(
                url = "http://192.168.1.10:11434/v1/chat/completions",
                openAiBaseUrl = "https://api.openai.com",
                compatibleBaseUrl = "http://192.168.1.10:11434/v1/chat/completions/",
                sdkInt = 37,
                granted = false,
            ),
        )
        assertEquals(
            TranslationError.InvalidRequest(ProviderId.OPENAI_COMPATIBLE, "invalid base URL: MissingScheme"),
            translationUrlError(
                url = "192.168.1.10:11434/v1/chat/completions",
                openAiBaseUrl = "",
                compatibleBaseUrl = "192.168.1.10:11434/v1",
                sdkInt = 37,
                granted = true,
            ),
        )
        assertNull(
            translationUrlError(
                url = "http://192.168.1.10:11434/v1/chat/completions",
                openAiBaseUrl = "",
                compatibleBaseUrl = "http://192.168.1.10:11434/v1/chat/completions",
                sdkInt = 37,
                granted = true,
            ),
        )
    }

    @Test
    fun `other providers are not affected by the endpoint rules`() {
        assertNull(
            translationEndpointError(
                provider = ProviderId.GEMINI,
                baseUrl = "http://api.example.com/v1",
                sdkInt = 37,
                granted = false,
            ),
        )
    }

    private fun endpoint(baseUrl: String, sdkInt: Int, granted: Boolean): TranslationError? =
        translationEndpointError(
            provider = ProviderId.OPENAI_COMPATIBLE,
            baseUrl = baseUrl,
            sdkInt = sdkInt,
            granted = granted,
        )
}
