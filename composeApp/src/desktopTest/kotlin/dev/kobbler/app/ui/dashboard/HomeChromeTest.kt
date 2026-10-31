package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlin.test.Test

/** Home carries the art and the titles; the progress bar says what was started, and no status is spelled out as a label. */
@OptIn(ExperimentalTestApi::class)
class HomeChromeTest {

    private val discover = """{"watch_next":[
        {"media_type":"movie","media_id":"m1","title":"Started","link":"/movies/m1","progress_percent":40,"position_seconds":1200},
        {"media_type":"episode","media_id":"e1","title":"Following","subtitle":"S1E2","link":"/tv/episodes/e1"},
        {"media_type":"movie","media_id":"m2","title":"Wanted","link":"/movies/m2"}
    ],"recently_added":[
        {"item_type":"movie","id":"r1","title":"Recent 1","subtitle":"2024","link":"/movies/r1"},
        {"item_type":"movie","id":"r2","title":"Recent 2","subtitle":"2024","link":"/movies/r2"}
    ],"sections":[
        {"title":"Popular Movies","section_key":"popular_movies","sort_order":1,"items":[
            {"provider":"tmdb","provider_id":"1","media_type":"movie","title":"Asked For","year":2024,"want_status":"wanted"},
            {"provider":"tmdb","provider_id":"2","media_type":"movie","title":"Already Here","year":2024,"is_in_library":true}
        ]}
    ]}"""

    @Test
    fun `home spells out nothing the art and progress already say`() {
        for (formFactor in listOf(FormFactor.TV, FormFactor.Desktop)) {
            runUiTest(width = 1280, height = 2000) {
                StubServer(mapOf("/api/v1/admin/discover" to { _ -> discover })).use { stub ->
                    DataCache.clear()
                    val api = KobblerApi(HttpClient(), stub.url, "token")
                    setContent { KobappTheme(formFactor = formFactor) { HomeScreen(api = api, formFactor = formFactor) } }
                    waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("Asked For").fetchSemanticsNodes().isNotEmpty() }
                    for (text in listOf("RESUME", "NEXT EPISODE", "WATCHLIST", "WANTED")) {
                        check(onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) { "$formFactor: '$text' is spelled out" }
                    }
                    for (text in listOf("UP NEXT ·", "new this week")) {
                        check(onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()) { "$formFactor: '$text' is shown" }
                    }
                    for (mark in listOf("Wanted", "In library")) {
                        check(onAllNodesWithContentDescription(mark).fetchSemanticsNodes().isNotEmpty()) { "$formFactor: no '$mark' mark" }
                    }
                }
            }
        }
    }
}
