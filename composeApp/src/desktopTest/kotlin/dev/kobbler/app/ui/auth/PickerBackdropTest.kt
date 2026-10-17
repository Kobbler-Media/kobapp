package dev.kobbler.app.ui.auth

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.AuthScreen
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.InMemoryKeyValueStore
import dev.kobbler.app.data.Profile
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.StubServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The profile picker stands on the library's newest arrivals, cycling, with a note of
 * which title is showing. It asks as a Kids profile when the household has one, since
 * everyone sees this screen.
 */
@OptIn(ExperimentalTestApi::class)
class PickerBackdropTest {

    private val discover = """{"watch_next":[],"recently_added":[
        {"item_type":"movie","id":"r1","title":"Recent One","subtitle":"2024","link":"/movies/r1","backdrop_url":"/img/r1-backdrop"},
        {"item_type":"movie","id":"r2","title":"Recent Two","subtitle":"2023","link":"/movies/r2","backdrop_url":"/img/r2-backdrop"}
    ],"sections":[]}"""

    private fun picker(profiles: (String) -> List<Profile>, test: DesktopComposeUiTest.(tokens: List<String>) -> Unit) =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val tokens = CopyOnWriteArrayList<String>()
            StubServer(
                mapOf(
                    "/api/v1/admin/discover" to { exchange ->
                        tokens += exchange.requestHeaders.getFirst("Authorization").orEmpty()
                        discover
                    },
                ),
            ).use { stub ->
                val roster = profiles(stub.url)
                val store = InMemoryKeyValueStore().apply {
                    putString("kobbler_profiles_v1", Json.encodeToString(roster))
                    putString("kobbler_current_profile_id", roster.first().id)
                    putString("kobbler_show_picker_on_launch", "true")
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val auth = AuthState(scope, FormFactor.Desktop, store)
                    waitUntil(timeoutMillis = 5_000) { auth.screen != AuthScreen.Loading }
                    setContent {
                        KobappTheme(formFactor = FormFactor.Desktop) {
                            ProfilePickerScreen(auth = auth, formFactor = FormFactor.Desktop)
                        }
                    }
                    test(tokens)
                } finally {
                    scope.cancel()
                }
            }
        }

    private fun DesktopComposeUiTest.shows(title: String) =
        onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the newest arrivals cycle behind the profiles`() = picker({ url ->
        listOf(Profile(id = "adult", token = "adult-token", apiUrl = url))
    }) {
        waitUntil(timeoutMillis = 10_000) { shows("Recent One") }
        assertTrue(shows("RECENTLY ADDED"))
        repeat(60) {
            if (shows("Recent Two")) return@picker
            mainClock.advanceTimeBy(500)
            waitForIdle()
        }
        assertTrue(shows("Recent Two"), "the picture never moved on")
    }

    @Test
    fun `a household with a kids profile shows what the kids profile may see`() = picker({ url ->
        listOf(
            Profile(id = "adult", token = "adult-token", apiUrl = url),
            Profile(id = "kid", token = "kid-token", apiUrl = url, isKids = true),
        )
    }) { tokens ->
        waitUntil(timeoutMillis = 10_000) { shows("Recent One") }
        assertTrue(tokens.isNotEmpty())
        assertEquals(setOf("Bearer kid-token"), tokens.toSet())
    }
}
