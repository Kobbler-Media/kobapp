package dev.kobbler.app.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LocalAuthState
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobfocus.LocalFocusScopeStore
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BACK from a result opened out of search lands on that result again: same query, same
 * results without a refetch, focus on the row that was opened — never the query field,
 * whose focus raises the on-screen keyboard on a TV.
 */
@OptIn(ExperimentalTestApi::class)
class SearchReturnTest {

    private val searches = AtomicInteger()

    private val hits = buildString {
        append("""{"top_hits":[],"remaining":[""")
        append(
            (1..4).joinToString(",") { """{"type":"movie","id":"m$it","title":"Block Movie $it"}""" } + "," +
                (1..4).joinToString(",") { """{"type":"tv_show","id":"s$it","title":"Block Show $it"}""" } + "," +
                (1..4).joinToString(",") { """{"type":"book","id":"b$it","title":"Block Book $it"}""" },
        )
        append("]}")
    }

    private val routes = mapOf<String, (com.sun.net.httpserver.HttpExchange) -> String>(
        "/api/v1/admin/search" to { _ -> searches.incrementAndGet(); hits },
        "/api/v1/library/books/b1" to { _ -> """{"id":"b1","title":"Block Book 1"}""" },
    )

    @Test
    fun `back from a result restores the results and focuses the result that was opened`() = runUiTest(width = 1280, height = 720) {
        StubServer(routes).use { stub ->
            val nav = NavigationState().apply {
                searchQuery = "block"
                searchOpen = true
            }
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
            val target = "Block Book 1"
            waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText(target).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(target).requestFocus()
            waitForIdle()
            press(Key.Enter)
            waitUntil(timeoutMillis = UiTimeoutMs) { nav.current == Destination.BookDetail("b1") }

            press(Key.Back)
            waitUntil(timeoutMillis = UiTimeoutMs) { nav.searchOpen && focusedOn(target) }
            mainClock.advanceTimeBy(1_000)
            waitForIdle()

            assertTrue(focusedOn(target), "focus left the opened result")
            assertTrue(
                onAllNodes(isFocused() and hasSetTextAction()).fetchSemanticsNodes().isEmpty(),
                "the query field took focus and would raise the keyboard",
            )
            assertEquals("block", nav.searchQuery)
            assertEquals(1, searches.get(), "results were fetched again")
        }
    }

    private fun DesktopComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(400)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.focusedOn(text: String) =
        onAllNodes(isFocused() and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
}
