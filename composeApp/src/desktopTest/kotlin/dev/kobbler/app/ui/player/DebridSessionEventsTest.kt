package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryEventFrame
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** A debrid stream learns its chapters and loudness while it plays; a library file starts with them. */
class DebridSessionEventsTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun frame(kind: String, data: String) =
        LibraryEventFrame(kind, Json.parseToJsonElement(data) as JsonObject)

    @Test
    fun `chapters and loudness arriving mid-play reach the player`() = runBlocking {
        val state = GlobalPlayerState()
        applyDebridSessionFrame(
            state, api, "s1",
            frame("chapters", """{"chapters":[{"index":0,"title":"Opening","start_seconds":0.0,"end_seconds":90.0}]}"""),
        )
        applyDebridSessionFrame(state, api, "s1", frame("loudness", """{"lufs":-21.5}"""))
        assertEquals(listOf("Opening"), state.chapters.map { it.title })
        assertEquals(-21.5, state.lufs)
    }
}
