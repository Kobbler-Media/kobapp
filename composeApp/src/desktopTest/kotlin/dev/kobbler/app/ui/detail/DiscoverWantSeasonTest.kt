package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverSeason
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Season one seen elsewhere, season two wanted: a show is not all-or-nothing from Discover either. */
@OptIn(ExperimentalTestApi::class)
class DiscoverWantSeasonTest {

    private val show = DiscoverDetail(
        mediaType = "tv", provider = "tmdb", title = "Stick", year = 2025,
        description = null, posterUrl = "", backdropUrl = null, genres = emptyList(),
        rating = null, cast = emptyList(), network = null, runtime = null,
        authors = emptyList(), libraryId = null, wantStatus = null, isInLibrary = false,
        identifiers = mapOf("tmdb_id" to "42"), providerId = "42",
        seasons = listOf(DiscoverSeason(1, null, 10, null), DiscoverSeason(2, null, 10, null)),
    )

    private val routes = mapOf(
        "/api/v1/admin/discover/want-season" to { _: com.sun.net.httpserver.HttpExchange ->
            """{"show_id":"show-9","season_id":"season-2","season_number":2,"total_episodes":10,"already_have":0,"already_wanted":0,"newly_wanted":10,"newly_wanted_episodes":[],"queued":true}"""
        },
    )

    @Test
    fun `one season is wanted on its own and the show opens in the library`() {
        for (formFactor in listOf(FormFactor.Desktop, FormFactor.Phone)) runUiTest(width = 1280, height = 900) {
            StubServer(routes).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val viewed = mutableListOf<Pair<String, String>>()
                setContent {
                    Box(Modifier.size(if (formFactor == FormFactor.Phone) 412.dp else 1280.dp, 900.dp)) {
                        KobappTheme(formFactor = formFactor) {
                            DiscoverDetailScreen(
                                detail = show, api = api, formFactor = formFactor, player = null,
                                gps = GlobalPlayerState(), onBack = {},
                                onViewInLibrary = { type, id -> viewed += type to id },
                            )
                        }
                    }
                }
                waitForIdle()
                onNodeWithText("Season 2").performClick()
                waitForIdle()
                onNode(hasContentDescription("Season 2 options", substring = true), useUnmergedTree = true).performClick()
                waitForIdle()
                onNodeWithText("Want season 2").performClick()
                waitUntil(timeoutMillis = UiTimeoutMs) { viewed.isNotEmpty() }

                assertEquals(listOf("tv" to "show-9"), viewed, "$formFactor")
                val body = Json.parseToJsonElement(stub.requestBodies.getValue("/api/v1/admin/discover/want-season")).jsonObject
                assertEquals(2, body.getValue("season_number").jsonPrimitive.int, "$formFactor")
                assertEquals("42", body.getValue("provider_id").jsonPrimitive.content, "$formFactor")
            }
        }
    }
}
