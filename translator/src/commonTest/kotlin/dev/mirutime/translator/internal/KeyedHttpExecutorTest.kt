package dev.mirutime.translator.internal

import dev.mirutime.translator.ProviderId
import dev.mirutime.translator.TranslationError
import dev.mirutime.translator.TranslationFailure
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KeyedHttpExecutorTest {

    @Test
    fun `transport preflight failure is not retried as a network error`() = runTest {
        var calls = 0
        val executor = KeyedHttpExecutor(
            keys = KeyRotator(listOf("key")),
            provider = ProviderId.OPENAI_COMPATIBLE,
            maxAttempts = 3,
        )

        val failure = assertFailsWith<TranslationFailure> {
            executor.execute<HttpResponse>(
                request = {
                    calls++
                    throw TranslationFailure(
                        TranslationError.LocalNetworkRequired(ProviderId.OPENAI_COMPATIBLE),
                        preflight = true,
                    )
                },
                parse = { error("unreachable") },
            )
        }

        assertEquals(1, calls)
        assertIs<TranslationError.LocalNetworkRequired>(failure.error)
    }

    @Test
    fun `provider timeout still follows the retry ladder`() = runTest {
        var calls = 0
        val executor = KeyedHttpExecutor(
            keys = KeyRotator(listOf("key")),
            provider = ProviderId.OPENAI,
            maxAttempts = 3,
        )

        val failure = assertFailsWith<TranslationFailure> {
            executor.execute<HttpResponse>(
                request = {
                    calls++
                    throw TranslationFailure(TranslationError.Network(ProviderId.OPENAI, "timed out"))
                },
                parse = { error("unreachable") },
            )
        }

        assertEquals(3, calls)
        assertIs<TranslationError.Network>(failure.error)
    }
}
