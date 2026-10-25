package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val player = FakePlayer(backgroundScope).apply { startTimeIgnored = true }

        resumed(player, atSec = 1_380)

        assertTrue(
            player.calls.any { it == "seek:1380000" },
            "no duration, so nothing was repaired and the title played from the top: ${player.calls}",
        )
    }

    /** The one that matters: a seek the player accepted and then did not perform. */
    @Test
    fun `a dropped seek is noticed and repeated`() = runTest {
        val player = FakePlayer(backgroundScope).apply {
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
        val player = FakePlayer(backgroundScope).apply { durationMs.value = 9_444_000 }

        resumed(player, atSec = 1_380)

        assertTrue(
            player.calls.none { it.startsWith("seek:") },
            "seeked a stream that had already opened at the resume point: ${player.calls}",
        )
    }

    /**
     * What a debrid stream does on a TV: the player shows the asked-for start the moment it
     * opens, and VLC's own clock — reading the head of the file — only seconds after PLAYING.
     * Judged on the first, the resume looked done and the episode played from 0:00.
     */
    @Test
    fun `an ignored start is caught however late the decoder's clock arrives`() = runTest {
        for (reportMs in listOf(2_500L, 4_000L, 9_000L)) {
            val player = FakePlayer(backgroundScope).apply {
                startTimeIgnored = true
                durationMs.value = 9_444_000
                clockReportMs = reportMs
            }

            resumed(player, atSec = 1_380)

            assertEquals(
                listOf("seek:1380000"), player.calls.filter { it.startsWith("seek:") },
                "clock after ${reportMs}ms: ${player.calls}",
            )
            assertEquals(1_380_000, player.currentMs.value, "clock after ${reportMs}ms")
        }
    }

    /** A clock that never comes is no evidence the open-time start landed. */
    @Test
    fun `a player whose clock never reports is still put where it was asked`() = runTest {
        val player = FakePlayer(backgroundScope).apply {
            startTimeIgnored = true
            durationMs.value = 9_444_000
            clockReportMs = Long.MAX_VALUE
        }

        resumed(player, atSec = 1_380)

        assertTrue(player.calls.any { it == "seek:1380000" }, "trusted the asked-for start: ${player.calls}")
    }

    /**
     * Until the repair has landed the playhead reads the head of the file, and a save then
     * would replace the server's resume point with a few seconds in.
     */
    @Test
    fun `the head of the file is never saved over the resume point`() = runTest {
        val saved = java.util.concurrent.CopyOnWriteArrayList<String>()
        StubServer(mapOf(
            "/api/v1/admin/movies/mov-1/watch-state/position" to { _ -> saved += "saved"; "{}" },
        )).use { stub ->
            val stubApi = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer(backgroundScope).apply {
                startTimeIgnored = true
                durationMs.value = 9_444_000
                seeksDropped = 1
            }
            val gps = GlobalPlayerState()
            gps.load(
                player = player, api = stubApi, type = "movie", id = "mov-1",
                streamUrl = "http://127.0.0.1:1/stream", newSessionId = null,
                newTitle = "T", newSubtitle = null,
                newPosterUrl = null, newLogoUrl = null, destination = null,
                seekToSec = 1_380,
            )
            player.play()
            val repair = backgroundScope.launch { gps.onStarted(player) }
            player.positionReported.first { it }
            gps.reportProgress(stubApi, "movie", "mov-1", player.currentMs.value)
            repair.join()

            assertEquals(emptyList(), saved, "saved the playhead before the resume landed")
            assertEquals(null, gps.reportedPositions["mov-1"], "showed the head of the file as progress")

            gps.reportProgress(stubApi, "movie", "mov-1", player.currentMs.value + 60_000)
            assertEquals(1, saved.size, "progress after the resume landed was not saved: $saved")
        }
    }
}
