package dev.kobbler.app.ui.reader

import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.FakePlayer
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Listening along turns the book to where the narrator is. */
class AudiobookSyncControllerTest {

    @Test
    fun `the page follows the narrator into the chapter the sync point names, in the file being played`() = runBlocking {
        // f2's point sorts later by seconds; a lookup that ignores the file would take it.
        val readAlong = """{"sync_points":[
            {"audio_file_id":"f1","audio_seconds":5.0,"ebook_cfi":"epubcfi(/6/8!/4/2/1:0)","position":{"chapter":3,"block":0,"offset":0}},
            {"audio_file_id":"f2","audio_seconds":5.5,"ebook_cfi":"epubcfi(/6/16!/4/2/1:0)","position":{"chapter":7,"block":0,"offset":0}}
        ],"transcript_segments":[],"audio_file_ids":["f1","f2"]}"""
        val server = StubServer(
            mapOf(
                "/api/v1/admin/books/b1/read-along-data" to { _ -> readAlong },
                "/api/v1/admin/books/b1/audio-files" to { _ ->
                    """[{"id":"f1","file_index":0,"format":"mp3","file_name":"1.mp3"},{"id":"f2","file_index":1,"format":"mp3","file_name":"2.mp3"}]"""
                },
                "/api/v1/admin/playback/tokens" to { _ -> """{"token":"pt"}""" },
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = AudiobookSyncController(KobblerApi(HttpClient(), server.url, "t"), scope)
            val player = FakePlayer()
            controller.start("b1", player, chapterCount = 10)
            player.seekTo(6_000)
            assertEquals(3, withTimeout(5_000) { controller.suggestedChapter.first { it >= 0 } })
        } finally {
            scope.cancel()
            server.close()
        }
    }
}
