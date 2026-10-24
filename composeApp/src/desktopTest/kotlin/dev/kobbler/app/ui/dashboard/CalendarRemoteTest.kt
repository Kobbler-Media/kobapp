package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.ui.navigation.DesktopScaffold
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.NavigationState
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.assertTrue

/** The calendar's filters stay within a remote's reach of its list. */
@OptIn(ExperimentalTestApi::class)
class CalendarRemoteTest {

    // Every range the screen asks for has releases in it, as a real library's past does.
    private fun calendar(query: String): String {
        val from = LocalDate.parse(Regex("from=([0-9-]+)").find(query)!!.groupValues[1])
        val to = LocalDate.parse(Regex("to=([0-9-]+)").find(query)!!.groupValues[1])
        val days = generateSequence(from) { it.plus(7, DateTimeUnit.DAY) }.takeWhile { it <= to }
        return days.joinToString(",", """{"items":[""", "]}") {
            """{"id":"s$it","media_type":"tv","date":"$it","title":"Show on $it","link":"/tv/s"}"""
        }
    }

    private fun DesktopComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }

    private fun calendarOnTv(test: DesktopComposeUiTest.() -> Unit) = runUiTest(width = 960, height = 540) {
        StubServer(mapOf("/api/v1/admin/calendar" to { ex -> calendar(ex.requestURI.query) })).use { stub ->
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
            mainClock.advanceTimeBy(1_000)
            runOnIdle { nav.navigate(Destination.Tab.Calendar) }
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Show", substring = true).fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(1_000)
            test()
        }
    }

    private fun DesktopComposeUiTest.leftTheList(): Boolean =
        onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() &&
            onAllNodes(isFocused() and hasText("Show", substring = true)).fetchSemanticsNodes().isEmpty()

    @Test
    fun `up from the list reaches the filters`() = calendarOnTv {
        repeat(80) {
            if (leftTheList()) return@calendarOnTv
            press(Key.DirectionUp)
        }
        assertTrue(false, "80 presses up never left the list")
    }

    /** The filters sit above the list's whole past; RIGHT is the short way there. */
    @Test
    fun `right from a row jumps to the filters`() = calendarOnTv {
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        assertTrue(leftTheList(), "RIGHT stayed in the list")
        assertTrue(onAllNodes(isFocused() and hasText("TV")).fetchSemanticsNodes().isNotEmpty(), "RIGHT did not land on the first filter")
    }
}
