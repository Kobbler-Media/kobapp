package dev.kobbler.app.ui.reader

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val SynthesizePath = "/api/v1/admin/tts/synthesize"
private const val StatusPath = "/api/v1/admin/tts/status"

/**
 * What a render that produced no audio is allowed to do next.
 *
 * A chapter that came back as nothing but an error is not a chapter that was read, and
 * treating it as one walks the reader through the rest of the book at network speed —
 * one failed request per chapter, each one advancing the page.
 */
class TtsErrorStreamTest {

    private class Harness(routes: Map<String, (HttpExchange) -> String>) : AutoCloseable {
        val stub = StubServer(routes)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = KobblerApi(HttpClient(), stub.url, "token")
        val controller = TtsController(api, scope)
        var finished = 0

        init {
            controller.onFinished = { finished++ }
        }

        fun readAndSettle(text: String = "Some text to read.") {
            controller.start(text)
            runBlocking {
                withTimeoutOrNull(10_000) {
                    while (controller.state.value != TtsState.IDLE) delay(10)
                }
            }
        }

        override fun close() {
            scope.cancel()
            stub.close()
        }
    }

    private fun errorThenDone(message: String) =
        """{"message":"$message","paragraph_index":0,"type":"error"}""" + """{"type":"done"}"""

    @Test
    fun `an error before any audio does not finish the chapter`() {
        val message = "TTS HTTP error: error decoding response body"
        Harness(mapOf(SynthesizePath to { _: HttpExchange -> errorThenDone(message) })).use { h ->
            h.readAndSettle()
            assertEquals(0, h.finished, "a chapter that produced no audio was reported as read")
            assertEquals(message, h.controller.error.value, "the reason the audio stopped never reached the reader")
            assertEquals(TtsState.IDLE, h.controller.state.value)
        }
    }

    @Test
    fun `a stream carrying nothing at all says so`() {
        Harness(mapOf(SynthesizePath to { _: HttpExchange -> """{"type":"done"}""" })).use { h ->
            h.readAndSettle()
            assertEquals(0, h.finished)
            assertNotNull(h.controller.error.value, "a silent chapter passed for a read one")
        }
    }

    /**
     * The voice is the server's to resolve: it holds the active preset and the minted
     * narrator, and an engine handed no name picks a fresh speaker per request. Naming
     * whichever voice happened to be first in the list overrides both, and it only
     * starts doing so once the list has loaded — so the first chapter and the second
     * come back in different voices.
     */
    @Test
    fun `the client never names a voice the user did not choose`() {
        val routes = mapOf(
            StatusPath to { _: HttpExchange ->
                """{"enabled":true,"voices":[{"id":"af_heart","name":"Aria"},{"id":"kobbler-narrator","name":"Narrator"}]}"""
            },
            SynthesizePath to { _: HttpExchange -> """{"type":"done"}""" },
        )
        Harness(routes).use { h ->
            h.controller.loadVoices()
            runBlocking {
                withTimeoutOrNull(10_000) {
                    while (h.controller.voices.value.isEmpty()) delay(10)
                }
            }
            assertNull(h.controller.selectedVoice, "the client pinned a voice on its own")

            h.readAndSettle()
            val body = h.stub.requestBodies[SynthesizePath]
            assertNotNull(body)
            assertFalse("voice" in body, "the request named a voice: $body")
        }
    }
}
