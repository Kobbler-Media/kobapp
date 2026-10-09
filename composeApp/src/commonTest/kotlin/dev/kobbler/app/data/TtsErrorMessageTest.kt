package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A rejected synthesis request used to surface as "TTS returned no audio" — the stream
 * decoder skipped the error body because it had no `type` field, so the one useful fact
 * (the backend's 50k character cap) never reached the reader.
 */
class TtsErrorMessageTest {

    @Test
    fun prefersTheServersErrorField() {
        assertEquals(
            "Text must be 1-50000 characters",
            ttsErrorMessage(400, "Bad Request", """{"error":"Text must be 1-50000 characters"}"""),
        )
    }

    @Test
    fun fallsBackToStatusWhenBodyIsUnhelpful() {
        val cases = listOf("", "   ", "<html>502</html>", """{"detail":"nope"}""")
        cases.forEach { body ->
            assertEquals("HTTP 502 Bad Gateway", ttsErrorMessage(502, "Bad Gateway", body), "body=$body")
        }
    }

    @Test
    fun acceptsAMessageFieldToo() {
        assertEquals("voice not found", ttsErrorMessage(404, "Not Found", """{"message":"voice not found"}"""))
    }
}
