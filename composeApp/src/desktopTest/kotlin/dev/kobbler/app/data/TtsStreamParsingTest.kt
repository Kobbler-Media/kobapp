package dev.kobbler.app.data

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SynthesizePath = "/api/v1/admin/tts/synthesize"

/**
 * What the reader survives on the wire.
 *
 * The stream is a sequence of JSON objects, and nothing in the protocol promises one
 * per line: a failing render tails off as `}{` with no separator at all, and the whole
 * response is a single flush. A reader that frames on newlines drops exactly the two
 * events that say why the audio stopped.
 */
class TtsStreamParsingTest {

    private fun eventsFrom(body: String): List<TtsStreamEvent> {
        val events = mutableListOf<TtsStreamEvent>()
        StubServer(mapOf(SynthesizePath to { _: HttpExchange -> body })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            runBlocking { api.streamTtsSynthesize("some text", null, 1.0) { events += it } }
        }
        return events
    }

    /** The exact tail of the failing response that took the reader down. */
    @Test
    fun `a concatenated error and done are both delivered`() {
        val events = eventsFrom(
            """
            {
              "message": "TTS HTTP error: error decoding response body",
              "paragraph_index": 0,
              "type": "error"
            }{
              "type": "done"
            }
            """.trimIndent(),
        )
        assertEquals(2, events.size, "framing lost events: $events")
        val error = events[0] as TtsStreamEvent.Error
        assertEquals("TTS HTTP error: error decoding response body", error.message)
        assertEquals(0, error.paragraphIndex)
        assertEquals(false, error.fatal)
        assertEquals(TtsStreamEvent.Done, events[1])
    }

    @Test
    fun `newline framed events still parse`() {
        val events = eventsFrom(
            """{"type":"paragraph_start","index":0,"text":"Hi there.","word_offset":0,""" +
                """"audio_offset_ms":0,"sample_rate":24000,"speed":1.0}""" + "\n" +
                """{"type":"alignment_final","paragraph_index":0,"audio_total_ms":900,""" +
                """"words":[{"word_index":0,"text":"Hi","t0_ms":0,"t1_ms":400}]}""" + "\n" +
                """{"type":"done"}""" + "\n",
        )
        assertEquals(3, events.size, "$events")
        assertEquals("Hi there.", (events[0] as TtsStreamEvent.ParagraphStart).text)
        assertEquals(1, (events[1] as TtsStreamEvent.AlignmentFinal).words.size)
        assertEquals(TtsStreamEvent.Done, events[2])
    }

    /**
     * The chunk anchor is the position signal that does not need the aligner,
     * so it has to survive parsing even on a stream that carries no alignment
     * at all.
     */
    @Test
    fun `chunk anchors parse`() {
        val events = eventsFrom(
            """{"type":"chunk","paragraph_index":1,"index":2,"chunks_total":5,""" +
                """"word_offset":210,"word_count":31,"audio_offset_ms":69500,""" +
                """"text":"A sentence or three."}""" + "\n" +
                """{"type":"done"}""" + "\n",
        )
        assertEquals(2, events.size, "$events")
        val anchor = events[0] as TtsStreamEvent.Chunk
        assertEquals(1, anchor.paragraphIndex)
        assertEquals(2, anchor.index)
        assertEquals(5, anchor.chunksTotal)
        assertEquals(210, anchor.wordOffset)
        assertEquals(31, anchor.wordCount)
        assertEquals(69500, anchor.audioOffsetMs)
        assertEquals("A sentence or three.", anchor.text)
    }

    /** A type this build has never heard of must cost nothing but an ignored event. */
    @Test
    fun `an unknown event type is not fatal`() {
        val events = eventsFrom(
            """{"type":"prosody_hint","paragraph_index":0,"stress":[1,2]}""" +
                """{"type":"done"}""",
        )
        assertEquals(listOf(TtsStreamEvent.Other, TtsStreamEvent.Done), events)
    }

    /** Braces inside a message are text, not framing. */
    @Test
    fun `a brace inside a string does not split an object`() {
        val events = eventsFrom(
            """{"type":"error","paragraph_index":1,"message":"bad shape }{ near \"end\"","fatal":true}""" +
                """{"type":"done"}""",
        )
        val error = events[0] as TtsStreamEvent.Error
        assertEquals("""bad shape }{ near "end"""", error.message)
        assertTrue(error.fatal)
        assertEquals(TtsStreamEvent.Done, events[1])
    }

    /** Audio events run to hundreds of kilobytes, so an object spans many socket reads. */
    @Test
    fun `an event larger than one read is reassembled`() {
        val pcmB64 = "QUJDRERF".repeat(20_000)
        val events = eventsFrom(
            """{"type":"audio","paragraph_index":0,"pcm_b64":"$pcmB64"}""" + """{"type":"done"}""",
        )
        val audio = events[0] as TtsStreamEvent.AudioPcm
        assertEquals(pcmB64.length / 4 * 3, audio.pcm.size)
        assertEquals(TtsStreamEvent.Done, events[1])
    }

    /** A body cut off mid-object loses that object and nothing before it. */
    @Test
    fun `a truncated tail does not discard what arrived`() {
        val events = eventsFrom("""{"type":"done"}{"type":"err""")
        assertEquals(listOf(TtsStreamEvent.Done), events)
    }
}
