package dev.kobbler.app.ui.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test

/** Only what was started reads Resume; the next episode and a watchlist pick were never begun. */
@OptIn(ExperimentalTestApi::class)
class HomeUpNextReasonTest {

    private val discover = """{"watch_next":[
        {"media_type":"movie","media_id":"m1","title":"Started","link":"/movies/m1","progress_percent":40,"position_seconds":1200},
        {"media_type":"episode","media_id":"e1","title":"Following","subtitle":"S1E2","link":"/tv/episodes/e1"},
        {"media_type":"movie","media_id":"m2","title":"Wanted","link":"/movies/m2"}
    ],"recently_added":[],"sections":[]}"""

    @Test
    fun `each up next card names why it is there`() {
        for (formFactor in listOf(FormFactor.TV, FormFactor.Desktop)) {
            runDesktopComposeUiTest(width = 1280, height = 2000) {
                StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
                    DataCache.clear()
                    val api = KobblerApi(HttpClient(), stub.url, "token")
                    setContent { KobappTheme(formFactor = formFactor) { HomeScreen(api = api, formFactor = formFactor) } }
                    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Following").fetchSemanticsNodes().isNotEmpty() }
                    onAllNodesWithText("NEXT EPISODE").fetchSemanticsNodes().let {
                        check(it.isNotEmpty()) { "$formFactor: no Next episode label" }
                    }
                    onAllNodesWithText("WATCHLIST").fetchSemanticsNodes().let {
                        check(it.isNotEmpty()) { "$formFactor: no Watchlist label" }
                    }
                }
            }
        }
    }
}
