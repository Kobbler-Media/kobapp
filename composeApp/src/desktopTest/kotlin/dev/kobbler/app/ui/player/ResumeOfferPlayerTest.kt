package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.SkipSegment
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Carrie 1x01's shape: a 3311 s episode whose crawl starts at 3131 s, watched to 2990 s —
 * past the 90% mark, so the server called it finished, and the play opens at the top.
 */
@OptIn(ExperimentalTestApi::class)
class ResumeOfferPlayerTest {

    private val credits = SkipSegment("credits", startSeconds = 3131.0, endSeconds = 3311.0)

    private fun playing(
        savedSeconds: Int,
        openedAtMs: Long = 2_000L,
        test: ComposeUiTest.(FakePlayer) -> Unit,
    ) = StubServer(
        mapOf("/api/v1/admin/tv/episodes/ep-1/watch-state" to { _ -> """{"position_seconds":$savedSeconds,"watched":true}""" }),
    ).use { server ->
        runUiTest(width = 1920, height = 1080) {
            val player = FakePlayer().apply {
                durationMs.value = 3_311_000L
                currentMs.value = openedAtMs
                isPlaying.value = true
                state.value = PlayerState.PLAYING
            }
            val state = GlobalPlayerState().apply {
                mediaType = "episode"
                mediaId = "ep-1"
                sessionId = "sess-1"
                title = "Episode"
                skipSegments = listOf(credits)
                isFullscreen = true
            }
            setContent {
                Box(Modifier.size(960.dp, 540.dp)) {
                    FocusScopeHost(id = "test", store = FocusScopeStore()) {
                        KobappTheme(formFactor = FormFactor.TV) {
                            PersistentVideoSurface(
                                player = player,
                                state = state,
                                api = KobblerApi(HttpClient(), server.url, "token"),
                                formFactor = FormFactor.TV,
                                onToggleFullscreen = null,
                                isFullscreen = true,
                                scope = CoroutineScope(Dispatchers.Unconfined),
                                videoSurface = { },
                            )
                        }
                    }
                }
            }
            test(player)
        }
    }

    private fun ComposeUiTest.offered(): Boolean {
        mainClock.advanceTimeBy(200)
        return runCatching {
            waitUntil(timeoutMillis = UiTimeoutMs) {
                onAllNodesWithText("Resume from 49:50", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
    }

    private fun FakePlayer.resumed() = calls.any { it == "seek:2990000" }

    @Test
    fun `the offer resumes on its own once the countdown runs out`() = playing(2990) { player ->
        assertTrue(offered(), "no offer")
        onNodeWithText("Resume from 49:50 (5)").assertExists()
        mainClock.advanceTimeBy(5_500)
        waitForIdle()
        assertTrue(player.resumed(), "countdown did not resume: ${player.calls}")
        assertTrue(onAllNodesWithText("Resume from", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `ok on a remote resumes straight away`() = playing(2990) { player ->
        assertTrue(offered(), "no offer")
        onNodeWithText("Resume from", substring = true).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        mainClock.advanceTimeBy(300)
        waitForIdle()
        assertTrue(player.resumed(), "OK did not resume: ${player.calls}")
    }

    @Test
    fun `cancel keeps playing from the top`() = playing(2990) { player ->
        assertTrue(offered(), "no offer")
        onNodeWithText("Cancel").performClick()
        mainClock.advanceTimeBy(6_000)
        waitForIdle()
        assertFalse(player.resumed(), "resumed after cancel: ${player.calls}")
        assertTrue(onAllNodesWithText("Resume from", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `nothing is offered for a title finished in its credits or opened part way`() {
        playing(3140) { player ->
            assertFalse(offered(), "offered a position inside the credits")
            assertFalse(player.calls.any { it.startsWith("seek:") })
        }
        playing(2990, openedAtMs = 1_200_000L) { _ ->
            assertFalse(offered(), "offered over a resumed play")
        }
    }
}
