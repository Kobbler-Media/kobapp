package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The stream must not be opened before there is somewhere to render it.
 *
 * A decoder is chosen once, when the stream opens, and a hardware one can only be chosen
 * against a live window. Open first and the window turns up to find the decision already
 * made — the title then plays out in software, at full CPU, with nothing in the UI
 * saying so. The ordering is the whole fix, so it is worth a test that cannot be
 * satisfied by getting it right on one device.
 */
class VideoOutputOrderingTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private suspend fun load(player: FakePlayer) = GlobalPlayerState().load(
        player = player, api = api, type = "movie", id = "m1",
        streamUrl = "http://127.0.0.1:1/stream", newSessionId = null,
        newTitle = "T", newSubtitle = null,
        newPosterUrl = null, newLogoUrl = null, destination = null,
    )

    @Test
    fun `the stream waits for a video output`() = runTest {
        val player = FakePlayer().apply {
            videoOutputReady.value = false
            state.value = dev.kobbler.kobplayer.PlayerState.PAUSED
            durationMs.value = 60_000
        }

        val loading = async { load(player) }
        delay(500)
        assertTrue(
            player.calls.none { it == "load" },
            "the stream was opened with no output to render into: ${player.calls}",
        )

        player.videoOutputReady.value = true
        loading.await()
        assertTrue(player.calls.any { it == "load" }, "the stream never opened: ${player.calls}")
    }

    /** A source with no video never raises an output, and must not be held up by one. */
    @Test
    fun `an output that is ready already costs nothing`() = runTest {
        val player = FakePlayer().apply {
            state.value = dev.kobbler.kobplayer.PlayerState.PAUSED
            durationMs.value = 60_000
        }
        load(player)
        assertTrue(player.calls.any { it == "load" }, "the stream never opened: ${player.calls}")
    }
}
