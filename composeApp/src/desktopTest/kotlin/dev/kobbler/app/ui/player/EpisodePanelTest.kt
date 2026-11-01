package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EpisodePanelTest {

    private val show = TvShow(
        id = "show-1",
        title = "A Show",
        year = 2020,
        posterUrl = "",
        seasons = listOf(1, 2).map { s ->
            Season(
                number = s,
                id = "s$s",
                episodes = (1..12).map { e ->
                    Episode(
                        id = "s${s}e$e",
                        number = e,
                        title = "S$s Episode $e",
                        synopsis = "What happens in S$s E$e.",
                        filePath = "/media/s${s}e$e.mkv",
                    )
                },
            )
        },
    )

    private fun ComposeUiTest.showPlayer(
        formFactor: FormFactor,
        mediaType: String = "episode",
        playing: String = "s1e9",
        onPlay: (String) -> Unit = {},
    ) {
        val state = GlobalPlayerState().apply {
            this.mediaType = mediaType
            mediaId = playing
            title = show.title
            sessionId = "sess-1"
            isFullscreen = true
            if (mediaType == "episode") {
                episodeShow = show
                onPlayEpisode = onPlay
            }
        }
        val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")
        setContent {
            FocusScopeHost(id = "test", store = FocusScopeStore()) {
                KobappTheme(formFactor = formFactor) {
                    FullscreenVideoPlayer(
                        player = FakePlayer(),
                        state = state,
                        api = api,
                        formFactor = formFactor,
                        onBack = {},
                        isFullscreen = true,
                        videoSurface = { },
                    )
                }
            }
        }
        waitForIdle()
        revealControls(formFactor)
    }

    private fun ComposeUiTest.openPanel() {
        onNodeWithContentDescription("Episodes").performClick()
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }

    private fun picksAnotherEpisode(formFactor: FormFactor) = runUiTest(width = 1280, height = 720) {
        var picked: String? = null
        showPlayer(formFactor, onPlay = { picked = it })
        openPanel()

        onNodeWithText("WATCHING").assertExists()
        onNodeWithText("S1 Episode 10").performClick()
        waitForIdle()

        assertEquals("s1e10", picked)
        assertTrue(onAllNodesWithContentDescription("Close episodes").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a phone picks another episode from the panel`() = picksAnotherEpisode(FormFactor.Phone)

    @Test
    fun `a desktop picks another episode from the panel`() = picksAnotherEpisode(FormFactor.Desktop)

    @Test
    fun `the panel shows the summary and switches seasons`() = runUiTest(width = 1280, height = 720) {
        var picked: String? = null
        showPlayer(FormFactor.Phone, onPlay = { picked = it })
        openPanel()

        onNodeWithText("What happens in S1 E9.").assertExists()
        onNodeWithText("Season 2").performClick()
        waitForIdle()
        onNodeWithText("S2 Episode 1").performClick()
        waitForIdle()

        assertEquals("s2e1", picked)
    }

    @Test
    fun `the episode being watched cannot be picked again`() = runUiTest(width = 1280, height = 720) {
        var picked: String? = null
        showPlayer(FormFactor.Phone, onPlay = { picked = it })
        openPanel()

        onNodeWithText("S1 Episode 9").performClick()
        waitForIdle()

        assertEquals(null, picked)
    }

    @Test
    fun `a remote opens the panel on the episode being watched`() = runUiTest(width = 1280, height = 720) {
        showPlayer(FormFactor.TV)
        openPanel()

        waitUntil(timeoutMillis = UiTimeoutMs) {
            onAllNodes(isFocused() and hasText("S1 Episode 9", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a film has no episode button`() = runUiTest(width = 1280, height = 720) {
        showPlayer(FormFactor.Phone, mediaType = "movie", playing = "mov-1")

        assertTrue(onAllNodesWithContentDescription("Episodes").fetchSemanticsNodes().isEmpty())
    }
}
