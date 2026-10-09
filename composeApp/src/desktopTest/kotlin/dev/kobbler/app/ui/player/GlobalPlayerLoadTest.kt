package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalPlayerLoadTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private suspend fun load(player: FakePlayer, seekToSec: Int): GlobalPlayerState {
        val gps = GlobalPlayerState()
        gps.load(
            player = player, api = api,
            type = "episode", id = "ep-1",
            streamUrl = "http://127.0.0.1:1/stream", newSessionId = null,
            newTitle = "Show", newSubtitle = null,
            newPosterUrl = null, newLogoUrl = null,
            destination = null,
            seekToSec = seekToSec,
        )
        return gps
    }

    @Test
    fun theResumePositionReachesTheMediaBeforePlay() = runTest {
        val player = FakePlayer()
        player.durationMs.value = 3_600_000
        val gps = load(player, seekToSec = 1200)

        assertEquals(1_200_000L, gps.pendingSeekMs)
        assertEquals(1200, player.loadedSource?.startTimeSec, "the stream was not opened at the resume point")
        assertTrue(player.calls.none { it == "play" }, "load() must not start playback")
    }

    /**
     * A debrid direct-play proxy can answer without a parseable duration. Waiting on one
     * unbounded meant load() never returned, so the caller never reached play() and the
     * stream sat on a black screen. Nothing in the open path waits on a parse any more,
     * and the resume rides in with the stream rather than chasing it.
     */
    @Test
    fun aSourceThatNeverReportsADurationStillLoads() {
        val player = FakePlayer()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(30_000) { load(player, seekToSec = 1200) }
        }
        assertEquals(1200, player.loadedSource?.startTimeSec)
        assertTrue(
            player.calls.none { it.startsWith("seek:") },
            "an unparsed source was seeked anyway: ${player.calls}",
        )
    }

    @Test
    fun theVideoOutputIsBuiltDuringPreloadNotAtPlay() = runTest {
        val player = FakePlayer()
        player.durationMs.value = 3_600_000
        val gps = load(player, seekToSec = 1200)

        val warm = player.calls.indexOf("warm")
        assertTrue(warm >= 0, "the decoder was never warmed: ${player.calls}")
        assertTrue(warm > player.calls.indexOf("load"), "warmed before the stream existed: ${player.calls}")

        gps.playUnmuted(player)
    }

    @Test
    fun aMisTapIsNotSeekedAgainOnPlay() {
        val player = FakePlayer()
        val gps = kotlinx.coroutines.runBlocking { load(player, seekToSec = 0) }
        gps.playUnmuted(player)
        assertTrue(player.calls.none { it.startsWith("seek:") }, "unexpected seek: ${player.calls}")
    }
}
