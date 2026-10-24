package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveBuffer
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScope
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScope
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The live player's own transport, driven the way a remote drives it. The channel
 * switcher is the only way off the current channel without leaving playback, so it has to
 * be reachable from wherever the overlay parks entry focus.
 */
@OptIn(ExperimentalTestApi::class)
class LiveControlFocusTest {

    private fun liveState(timeshiftable: Boolean, buffered: Boolean = timeshiftable) =
        GlobalPlayerState().apply {
            mediaType = "livetv"
            mediaId = "chan-1"
            title = "Channel 9"
            isLive = true
            liveSessionId = "live-1"
            if (timeshiftable) livePlaylistUrl = "http://stub/playlist.m3u8"
            if (buffered) {
                liveBuffer = LiveBuffer(
                    startTime = null, endTime = null,
                    liveEdgeSeconds = 1800.0, durationSeconds = 1800.0,
                    retainedSeconds = 1800.0, capSeconds = 3600.0,
                )
            }
        }

    private fun ComposeUiTest.showLivePlayer(
        state: GlobalPlayerState,
        api: KobblerApi,
        player: FakePlayer = FakePlayer(),
    ): FocusScopeAndManager {
        lateinit var scope: FocusScope
        lateinit var focusManager: FocusManager
        setContent {
          Box(Modifier.size(960.dp, 540.dp).semantics { contentDescription = "player" }) {
            FocusScopeHost(id = "test", store = FocusScopeStore()) {
                scope = LocalFocusScope.current
                focusManager = LocalFocusManager.current
                KobappTheme(formFactor = FormFactor.TV) {
                    FullscreenVideoPlayer(
                        player = player,
                        state = state,
                        api = api,
                        formFactor = FormFactor.TV,
                        onBack = {},
                        isFullscreen = true,
                        videoSurface = { },
                        onOpenGuide = {},
                    )
                }
            }
          }
        }
        waitForIdle()
        // The overlay parks entry focus on play/pause, which is where a remote starts.
        mainClock.advanceTimeBy(500)
        waitForIdle()
        return FocusScopeAndManager(scope, focusManager)
    }

    private data class FocusScopeAndManager(val scope: FocusScope, val focusManager: FocusManager)

    private fun ComposeUiTest.keysWhileWalking(
        directions: List<FocusDirection>,
        state: GlobalPlayerState,
    ): List<String?> {
        val (scope, focusManager) = showLivePlayer(state, KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"))
        return listOf(scope.save()) + directions.map { direction ->
            runOnIdle { focusManager.moveFocus(direction) }
            waitForIdle()
            scope.save()
        }
    }

    private val walkDown = List(3) { FocusDirection.Down } + List(8) { FocusDirection.Right }

    @Test
    fun `the channels button is reachable on a timeshiftable tune`() = runUiTest {
        val keys = keysWhileWalking(walkDown, liveState(timeshiftable = true))
        assertTrue(keys.any { it?.endsWith("ctrl.channels") == true }, "walked $keys")
    }

    @Test
    fun `the channels button is reachable on a raw live tune`() = runUiTest {
        val keys = keysWhileWalking(walkDown, liveState(timeshiftable = false))
        assertTrue(keys.any { it?.endsWith("ctrl.channels") == true }, "walked $keys")
    }

    /**
     * The ring buffer is reported by a poll and starts empty, so it is a second or more
     * into every tune before there is anything to scrub. The transport must not be a
     * different transport in the meantime: the badge layout drops the seek bar entirely
     * and rebuilds the control row, which read as the player changing its mind.
     *
     * Counted rather than matched: the badge layout draws its own LIVE plate *and* the
     * pill in the transport row, so two is the layout that flips and one is the one that
     * does not.
     */
    @Test
    fun `the live transport is the same before and after the buffer poll lands`() = runUiTest {
        listOf(false, true).forEach { buffered ->
            showLivePlayer(
                liveState(timeshiftable = true, buffered = buffered),
                KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
            )
            onAllNodesWithText("LIVE").assertCountEquals(1)
        }
    }

    /**
     * A remote lands on the bar it will spend the watch on. Play/pause is one press down
     * from it, whereas from play/pause the bar was unreachable without knowing it was
     * there — so the first LEFT of every watch went nowhere instead of stepping back.
     */
    @Test
    fun `the live transport opens on the seek bar`() = runUiTest {
        val (scope, _) = showLivePlayer(
            liveState(timeshiftable = true),
            KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
        )
        assertTrue(scope.save()?.endsWith("seek") == true, "entry focus landed on ${scope.save()}")
    }

    /**
     * RIGHT at the live edge has nowhere to seek to, so it is the press a TV spends on
     * the channel list. Behind the edge it is still a step forward.
     */
    @Test
    fun `pressing right at the live edge opens the channel panel`() = runUiTest {
        StubServer(railRoutes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val (scope, _) = showLivePlayer(liveState(timeshiftable = true), api)

            onNodeWithContentDescription("Seek").performKeyInput { pressKey(Key.DirectionRight) }
            waitUntil(timeoutMillis = UiTimeoutMs) { scope.save()?.contains("channelrail.ch.") == true }

            assertTrue(scope.save()!!.endsWith("ch.chan-1"), "panel entry landed on ${scope.save()}")
        }
    }

    /**
     * The list belongs against the edge the viewer pressed towards, as a column of
     * programmes — a bar across the bottom of the picture is where a phone puts it and
     * fits three channels on a TV.
     */
    @Test
    fun `the channel panel opens against the right edge`() = runUiTest {
        StubServer(railRoutes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val (scope, _) = showLivePlayer(liveState(timeshiftable = true), api)

            onNodeWithContentDescription("Seek").performKeyInput { pressKey(Key.DirectionRight) }
            waitUntil(timeoutMillis = UiTimeoutMs) { scope.save()?.contains("channelrail.ch.") == true }

            val screen = onNodeWithContentDescription("player").getBoundsInRoot()
            val panel = onNodeWithText("WATCHING").getBoundsInRoot()
            assertTrue(
                panel.left > (screen.right - screen.left) / 2,
                "the panel's rows start at ${panel.left} on a ${screen.right} wide screen",
            )
        }
    }

    /**
     * Opening the rail has to hand it the D-pad. The control that opened it is covered
     * and inert, and focus left there is focus the viewer cannot see or move.
     */
    @Test
    fun `opening the channel rail focuses a channel card`() = runUiTest {
        StubServer(railRoutes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val (scope, _) = showLivePlayer(liveState(timeshiftable = true), api)

            onNodeWithContentDescription("Channels").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { scope.save()?.contains("channelrail.ch.") == true }

            assertTrue(scope.save()!!.endsWith("ch.chan-1"), "rail entry landed on ${scope.save()}")
        }
    }

    /**
     * A channel change is several seconds of D-pad work, and the transport hides itself
     * after four of them. It is composed behind the rail the whole time, so hiding it
     * disposes the control the rail was opened from — and closing the rail then restores
     * focus to a node that no longer exists, leaving the window with nothing focused.
     * From there no key reaches the overlay, so the controls can never be asked back.
     */
    @Test
    fun `switching channel and leaving the rail gives the D-pad back`() = runUiTest {
        StubServer(switchRoutes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().apply { isPlaying.value = true }
            val (scope, _) = showLivePlayer(liveState(timeshiftable = true), api, player)

            onNodeWithContentDescription("Channels").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { scope.save()?.contains("channelrail.ch.") == true }

            onNodeWithText("Channel 7").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { player.loadedUrl?.contains("chan-2") == true }
            mainClock.advanceTimeBy(6_000)
            waitForIdle()

            // LEFT walks out of the rail, back to the transport it was opened from.
            onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
            waitUntil(timeoutMillis = UiTimeoutMs) { scope.focused?.startsWith("player.") == true }
        }
    }

    /**
     * Every forward control on a timeshifted tune has the same end to run into: the last
     * segment of the playlist is the one the server is still writing, and a step forward
     * that clamps to the reported duration lands in it. Jump to live was only the press
     * that found it first — the ±10 s buttons, the media keys and the bar all share it.
     */
    @Test
    fun `stepping forward never lands on the end of a live playlist`() = runUiTest {
        val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")
        val player = FakePlayer().apply {
            durationMs.value = 1_800_000
            currentMs.value = 1_795_000
        }
        val state = liveState(timeshiftable = true).apply { liveTimeshift = true }
        showLivePlayer(state, api, player)

        // The TV's forward step is the bar's RIGHT: the center disc that used to carry it
        // is not on a TV with a seek bar.
        onNodeWithContentDescription("Seek").performKeyInput { pressKey(Key.DirectionRight) }
        // The bar commits a step once the presses stop.
        mainClock.advanceTimeBy(600)
        waitForIdle()

        assertTrue(
            player.calls.none { it == "seek:1800000" },
            "a step forward seeked into the segment being written: ${player.calls}",
        )
        assertTrue(
            player.calls.any { it == "seek:1796000" },
            "a step forward near the edge did not land at it: ${player.calls}",
        )
    }

    /**
     * Which is the rule underneath it: a layer over the player holds the D-pad, so the
     * transport must not time out under it. An open dropdown already suppresses the
     * timer for exactly this reason.
     */
    @Test
    fun `the transport does not time out under the rail`() = runUiTest {
        StubServer(railRoutes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().apply { isPlaying.value = true }
            val (scope, _) = showLivePlayer(liveState(timeshiftable = true), api, player)

            onNodeWithContentDescription("Channels").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { scope.save()?.contains("channelrail.ch.") == true }

            mainClock.advanceTimeBy(6_000)
            waitForIdle()

            onNodeWithContentDescription("Channels").assertIsDisplayed()
        }
    }
}

private val railRoutes = mapOf(
    "/api/v1/admin/livetv/guide/now" to { _: com.sun.net.httpserver.HttpExchange ->
        """{"items":[
            {"channel":{"id":"chan-1","lcn":9,"name":"Channel 9","favourite":true}},
            {"channel":{"id":"chan-2","lcn":7,"name":"Channel 7","favourite":true}}
        ]}"""
    },
)

private val switchRoutes = railRoutes + mapOf(
    "/api/v1/admin/livetv/sessions/live-1/channel" to { _: com.sun.net.httpserver.HttpExchange ->
        """{"session_id":"live-1","channel_id":"chan-2","method":"direct",
            "direct_url":"http://127.0.0.1:1/live/chan-2/stream.ts","width":1920,"height":1080}"""
    },
)
