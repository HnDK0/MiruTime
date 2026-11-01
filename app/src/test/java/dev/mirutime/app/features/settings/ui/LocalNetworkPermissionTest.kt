package dev.mirutime.app.features.settings.ui

import dev.mirutime.app.R
import dev.mirutime.app.features.translation.data.TranslationBaseUrlCheck
import dev.mirutime.app.features.translation.data.translationEndpointError
import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {
    @Test
    fun `private endpoint without the android 17 grant shows the required message`() {
        assertEquals(
            R.string.st_tr_local_network_required,
            endpointErrorRes(
                translationEndpointError(
                    ProviderId.OPENAI_COMPATIBLE,
                    "http://10.0.2.2:18080/v1",
                    sdkInt = 37,
                    granted = false,
                ),
            ),
        )
    }

    @Test
    fun `no message when granted, on older android or for a public endpoint`() {
        assertNull(
            endpointErrorRes(
                translationEndpointError(
                    ProviderId.OPENAI_COMPATIBLE,
                    "http://10.0.2.2:18080/v1",
                    sdkInt = 37,
                    granted = true,
                ),
            ),
        )
        assertNull(
            endpointErrorRes(
                translationEndpointError(
                    ProviderId.OPENAI_COMPATIBLE,
                    "http://10.0.2.2:18080/v1",
                    sdkInt = 36,
                    granted = false,
                ),
            ),
        )
        assertNull(
            endpointErrorRes(
                translationEndpointError(
                    ProviderId.OPENAI_COMPATIBLE,
                    "https://api.openai.com/v1",
                    sdkInt = 37,
                    granted = false,
                ),
            ),
        )
    }

    @Test
    fun `the screen maps both OpenAI providers through the shared policy`() {
        assertEquals(
            R.string.st_tr_cleartext_forbidden,
            endpointErrorRes(
                translationEndpointError(
                    ProviderId.OPENAI,
                    "http://api.example.com/v1",
                    sdkInt = 37,
                    granted = true,
                ),
            ),
        )
        assertEquals(
            R.string.st_tr_local_network_required,
            endpointErrorRes(
                translationEndpointError(
                    ProviderId.OPENAI,
                    "http://10.0.2.2:18080/v1",
                    sdkInt = 37,
                    granted = false,
                ),
            ),
        )
    }

    @Test
    fun `every syntax reason maps to its own message`() {
        assertEquals(
            R.string.st_tr_url_missing_scheme,
            baseUrlIssueRes(TranslationBaseUrlCheck.MissingScheme),
        )
        assertEquals(
            R.string.st_tr_url_unsupported_scheme,
            baseUrlIssueRes(TranslationBaseUrlCheck.UnsupportedScheme),
        )
        assertEquals(
            R.string.st_tr_url_missing_host,
            baseUrlIssueRes(TranslationBaseUrlCheck.MissingHost),
        )
        assertEquals(
            R.string.st_tr_url_invalid,
            baseUrlIssueRes(TranslationBaseUrlCheck.InvalidUrl),
        )
        assertNull(baseUrlIssueRes(TranslationBaseUrlCheck.Ok))
    }

    @Test
    fun `syntax wins over the cleartext and permission messages`() {
        assertEquals(
            R.string.st_tr_url_missing_scheme,
            baseUrlFieldErrorRes(
                TranslationBaseUrlCheck.MissingScheme,
                TranslationError.CleartextNotAllowed(ProviderId.OPENAI_COMPATIBLE),
            ),
        )
        assertEquals(
            R.string.st_tr_cleartext_forbidden,
            baseUrlFieldErrorRes(
                TranslationBaseUrlCheck.Ok,
                TranslationError.CleartextNotAllowed(ProviderId.OPENAI_COMPATIBLE),
            ),
        )
        assertNull(baseUrlFieldErrorRes(TranslationBaseUrlCheck.Ok, null))
    }

    @Test
    fun `settings open only after the system stops prompting`() {
        assertFalse(shouldOpenAppSettings(denied = false, showRationale = false))
        assertFalse(shouldOpenAppSettings(denied = false, showRationale = true))
        assertFalse(shouldOpenAppSettings(denied = true, showRationale = true))
        assertTrue(shouldOpenAppSettings(denied = true, showRationale = false))
    }
}
