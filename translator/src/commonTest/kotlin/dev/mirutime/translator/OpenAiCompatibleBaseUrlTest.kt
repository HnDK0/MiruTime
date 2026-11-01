package dev.mirutime.translator

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAiCompatibleBaseUrlTest {

    @Test
    fun `a pasted full endpoint is not doubled`() {
        assertEquals(
            "http://h/v1/chat/completions",
            openAiCompatibleChatEndpoint("http://h/v1/chat/completions"),
        )
        assertEquals(
            "http://h/v1/chat/completions",
            openAiCompatibleChatEndpoint("http://h/v1/chat/completions/"),
        )
        assertEquals(
            "http://h/v1/CHAT/COMPLETIONS",
            openAiCompatibleChatEndpoint("http://h/v1/CHAT/COMPLETIONS"),
        )
    }

    @Test
    fun `an exotic path ending with the segment stays as-is`() {
        assertEquals(
            "http://h/api/chat/completions",
            openAiCompatibleChatEndpoint("http://h/api/chat/completions"),
        )
    }

    @Test
    fun `the segment is appended once when the base lacks it`() {
        assertEquals("http://h/chat/completions", openAiCompatibleChatEndpoint("http://h"))
        assertEquals(
            "http://h/openai/v1/chat/completions",
            openAiCompatibleChatEndpoint("http://h/openai/v1"),
        )
        assertEquals(
            "http://h/chat/completions/v1/chat/completions",
            openAiCompatibleChatEndpoint("http://h/chat/completions/v1"),
        )
    }

    @Test
    fun `the base path stays free-form`() {
        assertEquals(
            "http://h:4000/v1/chat/completions",
            openAiCompatibleChatEndpoint("http://h:4000/v1/"),
        )
        assertEquals(
            "http://192.168.1.10:11434/v1/chat/completions",
            openAiCompatibleChatEndpoint("  http://192.168.1.10:11434/v1  "),
        )
    }

    @Test
    fun `no v1 is added to a host-only base`() {
        assertEquals("http://h:4000/chat/completions", openAiCompatibleChatEndpoint("http://h:4000"))
    }
}
