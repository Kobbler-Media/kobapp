package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.Season
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test

/**
 * The real episode list, driven the way a remote drives it. Nesting the action icons
 * inside a focusable row left them unreachable: the row swallowed the press.
 */
@OptIn(ExperimentalTestApi::class)
class EpisodeActionFocusTest {

    private val season = Season(
        number = 1,
        episodes = listOf(
            Episode(id = "e1", number = 1, title = "Pilot", filePath = "/a.mkv"),
            Episode(id = "e2", number = 2, title = "Second", filePath = "/b.mkv"),
        ),
    )

    private fun ComposeUiTest.showList(target: Season = season): FocusManager {
        var manager: FocusManager? = null
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                manager = LocalFocusManager.current
                EpisodeList(
                    season = target,
                    seasonIdx = 0,
                    accent = KobappTheme.colors.textHi,
                    currentEpisodeId = null,
                    api = null,
                    watchedOverrides = mutableStateMapOf(),
                    onPlayEpisode = { _, _, _ -> },
                    onStreamEpisode = { _, _ -> },
                    onStreamEpisodePick = { _, _ -> },
                    wantOverrides = mutableStateMapOf(),
                )
            }
        }
        waitForIdle()
        // Entry: the first episode body.
        runOnIdle { manager!!.moveFocus(FocusDirection.Down) }
        waitForIdle()
        return manager!!
    }

    @Test
    fun rightFromAnEpisodeReachesItsStreamOptions() = runComposeUiTest {
        val manager = showList()
        runOnIdle { manager.moveFocus(FocusDirection.Right) }
        waitForIdle()
        onAllNodesWithContentDescription("Stream options")[0].assertIsFocused()
    }

    @Test
    fun theWantedMarkerIsNotADpadStop() = runComposeUiTest {
        // An episode already wanted shows a bookmark, but pressing it would do nothing.
        // It must stay out of the focus order or a remote gains a dead stop between the
        // row and its real actions.
        val streamed = Season(
            number = 1,
            episodes = listOf(
                Episode(id = "s1", number = 1, title = "No File", wantStatus = "wanted"),
                Episode(id = "s2", number = 2, title = "Also None", wantStatus = "wanted"),
            ),
        )
        val manager = showList(streamed)
        onAllNodesWithContentDescription("Wanted").assertCountEquals(2)
        runOnIdle { manager.moveFocus(FocusDirection.Right) }
        waitForIdle()
        onAllNodesWithContentDescription("Stream options")[0].assertIsFocused()
    }

    @Test
    fun downFromAnActionStaysInTheListAndMovesToTheNextEpisode() = runComposeUiTest {
        // Splitting the row must not strand focus in the action column: the icons of
        // one episode still have to lead down to the next episode's icons.
        val manager = showList()
        runOnIdle { manager.moveFocus(FocusDirection.Right) }
        waitForIdle()
        runOnIdle { manager.moveFocus(FocusDirection.Down) }
        waitForIdle()
        onAllNodesWithContentDescription("Stream options")[1].assertIsFocused()
    }
}
