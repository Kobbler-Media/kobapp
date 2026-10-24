package dev.kobbler.app.ui.auth

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import coil3.SingletonImageLoader
import dev.kobbler.app.data.AuthScreen
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.InMemoryKeyValueStore
import dev.kobbler.app.data.Profile
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.cachedArtLoader
import dev.kobbler.app.ui.player.StubServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

private const val PickerDiscover = """{"watch_next":[],"recently_added":[
    {"item_type":"movie","id":"r1","title":"Recent One","subtitle":"2024","link":"/movies/r1","backdrop_url":"/img/r1-backdrop"},
    {"item_type":"movie","id":"r2","title":"Recent Two","subtitle":"2023","link":"/movies/r2","backdrop_url":"/img/r2-backdrop"}
],"sections":[]}"""

@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.shows(text: String) =
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

@OptIn(ExperimentalTestApi::class)
internal fun picker(
    profiles: (String) -> List<Profile>,
    discoverDelayMs: Long = 0,
    formFactor: FormFactor = FormFactor.Desktop,
    width: Int = 1280,
    height: Int = 800,
    test: DesktopComposeUiTest.(tokens: List<String>, auth: AuthState) -> Unit,
) = runUiTest(width = width, height = height) {
    val tokens = CopyOnWriteArrayList<String>()
    SingletonImageLoader.setUnsafe(cachedArtLoader())
    StubServer(
        mapOf(
            "/api/v1/admin/discover" to { exchange ->
                tokens += exchange.requestHeaders.getFirst("Authorization").orEmpty()
                Thread.sleep(discoverDelayMs)
                PickerDiscover
            },
            "/api/v1/admin/me" to { exchange ->
                val token = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
                """{"user_id":"$token","email":"","role":"viewer","display_name":"$token"}"""
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
            val auth = AuthState(scope, formFactor, store)
            waitUntil(timeoutMillis = UiTimeoutMs) { auth.screen != AuthScreen.Loading }
            setContent {
                KobappTheme(formFactor = formFactor) {
                    ProfilePickerScreen(auth = auth, formFactor = formFactor)
                }
            }
            test(tokens, auth)
        } finally {
            scope.cancel()
            SingletonImageLoader.reset()
        }
    }
}
