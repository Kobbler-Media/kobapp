package dev.kobbler.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.CastAudioTrack
import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTextTrack
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cast transport sits over a live screen, so a tap on it is the viewer's word to the cast. */
@OptIn(ExperimentalTestApi::class)
class CastControllerTest {

    private fun casting(connecting: Boolean = false) = GlobalPlayerState().apply {
        mediaType = "episode"
        mediaId = "ep-1"
        title = "South Park"
        castSession = CastSessionDto(
            deviceName = "Projector TV",
            mediaId = "ep-1",
            mediaType = "episode",
            mediaTitle = "South Park",
            subtitle = "S1 E1 · Cartman Gets an Anal Probe",
            positionMs = 600_000,
            durationMs = 1_465_000,
            isPlaying = true,
            connecting = connecting,
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: GlobalPlayerState, onBehind: () -> Unit) {
        setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                FocusScopeHost(id = "test", store = FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.Phone) {
                        Box(Modifier.fillMaxSize().clickable(onClick = onBehind))
                        PersistentVideoSurface(
                            player = FakePlayer(),
                            state = state,
                            api = KobblerApi(HttpClient(), "http://localhost:1", "token"),
                            formFactor = FormFactor.Phone,
                            onToggleFullscreen = null,
                            isFullscreen = false,
                            scope = CoroutineScope(Dispatchers.Default),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a tap on the mini controller opens the cast player instead of what is behind it`() = runUiTest {
        var behind = 0
        show(casting()) { behind++ }

        onNodeWithText("South Park").performClick()
        waitForIdle()

        assertEquals(0, behind, "the tap went through to the screen behind the mini controller")
        onNodeWithText("Stop casting").assertExists()

        onNodeWithText("Stop casting").performClick()
        onNodeWithContentDescription("Collapse").performClick()
        waitForIdle()
        assertEquals(0, behind, "the tap went through the full-screen cast player")
        onNodeWithText("Stop casting").assertDoesNotExist()
    }

    @Test
    fun `a session still opening says so`() = runUiTest {
        show(casting(connecting = true)) {}

        onNodeWithText("Connecting to Projector TV…").assertExists()
    }

    @Test
    fun `a cast joined with nothing open here still gets its controls`() {
        val joined = GlobalPlayerState().apply { castSession = casting().castSession }
        kotlin.test.assertTrue(joined.showsPlayerSurface)
        kotlin.test.assertTrue(!GlobalPlayerState().showsPlayerSurface)
    }

    @Test
    fun `a skippable segment on the receiver can be skipped from the mini controller`() = runUiTest {
        val state = casting()
        state.castSession = state.castSession?.copy(skip = CastSkip("intro", 90.0))
        val seeks = mutableListOf<Long>()
        state.onCastSeek = { seeks += it }
        show(state) {}

        onNodeWithText("Skip Intro").performClick()
        waitForIdle()

        assertEquals(listOf(90_000L), seeks)
        onNodeWithText("Stop casting").assertDoesNotExist()
    }

    @Test
    fun `the cast player switches the receiver's subtitles and audio`() = runUiTest {
        val state = casting()
        state.isCastExpanded = true
        state.castSession = state.castSession?.copy(
            skip = CastSkip("credits", 1_400.0),
            tracks = CastTracks(
                subtitles = listOf(CastTextTrack(id = 1, language = "eng"), CastTextTrack(id = 2, language = "fre")),
                activeSubtitleId = 2,
                audioTracks = listOf(CastAudioTrack(streamIndex = 1, language = "jpn"), CastAudioTrack(streamIndex = 2, language = "eng")),
                activeAudioIndex = 1,
            ),
        )
        val subtitles = mutableListOf<Int?>()
        val audio = mutableListOf<Int>()
        state.onCastSubtitle = { subtitles += it }
        state.onCastAudio = { audio += it }
        show(state) {}

        onNodeWithText("Skip Credits").assertExists()
        onNodeWithText("Subtitles: FRE").performClick()
        onNodeWithText("Off").performClick()
        onNodeWithText("Audio: JPN").performClick()
        onNodeWithText("ENG").performClick()
        waitForIdle()

        assertEquals(listOf<Int?>(null), subtitles)
        assertEquals(listOf(2), audio)
    }
}
