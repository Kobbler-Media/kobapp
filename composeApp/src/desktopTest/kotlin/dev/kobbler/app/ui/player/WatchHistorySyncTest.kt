package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.WatchProgress
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.components.RefreshWatchStateOnFocus
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val POSITION = "/api/v1/admin/movies/mov-1/watch-state/position"

/**
 * Watching on one device and picking up on another only works if the first one said
 * where it stopped. The periodic save only runs while playing, so a pause — or a seek
 * made while paused — followed by closing the window never reached the server.
 */
@OptIn(ExperimentalTestApi::class)
class WatchHistorySyncTest {

    private fun ComposeUiTest.surface(
        stub: StubServer,
        player: FakePlayer,
        state: GlobalPlayerState = GlobalPlayerState(),
    ) {
        state.apply {
            mediaType = "movie"; mediaId = "mov-1"; sessionId = "sess-1"; title = "Film"
            isMiniPlayer = true
        }
        setContent {
            Box(Modifier.size(960.dp, 540.dp)) {
                FocusScopeHost(id = "test", store = FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        PersistentVideoSurface(
                            player = player, state = state,
                            api = KobblerApi(HttpClient(), stub.url, "token"),
                            formFactor = FormFactor.Desktop, onToggleFullscreen = null, isFullscreen = false,
                            scope = CoroutineScope(Dispatchers.Unconfined), videoSurface = { },
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun playing() = FakePlayer().apply {
        durationMs.value = 6_000_000
        currentMs.value = 120_000
        isPlaying.value = true
        state.value = PlayerState.PLAYING
    }

    private fun StubServer.savedSeconds(): Int? =
        Regex("\"position_seconds\":(\\d+)").find(requestBodies[POSITION].orEmpty())?.groupValues?.get(1)?.toInt()

    @Test
    fun pausingSavesThePosition() = runComposeUiTest {
        StubServer(mapOf(POSITION to { _ -> "{}" })).use { stub ->
            val player = playing()
            surface(stub, player)
            runOnIdle { player.pause() }
            waitUntil(timeoutMillis = 3_000) { stub.savedSeconds() != null }
            assertEquals(120, stub.savedSeconds())
        }
    }

    @Test
    fun reachingTheEndIsNotSavedAsAPause() = runComposeUiTest {
        StubServer(mapOf(POSITION to { _ -> "{}" })).use { stub ->
            val player = playing()
            surface(stub, player)
            runOnIdle {
                player.state.value = PlayerState.FINISHED
                player.isPlaying.value = false
            }
            Thread.sleep(1_500)
            waitForIdle()
            assertFalse(POSITION in stub.requestBodies, "a finish was overwritten with a part-way save")
        }
    }

    @Test
    fun closingTheAppSavesWhereItWasLeft() = runComposeUiTest {
        StubServer(mapOf(POSITION to { _ -> "{}" })).use { stub ->
            val player = playing()
            surface(stub, player)
            runOnIdle { player.pause() }
            waitUntil(timeoutMillis = 3_000) { stub.savedSeconds() != null }
            runOnIdle { player.currentMs.value = 900_000 }
            runBlocking { PlaybackExit.flush() }
            assertEquals(900, stub.savedSeconds(), "a seek made while paused was lost on close")
        }
    }

    /**
     * An offer from another device holds every save until the viewer answers it, and
     * playing on past it is the answer "stay". That countdown lived in the expanded player
     * only, so an offer that arrived there and was then played past from the mini player
     * never resolved — and the device stopped saving for the rest of the title.
     */
    @Test
    fun playingPastAnOfferOutsideTheExpandedPlayerResumesSaving() = runComposeUiTest {
        StubServer(mapOf(POSITION to { _ -> "{}" })).use { stub ->
            val player = playing()
            val state = GlobalPlayerState().apply {
                handoff = WatchProgress(
                    mediaType = "movie", mediaId = "mov-1", scopeId = "mov-1", positionSeconds = 30,
                    clientId = "phone", savedAt = "2026-10-02T00:00:00Z",
                )
            }
            surface(stub, player, state)
            mainClock.advanceTimeBy((HANDOFF_STAY_SECONDS + 1) * 1_000L)
            waitForIdle()
            assertEquals(null, state.handoff, "the offer was never resolved")
            runOnIdle { player.pause() }
            waitUntil(timeoutMillis = 3_000) { stub.savedSeconds() != null }
            assertEquals(120, stub.savedSeconds())
        }
    }

    /**
     * A player left paused here while the title carried on elsewhere still holds the old
     * playhead. Closing it wrote that straight back over the newer save — no handoff card,
     * because a player that was not listening when the other device saved never got one.
     */
    @Test
    fun aPlayerThatNeverMovedDoesNotOverwriteTheServer() = runComposeUiTest {
        StubServer(mapOf(POSITION to { _ -> "{}" })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().apply { durationMs.value = 6_000_000 }
            val state = GlobalPlayerState()
            runBlocking {
                state.load(
                    player = player, api = api, type = "movie", id = "mov-1",
                    streamUrl = "http://127.0.0.1:1/stream", newSessionId = "s1",
                    newTitle = "Film", newSubtitle = null, newPosterUrl = null, newLogoUrl = null,
                    destination = null, seekToSec = 600,
                )
            }
            player.currentMs.value = 600_000
            surface(stub, player, state)

            runBlocking {
                PlaybackExit.flush()
                state.reportProgress(api, "movie", "mov-1", 600_000)
            }
            assertFalse(POSITION in stub.requestBodies, "an untouched playhead was written back on close")

            runBlocking { state.reportProgress(api, "movie", "mov-1", 660_000) }
            assertEquals(660, stub.savedSeconds(), "a playhead that did move was not saved")
        }
    }

    /**
     * The local mirror is state the screens beneath the player read, so writing it redraws
     * them; every redraw on Android 15+ pulls the panel to 60Hz and 24fps video judders.
     * Mid-play saves go to the server only; the pause or exit after them updates the mirror,
     * even when the server already has that second.
     */
    @Test
    fun aSaveWhilePlayingLeavesTheShownPositionToThePauseOrExit() {
        StubServer(mapOf(POSITION to { _ -> "{}" })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val state = GlobalPlayerState()
            runBlocking { state.reportProgress(api, "movie", "mov-1", 600_000, mirror = false) }
            assertEquals(600, stub.savedSeconds())
            assertNull(state.reportedPositions["mov-1"], "a mid-play save touched the shown position")

            runBlocking { state.reportProgress(api, "movie", "mov-1", 600_400) }
            assertEquals(WatchedPoint(600), state.reportedPositions["mov-1"])
        }
    }

    /** Coming back to an app left open elsewhere is when its watch history is most likely stale. */
    @Test
    fun regainingFocusRefetchesWatchState() = runComposeUiTest {
        val focused = mutableStateOf(true)
        setContent { RefreshWatchStateOnFocus(focused.value) }
        waitForIdle()
        val before = listOf("home", "movie-m1", "show-s1").associateWith { DataCache.versionOf(it) }
        runOnIdle { focused.value = false }
        waitForIdle()
        assertEquals(before, before.keys.associateWith { DataCache.versionOf(it) }, "losing focus refetched")
        runOnIdle { focused.value = true }
        waitForIdle()
        before.forEach { (key, v) -> assertTrue(DataCache.versionOf(key) > v, "$key was not refetched") }
    }
}
