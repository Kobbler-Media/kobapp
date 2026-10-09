package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The resume is confirmed, not attempted.
 *
 * A stream is opened at the resume point rather than seeked to it, but that is the
 * demuxer's to honour and an HTTP source it has not yet decided is seekable will quietly
 * open at the top instead. The repair that exists for exactly that had two ways to miss
 * it: it gave up without seeking at all if no duration arrived within three seconds — a
 * first open over a network often takes longer — and the player answers a seek
 * optimistically, reporting the position it was asked for whether or not the seek landed,
 * so a dropped one read exactly like a good one.
 */
class ResumeRepairTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private suspend fun resumed(player: FakePlayer, atSec: Int): GlobalPlayerState {
        val gps = GlobalPlayerState()
        gps.load(
            player = player, api = api, type = "movie", id = "mov-1",
            streamUrl = "http://127.0.0.1:1/stream", newSessionId = null,
            newTitle = "T", newSubtitle = null,
            newPosterUrl = null, newLogoUrl = null, destination = null,
            seekToSec = atSec,
        )
        player.play()
        gps.onStarted(player)
        return gps
    }

    /** A container VLC cannot measure still plays. No duration is not a reason to give up. */
    @Test
    fun `a source that reports no duration is still put where it was asked to be`() = runTest {
        val player = FakePlayer().apply { startTimeIgnored = true }

        resumed(player, atSec = 1_380)

        assertTrue(
            player.calls.any { it == "seek:1380000" },
            "no duration, so nothing was repaired and the title played from the top: ${player.calls}",
        )
    }

    /** The one that matters: a seek the player accepted and then did not perform. */
    @Test
    fun `a dropped seek is noticed and repeated`() = runTest {
        val player = FakePlayer().apply {
            startTimeIgnored = true
            durationMs.value = 9_444_000
            seeksDropped = 2
        }

        resumed(player, atSec = 1_380)

        assertTrue(
            player.calls.count { it == "seek:1380000" } > 2,
            "gave up after a seek that was never performed: ${player.calls}",
        )
        assertTrue(
            player.currentMs.value >= 1_380_000 - RESUME_TOLERANCE_MS,
            "the playhead never got there: ${player.currentMs.value}",
        )
    }

    /** A source that honoured the open-time position must not be seeked on top of it. */
    @Test
    fun `a source that opened where it was asked to is left alone`() = runTest {
        val player = FakePlayer().apply { durationMs.value = 9_444_000 }

        resumed(player, atSec = 1_380)

        assertTrue(
            player.calls.none { it.startsWith("seek:") },
            "seeked a stream that had already opened at the resume point: ${player.calls}",
        )
    }
}
