package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryEventFrame
import dev.kobbler.app.data.PictureArea
import dev.kobbler.app.platform.Settings
import dev.kobbler.kobplayer.PictureCrop
import dev.kobbler.kobplayer.Loudness
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
        applyDebridSessionFrame(state, api, "s1", frame("loudness", """{"lufs":-21.5,"downmix_peak_db":-2.0}"""))
        assertEquals(listOf("Opening"), state.chapters.map { it.title })
        assertEquals(Loudness(-21.5, -2.0), state.loudness)
    }

    @Test
    fun `black bars measured mid-play are trimmed at once, and a picture that fills the frame is left whole`() = runBlocking {
        val previous = Settings.getString(KEY_TRIM_BLACK_BARS)
        Settings.putString(KEY_TRIM_BLACK_BARS, "true")
        try {
            val state = GlobalPlayerState()
            val player = FakePlayer()
            applyDebridSessionFrame(state, api, "s1", frame("picture", """{"picture":{"x":0.0,"y":0.12,"width":1.0,"height":0.76}}"""), player)
            assertEquals(PictureArea(0.0, 0.12, 1.0, 0.76), state.picture)
            assertEquals(PictureCrop(0.0, 0.12, 1.0, 0.76), player.croppedTo)

            applyDebridSessionFrame(state, api, "s1", frame("picture", """{"picture":{"x":0.0,"y":0.0,"width":1.0,"height":1.0}}"""), player)
            assertEquals(null, state.picture)
            assertEquals(null, player.croppedTo)
        } finally {
            if (previous != null) Settings.putString(KEY_TRIM_BLACK_BARS, previous) else Settings.remove(KEY_TRIM_BLACK_BARS)
        }
    }
}
