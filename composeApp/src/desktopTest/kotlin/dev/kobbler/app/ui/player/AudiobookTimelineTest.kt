package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AudiobookTimelineTest {

    private fun file(id: String, index: Int) =
        AudioFileDto(id = id, fileIndex = index, format = "mp3", fileName = "$id.mp3", durationSeconds = 600.0)

    private fun state(player: FakePlayer) = AudiobookPlayerState(
        Book(id = "b1", title = "T", author = "A", coverUrl = ""),
        KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
        player,
        CoroutineScope(Dispatchers.Unconfined),
    ).apply { audioFiles = listOf(file("f1", 0), file("f2", 1), file("f3", 2)) }

    @Test
    fun `the lock screen sees the whole book, not the file`() = runTest {
        val player = FakePlayer().apply { durationMs.value = 600_000 }
        val s = state(player)
        s.playFileAt(1, positionMs = 0)
        player.currentMs.value = 30_000

        val timeline = s.playbackTimeline()

        assertEquals(630_000, timeline.positionMs())
        assertEquals(1_800_000, timeline.durationMs())
    }

    @Test
    fun `a lock screen seek lands in the file that holds it`() = runTest {
        val player = FakePlayer().apply { durationMs.value = 600_000 }
        val s = state(player)
        s.playFileAt(0, positionMs = 0)

        s.playbackTimeline().seekTo(1_250_000)

        assertEquals(2, s.currentFileIndex)
        assertEquals(50, player.loadedSource?.startTimeSec)
    }

    @Test
    fun `its skips are the ones the screen offers`() {
        val timeline = state(FakePlayer()).playbackTimeline()
        assertEquals(30_000, timeline.skipBackMs)
        assertEquals(10_000, timeline.skipForwardMs)
    }
}
