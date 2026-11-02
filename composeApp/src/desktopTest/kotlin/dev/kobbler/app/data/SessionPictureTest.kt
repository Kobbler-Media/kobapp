package dev.kobbler.app.data

import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionPictureTest {

    private fun session(extra: String) =
        """{"session_id":"s1","method":"direct_play","direct_url":"/d","duration_seconds":60,"video_width":1920,"video_height":1080$extra}"""

    private fun opened(body: String) = StubServer(mapOf("/api/v1/admin/playback/sessions" to { _ -> body })).use { stub ->
        runBlocking { KobblerApi(HttpClient(), stub.url, "token").getMovieStreamSession("m1") }
    }

    @Test
    fun `a session carries the picture inside its frame`() {
        val picture = opened(session(""","picture":{"x":0.0,"y":0.12,"width":1.0,"height":0.76}"""))?.picture
        assertEquals(PictureArea(0.0, 0.12, 1.0, 0.76), picture)
        assertNull(opened(session(""))?.picture)
    }
}
