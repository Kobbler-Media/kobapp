package dev.kobbler.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two server streams frame their events differently, and one parser reads both:
 * the library stream names them in the SSE `event:` field, the per-session debrid
 * stream sends nameless frames discriminated by a `type` field in the payload.
 */
class SseFrameTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(name: String?, data: String): LibraryEventFrame? =
        parseFrameForTest(json, name, data)

    @Test
    fun aLibraryFrameTakesItsKindFromThePayload() {
        val frame = parse("skip_segments_ready", """{"kind":"skip_segments_ready","file_id":"abc"}""")
        assertEquals("skip_segments_ready", frame?.kind)
    }

    @Test
    fun aDebridFrameHasNoNameAndIsTypedInline() {
        val frame = parse(null, """{"type":"thumbnails_ready","thumbnails_url":"/a.vtt"}""")
        assertEquals("thumbnails_ready", frame?.kind)
        assertEquals("/a.vtt", (frame?.data?.get("thumbnails_url") as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun aJobFrameFallsBackToTheEventName() {
        // Job and ingest frames ride the library stream carrying neither discriminator.
        val frame = parse("job", """{"job_id":"1","state":"running"}""")
        assertEquals("job", frame?.kind)
    }

    @Test
    fun anUnnamedUntypedFrameIsDropped() {
        assertNull(parse(null, """{"nothing":"useful"}"""))
    }

    @Test
    fun malformedJsonIsDroppedRatherThanThrown() {
        // One bad frame must not take the stream down.
        assertNull(parse("whatever", "not json at all"))
        assertNull(parse("whatever", """["an","array"]"""))
    }

    @Test
    fun theDataObjectIsHandedThroughIntact() {
        val frame = parse(null, """{"type":"skip_segments_ready","segments":[{"segment_type":"intro"}]}""")
        assertEquals(true, frame?.data?.get("segments") is kotlinx.serialization.json.JsonArray)
        assertEquals(true, frame?.data is JsonObject)
    }
}

class EventIdFieldTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun frame(data: String) = parseFrameForTest(json, "x", data)!!

    @Test
    fun aStringIdIsRead() {
        assertEquals("m1", frame("""{"kind":"movie_updated","movie_id":"m1"}""").idField("movie_id"))
    }

    @Test
    fun anAbsentFieldIsNull() {
        // library_updated omits book_id entirely when the change was library-wide.
        assertNull(frame("""{"kind":"library_updated"}""").idField("book_id"))
    }

    @Test
    fun aNonStringFieldIsNullRatherThanThrowing() {
        assertNull(frame("""{"kind":"x","movie_id":{"nested":1}}""").idField("movie_id"))
        assertNull(frame("""{"kind":"x","movie_id":null}""").idField("movie_id"))
    }
}
