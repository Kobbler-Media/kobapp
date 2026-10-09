package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.player.audiobook.AUDIOBOOK_START_BUDGET_MS
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An audiobook that will not play has to say so, and a resume has to land.
 *
 * The audio path had neither: a file the server could not serve produced a dead player and
 * a silent screen, because the only thing setting an error message was the metadata fetch.
 * And every jump opened the stream and then slept for half a second before seeking, so a
 * source that took longer than that to open lost the position and started the book again.
 */
class AudiobookStartTest {

    private val book = Book(
        id = "bk-1",
        title = "Project Hail Mary",
        author = "Andy Weir",
        coverUrl = "",
    )

    private fun file(id: String) = AudioFileDto(
        id = id, fileIndex = 0, format = "m4b", fileName = "$id.m4b", durationSeconds = 3_600.0,
    )

    private fun state(player: FakePlayer) = AudiobookPlayerState(
        book = book,
        api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
        player = player,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    ).apply {
        audioFiles = listOf(file("f1"), file("f2"))
    }

    @Test
    fun `a resume opens the stream at the position rather than seeking blind`() = runTest {
        val player = FakePlayer().apply { durationMs.value = 3_600_000 }
        val s = state(player)

        s.playFileAt(1, positionMs = 742_000)

        assertEquals(1, s.currentFileIndex)
        assertEquals(
            742, player.loadedSource?.startTimeSec,
            "the position was not handed to the demuxer: ${player.loadedSource}",
        )
        assertTrue(player.calls.contains("play"))
    }

    /** Below the resume floor there is nothing to resume from. */
    @Test
    fun `a position at the very start is not asked for`() = runTest {
        val player = FakePlayer().apply { durationMs.value = 3_600_000 }
        state(player).playFileAt(0, positionMs = 1_200)
        assertNull(player.loadedSource?.startTimeSec)
    }

    /** A demuxer may ignore the open-time position. One check, after it is really playing. */
    @Test
    fun `a start time the source ignored is repaired`() = runTest {
        val player = FakePlayer().apply { startTimeIgnored = true; durationMs.value = 3_600_000 }
        val s = state(player)

        s.playFileAt(0, positionMs = 742_000)
        s.awaitFileStart(742_000)

        assertTrue(
            player.calls.any { it == "seek:742000" },
            "the source opened at the top and nothing put it right: ${player.calls}",
        )
    }

    @Test
    fun `a source that opened where it was asked to is left alone`() = runTest {
        val player = FakePlayer().apply {
            durationMs.value = 3_600_000
            currentMs.value = 742_000
        }
        val s = state(player)

        s.playFileAt(0, positionMs = 742_000)
        s.awaitFileStart(742_000)

        assertTrue(
            player.calls.none { it.startsWith("seek:") },
            "seeked a player that was already there: ${player.calls}",
        )
    }

    /** The whole gap: a file that never plays used to leave a silent, dead screen. */
    @Test
    fun `a file that never plays says so`() = runTest {
        val player = FakePlayer().apply { playStalls = true; durationMs.value = 3_600_000 }
        val s = state(player)

        s.playFileAt(0, positionMs = 0)
        s.awaitFileStart(0, budgetMs = 200)

        assertNotNull(s.errorMessage, "the book stopped playing and nothing said why")
    }

    @Test
    fun `a player failure is surfaced`() = runTest {
        val player = FakePlayer()
        val s = state(player)

        s.watchPlayerFailures(backgroundScope)
        testScheduler.runCurrent()
        player.error.value = IllegalStateException("could not open the stream")
        testScheduler.runCurrent()

        assertTrue(
            s.errorMessage?.contains("could not open the stream") == true,
            "a decode failure reached nothing: ${s.errorMessage}",
        )
    }

    @Test
    fun `the start budget is not a magic sleep`() {
        assertTrue(AUDIOBOOK_START_BUDGET_MS >= 1_000)
    }
}
