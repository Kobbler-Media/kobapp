package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal const val SHOW_PATH = "/api/v1/admin/tv/shows/show-1"

internal fun showJson(continueSeasonIdx: Int = 2, continueEpisodeIdx: Int = 1, position: Int = 600): String = buildString {
    append("""{"id":"show-1","title":"Remote Show","year":2015,"kids_allowed":false,"content_rating":"TV-MA","seasons":[""")
    append(
        (1..4).joinToString(",") { s ->
            val eps = (1..3).joinToString(",") { e ->
                val watched = s < 3
                """{"id":"e$s-$e","episode_identifier":"$e","episode_sort_order":$e,"title":"S${s}E$e title",
                   "runtime":45,"is_in_library":true,"watched":$watched}"""
            }
            """{"id":"season-$s","season_identifier":"$s","season_sort_order":$s,"episodes":[$eps]}"""
        },
    )
    append("""],"continue_episode":{"season_idx":$continueSeasonIdx,"episode_idx":$continueEpisodeIdx,""")
    append(""""episode_id":"e${continueSeasonIdx + 1}-${continueEpisodeIdx + 1}","position_seconds":$position}}""")
}

/** The show page driven by a remote, through the real scaffold and its rail. */
@OptIn(ExperimentalTestApi::class)
class ShowDetailRemoteTest {

    private fun showPage(test: DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 960, height = 540) {
            DataCache.clear()
            StubServer(mapOf(SHOW_PATH to { _ -> showJson() })).use { stub ->
                val nav = NavigationState()
                setContent {
                    val auth = AuthState(rememberCoroutineScope(), FormFactor.TV)
                    // The default focus memory is process-wide; a page reopened by a later test
                    // would otherwise restore focus to wherever the last one left it.
                    CompositionLocalProvider(LocalAuthState provides auth, LocalFocusScopeStore provides FocusScopeStore()) {
                        KobappTheme(formFactor = FormFactor.TV) {
                            DesktopScaffold(
                                nav = nav,
                                navConfig = defaultNavConfig,
                                api = KobblerApi(HttpClient(), stub.url, "token", canManageKids = true),
                                formFactor = FormFactor.TV,
                                player = null,
                                gps = GlobalPlayerState(),
                            )
                        }
                    }
                }
                mainClock.advanceTimeBy(1_000)
                runOnIdle { nav.navigate(Destination.TvShowDetail("show-1")) }
                waitUntil(timeoutMillis = 10_000) { focusedText() != null }
                test()
            }
        }

    private fun DesktopComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(400)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.focusedText(): String? =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
            .fetchSemanticsNodes().lastOrNull()
            ?.let { node ->
                node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString(" ")
                    ?: node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString(" ")
                    ?: ""
            }

    @Test
    fun `entry focus is the play action`() = showPage {
        assertTrue(focusedText()!!.startsWith("Resume"), "entry focus was ${focusedText()}")
    }

    @Test
    fun `left from the play action opens the rail`() = showPage {
        press(Key.DirectionLeft)
        // The rail is driven by the scaffold's own key handling: opening it parks
        // focus on the scaffold root, which carries no label of its own.
        assertEquals("", focusedText(), "left landed on a page control instead of the rail")
    }

    @Test
    fun `up from an episode returns to the season it lists`() {
        for (column in 0..2) showPage {
            press(Key.DirectionDown)
            assertEquals("Season 3", focusedText(), "one press down from the actions")
            press(Key.DirectionLeft)
            press(Key.DirectionLeft)
            assertEquals("Season 1", focusedText())
            press(Key.DirectionDown)
            repeat(column) { press(Key.DirectionRight) }
            press(Key.DirectionUp)
            assertEquals("Season 1", focusedText(), "up from column $column")
        }
    }

    @Test
    fun `ok on a season tab opens that season's options without moving off it`() = showPage {
        press(Key.DirectionDown)
        assertEquals("Season 3", focusedText())
        press(Key.Enter)
        assertTrue(onAllNodesWithText("Mark season 3 watched").fetchSemanticsNodes().isNotEmpty(), "no season menu")
        assertTrue(onAllNodesWithText("S3E1 title").fetchSemanticsNodes().isNotEmpty(), "the season changed")
    }
}
