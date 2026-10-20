package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Work the app runs after a load returns still reaches the player from the main thread.
 *
 * The Android player hands libVLC its commands from there in order, and attaches its video
 * output there; a resume repair or track repair issued from a background pool raced the
 * main thread's own commands for the same player.
 */
class PlayerThreadTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun awaitCall(player: FakePlayer, matches: (String) -> Boolean) = runBlocking {
        withTimeout(10_000) { while (player.calls.none(matches)) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `the resume repair seeks from the main thread`() {
        val player = FakePlayer().apply {
            startTimeIgnored = true
            durationMs.value = 9_444_000
        }
        val gps = GlobalPlayerState()
        runBlocking {
            gps.load(
                player = player, api = api, type = "movie", id = "mov-1",
                streamUrl = "http://127.0.0.1:1/stream", newSessionId = null,
                newTitle = "T", newSubtitle = null,
                newPosterUrl = null, newLogoUrl = null, destination = null,
                seekToSec = 1_380,
            )
        }
        gps.playUnmuted(player)

        awaitCall(player) { it.startsWith("seek:") }
        assertEquals(emptyList(), player.offMainCalls)
    }

    @Test
    fun `the track repair selects from the main thread`() {
        val player = FakePlayer().apply {
            durationMs.value = 9_444_000
            selectedSubtitleTrack.value = dev.kobbler.kobplayer.TrackInfo("spu/1", "English")
        }
        val gps = GlobalPlayerState()
        runBlocking {
            gps.load(
                player = player, api = api, type = "movie", id = "mov-1",
                streamUrl = "http://127.0.0.1:1/stream", newSessionId = null,
                newTitle = "T", newSubtitle = null,
                newPosterUrl = null, newLogoUrl = null, destination = null,
                seekToSec = 0,
            )
        }
        player.play()

        awaitCall(player) { it == "subtitle:null" }
        assertTrue(player.offMainCalls.isEmpty(), "off the main thread: ${player.offMainCalls}")
    }
}
