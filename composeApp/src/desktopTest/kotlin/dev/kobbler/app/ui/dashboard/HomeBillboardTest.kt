package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.dashboard.home.HomeBillboardDetailsTag
import dev.kobbler.app.ui.dashboard.home.HomeBillboardPlayTag
import dev.kobbler.app.ui.dashboard.home.HomeBillboardProgressTag
import dev.kobbler.app.ui.dashboard.home.HomeHeroTag
import dev.kobbler.app.ui.dashboard.home.UpNextCardTag
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.performSemanticsAction
import dev.kobbler.app.ui.dashboard.home.HomeWide
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** The billboard above Up Next describes the focused card and offers Play and its details page. */
@OptIn(ExperimentalTestApi::class)
class HomeBillboardTest {

    private val fresh = """{"media_type":"movie","media_id":"m1","title":"Fresh","link":"/movies/m1"}"""
    private val reading = """{"media_type":"book","media_id":"b1","title":"Reading","link":"/library/books/b1","progress_percent":25}"""
    private val following =
        """{"media_type":"episode","media_id":"e1","title":"Following","subtitle":"S1E2 - Pilot","link":"/tv/episodes/e1",""" +
            """"progress_percent":40,"position_seconds":1200,"duration_seconds":3000}"""

    private fun discover(vararg items: String) =
        """{"watch_next":[${items.joinToString(",")}],""" +
            """"recently_added":[{"item_type":"movie","id":"r1","title":"Recent 1","subtitle":"2024","link":"/movies/r1"}],"sections":[]}"""

    private fun home(
        later: String = discover(fresh, reading, following),
        test: DesktopComposeUiTest.(nav: NavigationState, discoverHits: AtomicInteger) -> Unit,
    ) = runUiTest(width = 960, height = 540) {
        DataCache.clear()
        val hits = AtomicInteger()
        val first = discover(fresh, reading, following)
        StubServer(mapOf("/api/v1/admin/discover" to { _ -> if (hits.incrementAndGet() == 1) first else later })).use { stub ->
            val nav = NavigationState()
            setContent {
                val auth = AuthState(rememberCoroutineScope(), FormFactor.TV)
                CompositionLocalProvider(LocalAuthState provides auth, LocalFocusScopeStore provides FocusScopeStore()) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        DesktopScaffold(
                            nav = nav,
                            navConfig = defaultNavConfig,
                            api = KobblerApi(HttpClient(), stub.url, "token"),
                            formFactor = FormFactor.TV,
                            player = null,
                            gps = GlobalPlayerState(),
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Fresh").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = UiTimeoutMs) { runCatching { onNodeWithTag("$UpNextCardTag.0").assertIsFocused() }.isSuccess }
            test(nav, hits)
        }
    }

    private fun DesktopComposeUiTest.press(keys: List<Key>) = keys.forEach {
        onRoot().performKeyInput { pressKey(it) }
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.billboard(tag: String, text: String) =
        onAllNodes(hasTestTag(tag) and hasAnyDescendant(hasText(text, substring = true)), useUnmergedTree = true).fetchSemanticsNodes().size == 1

    @Test
    fun `the billboard describes whichever up next card has focus`() = home { _, _ ->
        assertEquals(2, onAllNodesWithText("Fresh").fetchSemanticsNodes().size, "the billboard does not name the first card")
        check(billboard(HomeBillboardPlayTag, "Play")) { "an unstarted movie does not offer Play" }
        check(billboard(HomeBillboardDetailsTag, "Details")) { "a movie's details button is not 'Details'" }

        press(listOf(Key.DirectionRight))
        check(billboard(HomeBillboardPlayTag, "Read")) { "a book does not offer Read" }
        check(billboard(HomeBillboardDetailsTag, "Book details")) { "a book's details button is not 'Book details'" }
        check(billboard(HomeBillboardProgressTag, "25%")) { "a book with no duration does not show its percentage" }

        press(listOf(Key.DirectionRight))
        assertEquals(2, onAllNodesWithText("Following").fetchSemanticsNodes().size, "the billboard does not follow focus")
        check(onAllNodesWithText("S1E2 - Pilot").fetchSemanticsNodes().isNotEmpty()) { "the episode line is missing" }
        check(billboard(HomeBillboardPlayTag, "Resume")) { "a started episode does not offer Resume" }
        check(billboard(HomeBillboardDetailsTag, "Episodes")) { "an episode's details button is not 'Episodes'" }
        check(billboard(HomeBillboardProgressTag, "30m left")) { "the time left is not spelled out" }
    }

    /** Play, Read and Resume, each details label, with and without a progress bar: all occupy the same space. */
    @Test
    fun `stepping between items never moves the billboard`() = home { _, _ ->
        val bounds = List(3) { i ->
            if (i > 0) press(listOf(Key.DirectionRight))
            listOf(HomeHeroTag, HomeBillboardPlayTag, HomeBillboardDetailsTag).associateWith { onNodeWithTag(it).getUnclippedBoundsInRoot() } +
                (HomeBillboardProgressTag to onNodeWithTag(HomeBillboardProgressTag, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.copy(right = it.left) })
        }
        assertEquals(bounds[0], bounds[1], "Play → Read moved the billboard")
        assertEquals(bounds[0], bounds[2], "Play → Resume moved the billboard")
    }

    /** As on a details page, how far in you are rides inside the Play button rather than beside it. */
    @Test
    fun `progress sits inside the play button`() = home { _, _ ->
        press(listOf(Key.DirectionRight, Key.DirectionRight))
        val play = onNodeWithTag(HomeBillboardPlayTag).getUnclippedBoundsInRoot()
        val progress = onNodeWithTag(HomeBillboardProgressTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        check(progress.left >= play.left && progress.right <= play.right && progress.top >= play.top && progress.bottom <= play.bottom) {
            "progress $progress is outside the play button $play"
        }
    }

    /** Whichever card Up is pressed from, and whichever button is nearer, the row is entered on Play. */
    @Test
    fun `up from any up next card lands on play`() {
        for (card in 0..2) home { _, _ ->
            press(List(card) { Key.DirectionRight } + Key.DirectionUp)
            onNodeWithTag(HomeBillboardPlayTag).assertIsFocused()
        }
    }

    @Test
    fun `play starts the item and details opens its page without playing`() {
        val cases = listOf(
            Triple(0, false, Destination.MovieDetail("m1", autoPlay = true)),
            Triple(0, true, Destination.MovieDetail("m1", autoPlay = false)),
            Triple(1, true, Destination.BookDetail("b1")),
            Triple(2, false, Destination.TvShowDetail("e1", autoPlay = true, isEpisodeId = true)),
            Triple(2, true, Destination.TvShowDetail("e1", autoPlay = false, isEpisodeId = true)),
        )
        for ((card, details, expected) in cases) home { nav, _ ->
            press(List(card) { Key.DirectionRight } + Key.DirectionUp)
            if (details) press(listOf(Key.DirectionRight))
            press(listOf(Key.Enter))
            waitUntil(timeoutMillis = UiTimeoutMs) { nav.current == expected }
        }
    }

    /** Coming back refetches, and the list can come back reordered while focus returns to Details. */
    @Test
    fun `a reordered up next keeps the billboard on the same item`() = runUiTest(width = 960, height = 540) {
        var response = discover(fresh, reading, following)
        StubServer(mapOf("/api/v1/admin/discover" to { _ -> response })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val homeData = mutableStateOf(runBlocking { api.getDiscover() })
            var detailsFor: String? = null
            lateinit var focusManager: FocusManager
            setContent {
                focusManager = LocalFocusManager.current
                KobappTheme(formFactor = FormFactor.TV) {
                    HomeWide(
                        homeData = homeData.value,
                        api = api,
                        onContinueClick = {},
                        onRecentClick = {},
                        onDiscoverItemClick = {},
                        onContinueDetails = { detailsFor = it.id },
                    )
                }
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Following").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("$UpNextCardTag.2").performSemanticsAction(SemanticsActions.RequestFocus)
            runOnIdle { focusManager.moveFocus(FocusDirection.Up) }
            runOnIdle { focusManager.moveFocus(FocusDirection.Right) }
            onNodeWithTag(HomeBillboardDetailsTag).assertIsFocused()

            response = discover(following, fresh, reading)
            homeData.value = runBlocking { api.getDiscover() }
            waitForIdle()
            check(billboard(HomeBillboardDetailsTag, "Episodes")) { "the billboard moved off the episode" }
            onNodeWithTag(HomeBillboardDetailsTag).assertIsFocused()
            onNodeWithTag(HomeBillboardDetailsTag).performSemanticsAction(SemanticsActions.OnClick)
            assertEquals("e1", detailsFor, "Details opened the item now in the episode's old slot")
        }
    }
}
