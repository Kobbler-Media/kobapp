package dev.kobbler.app.ui.reader

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.ui.player.FakeSender
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Read-aloud with a receiver connected: heard there, followed and worked from here. */
class TtsCastTest {

    private val aligned = listOf(
        """{"type":"paragraph_start","index":0,"text":"One two three four","word_offset":0,"audio_offset_ms":0,"sample_rate":24000,"speed":1.0}""",
        """{"type":"alignment_final","paragraph_index":0,"audio_total_ms":2000,"words":[""" +
            (0..3).joinToString(",") { """{"word_index":$it,"text":"w$it","t0_ms":${it * 500},"t1_ms":${it * 500 + 500}}""" } +
            "]}",
        """{"type":"done"}""",
    )

    private fun routes(events: List<String>, hold: CountDownLatch? = null) = mapOf(
        "/api/v1/admin/tts/cast" to { _: HttpExchange -> """{"id":"r1"}""" },
        "/api/v1/admin/playback/tokens" to { _: HttpExchange -> """{"token":"cast-tok"}""" },
        "/api/v1/admin/tts/cast/r1/events" to { _: HttpExchange ->
            hold?.await(10, TimeUnit.SECONDS)
            events.joinToString("\n", postfix = "\n")
        },
    )

    private class Rig(val stub: StubServer, val cast: FakeSender, val tts: TtsController, val scope: CoroutineScope) {
        var finished = 0
        fun inits() = cast.payloads.filter { it.first == "INIT_TTS" }.map { Json.parseToJsonElement(it.second).jsonObject }
    }

    private fun casting(events: List<String>, hold: CountDownLatch? = null, test: suspend Rig.() -> Unit) {
        StubServer(routes(events, hold)).use { stub ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val cast = FakeSender().apply { connect("Kitchen speaker") }
            val tts = TtsController(KobblerApi(HttpClient(), stub.url, "token"), scope, cast)
            val rig = Rig(stub, cast, tts, scope)
            tts.onFinished = { rig.finished++ }
            tts.setNowPlayingMetadata("Treehouse", "Chapter 3", "/api/v1/admin/books/b1/cover")
            try {
                runBlocking { withTimeout(15_000) { rig.test() } }
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("never: $what")
            delay(20)
        }
    }

    @Test
    fun `a read started beside a receiver is heard there and followed here`() = casting(aligned) {
        tts.start("One two three four")
        eventually("INIT_TTS") { inits().isNotEmpty() }

        val init = inits().single()
        assertEquals("${stub.url}/api/v1/admin/tts/cast/r1/audio?token=cast-tok", init["url"]!!.jsonPrimitive.content)
        assertEquals("Treehouse", init["title"]!!.jsonPrimitive.content)
        assertEquals("Chapter 3", init["subtitle"]!!.jsonPrimitive.content)
        assertEquals("${stub.url}/api/v1/admin/books/b1/cover?token=cast-tok", init["coverUrl"]!!.jsonPrimitive.content)
        assertTrue("One two three four" in stub.requestBodies["/api/v1/admin/tts/cast"].orEmpty())

        cast.say(CastEvent.Position(positionSec = 1.2, durationSec = 0.0, isPlaying = false))
        eventually("the word at 1.2 s") { tts.currentWordIndex.value == 2 }
        assertEquals(TtsState.PAUSED, tts.state.value)

        cast.say(CastEvent.Position(positionSec = 1.2, durationSec = 0.0, isPlaying = true))
        eventually("the clock to run on between reports") { tts.currentWordIndex.value == 3 }
        assertEquals(TtsState.PLAYING, tts.state.value)

        tts.pause()
        assertEquals(TtsState.PAUSED, tts.state.value)
        tts.resume()
        eventually("both taps to reach the receiver") { cast.toggles == 2 }

        cast.say(CastEvent.TtsFinished)
        eventually("the chapter to finish") { finished == 1 }
        assertEquals(TtsState.IDLE, tts.state.value)
    }

    @Test
    fun `a buffered engine's sentences are followed on the receiver's clock`() = casting(
        listOf(
            """{"type":"sentence","index":0,"text":"First.","speed":1.0,"audio_offset_ms":0,"duration_ms":1000}""",
            """{"type":"sentence","index":1,"text":"Second.","speed":1.0,"audio_offset_ms":1000,"duration_ms":1000}""",
            """{"type":"done"}""",
        ),
    ) {
        tts.start("First. Second.")
        eventually("INIT_TTS") { inits().isNotEmpty() }

        cast.say(CastEvent.Position(positionSec = 0.4, durationSec = 0.0, isPlaying = false))
        eventually("the first sentence") { tts.currentSentence.value == "First." }
        cast.say(CastEvent.Position(positionSec = 1.5, durationSec = 0.0, isPlaying = false))
        eventually("the second sentence") { tts.currentSentence.value == "Second." }
        assertEquals(1, tts.currentSentenceIndex.value)
    }

    @Test
    fun `a read the server gave up on is not taken for a finished chapter`() = casting(
        listOf(
            aligned[0],
            """{"type":"error","paragraph_index":0,"message":"koblem went away","fatal":true}""",
            """{"type":"done"}""",
        ),
    ) {
        tts.start("One two three four")
        eventually("INIT_TTS") { inits().isNotEmpty() }
        eventually("the error") { tts.error.value == "koblem went away" }

        cast.say(CastEvent.Position(positionSec = 0.5, durationSec = 0.0, isPlaying = true))
        cast.say(CastEvent.TtsFinished)
        eventually("the read to end") { tts.state.value == TtsState.IDLE }
        assertEquals(0, finished)
    }

    @Test
    fun `a receiver that runs dry before the read is over is reopened where it was`() {
        val hold = CountDownLatch(1)
        casting(aligned, hold) {
            tts.start("One two three four")
            eventually("INIT_TTS") { inits().isNotEmpty() }
            cast.say(CastEvent.Position(positionSec = 1.25, durationSec = 0.0, isPlaying = true))
            cast.say(CastEvent.Position(positionSec = 1.25, durationSec = 0.0, isPlaying = false))
            cast.say(CastEvent.TtsFinished)
            eventually("a second INIT_TTS") { inits().size == 2 }

            assertEquals(1250L, inits()[1]["startMs"]!!.jsonPrimitive.long)
            assertEquals(0, finished)
            hold.countDown()
            eventually("the chapter to finish once the whole read is in") {
                runBlocking { cast.say(CastEvent.TtsFinished) }
                finished > 0
            }
            assertEquals(1, finished)
        }
    }

    @Test
    fun `a receiver lost mid-read hands the read back from the word it was on`() = casting(aligned) {
        var handedBack: Int? = null
        tts.onHandoff = { handedBack = it }
        tts.start("One two three four")
        eventually("INIT_TTS") { inits().isNotEmpty() }
        cast.say(CastEvent.Position(positionSec = 1.2, durationSec = 0.0, isPlaying = false))
        eventually("the word at 1.2 s") { tts.currentWordIndex.value == 2 }

        cast.disconnect()
        eventually("the handback") { handedBack != null }
        assertEquals(2, handedBack)
    }

    @Test
    fun `stopping the read stops the receiver, and losing it with nowhere to go stops here`() = casting(aligned) {
        tts.start("One two three four")
        eventually("INIT_TTS") { inits().isNotEmpty() }
        tts.stop()
        eventually("TTS_STOP") { "TTS_STOP" in cast.sent }
        assertEquals(TtsState.IDLE, tts.state.value)

        tts.start("One two three four")
        eventually("a second INIT_TTS") { inits().size == 2 }
        cast.disconnect()
        eventually("the read to stop") { tts.state.value == TtsState.IDLE }
        assertFalse(finished > 0)
        assertNotNull(tts.error.value)
    }
}
