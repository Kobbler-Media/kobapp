package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Between the tap and the first frame the viewer used to look at a black surface — through
 * the search, the resolve and a slow server — with nothing to say any of it was moving.
 */
@OptIn(ExperimentalTestApi::class)
class StartShellTest {

    private fun shell(state: GlobalPlayerState, assertions: ComposeUiTest.() -> Unit) = runUiTest {
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(960.dp, 540.dp)) {
                FocusScopeHost(id = "test", store = FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        FullscreenVideoPlayer(
                            player = FakePlayer().apply { durationMs.value = 9_444_000 },
                            state = state,
                            api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
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
        repeat(4) { mainClock.advanceTimeByFrame() }
        assertions()
    }

    private fun finding() = GlobalPlayerState().apply {
        beginPreparing(
            id = "discover:tmdb:1:1:1",
            newTitle = "Slow Horses",
            newSubtitle = "S1 E1",
            newPosterUrl = "http://127.0.0.1:1/poster.jpg",
            newBackdropUrl = "http://127.0.0.1:1/backdrop.jpg",
            note = "Finding a stream…",
        )
    }

    private fun starting(progress: String? = null, debrid: Boolean = true) = GlobalPlayerState().apply {
        isDebrid = debrid
        mediaType = "episode"
        mediaId = "ep-1"
        sessionId = "s1"
        title = "Slow Horses"
        backdropUrl = "http://127.0.0.1:1/backdrop.jpg"
        isFullscreen = true
        isStarting = true
        startProgress = progress
    }

    private fun loadEpisode(state: GlobalPlayerState) = runBlocking {
        state.load(
            player = FakePlayer(), api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
            type = "episode", id = "ep-1",
            streamUrl = "http://127.0.0.1:1/stream", newSessionId = "s1",
            newTitle = "Slow Horses", newSubtitle = null,
            newPosterUrl = "http://127.0.0.1:1/poster.jpg", newLogoUrl = null,
            destination = null,
        )
    }

    @Test
    fun `a library file loaded after a stream sheds the stream shell`() {
        val state = finding().also { loadEpisode(it) }
        runBlocking {
            state.load(
                player = FakePlayer(), api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                type = "episode", id = "ep-2",
                streamUrl = "http://127.0.0.1:1/stream2", newSessionId = "s2",
                newTitle = "Slow Horses", newSubtitle = null,
                newPosterUrl = "http://127.0.0.1:1/poster.jpg", newLogoUrl = null,
                destination = null,
            )
        }
        shell(state) {
            onNodeWithTag(START_BACKDROP_TAG).assertDoesNotExist()
            onNodeWithText("Starting…").assertDoesNotExist()
        }
    }

    private fun continuingLocal() = GlobalPlayerState().apply {
        beginPreparing(
            id = "/movies/m1",
            newTitle = "Arrival",
            newSubtitle = null,
            newPosterUrl = "http://127.0.0.1:1/poster.jpg",
            stream = false,
        )
    }

    @Test
    fun `a library file opens on its title alone, not the stream shell`() = shell(starting(debrid = false)) {
        onNodeWithTag(START_BACKDROP_TAG).assertDoesNotExist()
        onNodeWithText("Starting…").assertDoesNotExist()
        onNodeWithText("Slow Horses").assertExists()
    }

    @Test
    fun `resuming a library file raises no stream shell while it resolves`() = shell(continuingLocal()) {
        onNodeWithTag(START_BACKDROP_TAG).assertDoesNotExist()
        onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `a stream keeps its shell through the load that opens it`() {
        val state = finding().also { loadEpisode(it) }
        shell(state) {
            onNodeWithTag(START_BACKDROP_TAG).assertExists()
            onNodeWithText("Starting…").assertIsDisplayed()
        }
    }

    @Test
    fun `the search shows the title over its artwork with a quiet note`() = shell(finding()) {
        onNodeWithTag(START_BACKDROP_TAG).assertExists()
        onNodeWithText("Finding a stream…").assertIsDisplayed()
    }

    @Test
    fun `an opening stream keeps the artwork and says it is starting`() = shell(starting()) {
        onNodeWithTag(START_BACKDROP_TAG).assertExists()
        onNodeWithText("Starting…").assertIsDisplayed()
    }

    @Test
    fun `a slow start says how the feed is doing`() = shell(starting("Buffering · 24 MB")) {
        onNodeWithText("Buffering · 24 MB").assertIsDisplayed()
        onNodeWithText("Starting…").assertDoesNotExist()
    }

    @Test
    fun `caching says it is queued and offers another release`() {
        var chose = false
        val state = finding().apply {
            beginCachingPreparation("Slow Horses", onCancel = {}, onChooseRelease = { chose = true })
            cachingNote = "Queued on Real-Debrid — it hasn't started downloading yet"
        }
        shell(state) {
            onNodeWithText("Queued on Real-Debrid — it hasn't started downloading yet").assertIsDisplayed()
            onNodeWithText("Cancel").assertIsDisplayed()
            onNodeWithText("Choose another release").performClick()
            assertTrue(chose, "the release picker was not offered")
        }
    }

    @Test
    fun `caching without a picker offers only cancel`() {
        val state = finding().apply { beginCachingPreparation("Slow Horses", onCancel = {}) }
        shell(state) {
            onNodeWithText("Cancel").assertIsDisplayed()
            onNodeWithText("Choose another release").assertDoesNotExist()
        }
    }
}
