package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverSeason
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.navigation.BackInterceptors
import dev.kobbler.app.ui.navigation.LocalBackInterceptors
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Streaming a title the library does not have, from its Discover page.
 *
 * An episode used to be searched for as the show's name alone — no season, no episode,
 * no ids — which the server takes as a movie search, so a show came back with one
 * mismatched release. And every play went through the option list first, where the
 * library page plays the best option straight away and keeps the list behind a button.
 */
@OptIn(ExperimentalTestApi::class)
class DiscoverStreamTest {

    private val show = DiscoverDetail(
        mediaType = "tv", provider = "tmdb", title = "Mayor of Kingstown", year = 2021,
        description = null, posterUrl = "", backdropUrl = null, genres = emptyList(),
        rating = null, cast = emptyList(), network = null, runtime = null,
        authors = emptyList(), libraryId = null, wantStatus = null, isInLibrary = false,
        identifiers = mapOf("tmdb_id" to "97951", "imdb_id" to "tt4489382"), providerId = "97951",
        seasons = listOf(DiscoverSeason(seasonNumber = 1, title = "Season 1", episodeCount = 10, airDate = null)),
    )
    private val movie = show.copy(mediaType = "movie", title = "Wall-E", seasons = emptyList())

    private val option = """
        {"id":"o1","release_title":"Mayor.of.Kingstown.S01.1080p.WEB.h264-GRP","size":9000000000,
         "indexer":"hash_index:torrentio","info_hash":"abc","total_score":0.91,
         "debrid_provider":"realdebrid","debrid_provider_id":"rd-1","cached":true,
         "files":[{"id":3,"path":"Mayor.of.Kingstown.S01/Mayor.of.Kingstown.S01E01.1080p.mkv","size":1}],
         "quality":{"resolution":"1080p","codec":"h264"}}
    """.trimIndent()

    private val routes = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/discover/tv/tmdb/97951/season/1" to { _ ->
            """{"episodes":[{"episode_number":1,"title":"The Mayor"}]}"""
        },
        "/api/v1/admin/stream/search" to { _ -> """{"options":[$option]}""" },
        "/api/v1/admin/stream/resolve" to { _ ->
            """{"session_id":"sess-1","method":"direct_play","direct_url":"/s/1","media_id":"ep-1"}"""
        },
        "/api/v1/admin/tv/episodes/ep-1" to { _ ->
            """{"id":"ep-1","season_id":"se-1","episode_identifier":"1","episode_sort_order":1}"""
        },
        "/api/v1/admin/tv/seasons/se-1" to { _ ->
            """{"id":"se-1","show_id":"show-9","season_identifier":"1","season_sort_order":1}"""
        },
    )

    private class Page(
        val viewed: MutableList<Pair<String, String>>,
        val backs: BackInterceptors,
        val gps: GlobalPlayerState,
    ) {
        var leftPage = false
    }

    // [refetched] stands in for the navigation layer's cached fetch of the page, which a
    // resolve that auto-creates the title invalidates.
    private fun ComposeUiTest.showPage(stub: StubServer, detail: DiscoverDetail, refetched: DiscoverDetail? = null): Page {
        val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
        val page = Page(mutableListOf(), BackInterceptors(), GlobalPlayerState())
        val cacheKey = "discover-${detail.mediaType}-${detail.provider}-${detail.providerId}"
        val shownAt = DataCache.versionOf(cacheKey)
        setContent {
            val current = if (refetched != null && DataCache.versionOf(cacheKey) != shownAt) refetched else detail
            CompositionLocalProvider(LocalBackInterceptors provides page.backs) {
                Box(Modifier.size(1280.dp, 720.dp)) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        DiscoverDetailScreen(
                            detail = current,
                            api = api,
                            formFactor = FormFactor.TV,
                            player = null,
                            gps = page.gps,
                            onBack = { page.leftPage = true },
                            onViewInLibrary = { type, id -> page.viewed += type to id },
                        )
                    }
                }
            }
        }
        waitForIdle()
        return page
    }

    private fun ComposeUiTest.expandFirstSeason() {
        onNodeWithText("Season 1").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) { onAllNodesWithText("The Mayor").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun StubServer.body(path: String): JsonObject =
        Json.parseToJsonElement(requestBodies.getValue(path)).jsonObject

    @Test
    fun `an episode plays straight away, searched for as that episode`() = runUiTest {
        StubServer(routes).use { stub ->
            val page = showPage(stub, show)
            expandFirstSeason()
            onNodeWithText("The Mayor").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { page.viewed.isNotEmpty() }

            onAllNodesWithText("Stream options").assertCountEquals(0)
            val search = stub.body("/api/v1/admin/stream/search")
            assertEquals("tv", search["media_type"]?.jsonPrimitive?.content)
            assertEquals(1, search["season"]?.jsonPrimitive?.int)
            assertEquals(1, search["episode"]?.jsonPrimitive?.int)
            assertEquals(97951, search["tmdb_id"]?.jsonPrimitive?.int)
            assertEquals("tt4489382", search["imdb_id"]?.jsonPrimitive?.content)

            val autoCreate = stub.body("/api/v1/admin/stream/resolve")["auto_create"]!!.jsonObject
            assertEquals(1, autoCreate["season_number"]?.jsonPrimitive?.int)
            assertEquals(1, autoCreate["episode_number"]?.jsonPrimitive?.int)
            assertEquals("tmdb", autoCreate["provider"]?.jsonPrimitive?.content)
            assertEquals("97951", autoCreate["provider_id"]?.jsonPrimitive?.content)

            assertEquals(listOf("tv" to "show-9"), page.viewed, "the episode did not open its show's library page")
        }
    }

    @Test
    fun `an episode still plays when the page turns into its library page under it`() = runUiTest {
        val entries = listOf<ComposeUiTest.() -> Unit>(
            { onNodeWithText("The Mayor").performClick() },
            { onNodeWithText("Stream").performClick() },
            {
                onAllNodesWithContentDescription("Stream options").onFirst().performClick()
                waitUntil(timeoutMillis = UiTimeoutMs) {
                    onAllNodesWithText("Mayor.of.Kingstown.S01.1080p.WEB.h264-GRP").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Mayor.of.Kingstown.S01.1080p.WEB.h264-GRP").performClick()
            },
        )
        // The show lookup after the resolve is a real round trip, long enough for the
        // page's refetch to land first.
        val slowLookup = routes + ("/api/v1/admin/tv/episodes/ep-1" to { e: HttpExchange ->
            Thread.sleep(500)
            routes.getValue("/api/v1/admin/tv/episodes/ep-1")(e)
        })
        entries.forEach { press ->
            StubServer(slowLookup).use { stub ->
                val page = showPage(stub, show, refetched = show.copy(libraryId = "show-9", isInLibrary = true))
                expandFirstSeason()
                press()
                waitUntil(timeoutMillis = UiTimeoutMs) { page.gps.debridPlayback != null }

                assertEquals("tv" to "show-9", page.viewed.last())
            }
        }
    }

    @Test
    fun `the option list is one button away from an episode`() = runUiTest {
        StubServer(routes).use { stub ->
            showPage(stub, show)
            expandFirstSeason()
            onAllNodesWithContentDescription("Stream options").onFirst().performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) {
                onAllNodesWithText("Mayor.of.Kingstown.S01.1080p.WEB.h264-GRP").fetchSemanticsNodes().isNotEmpty()
            }

            assertEquals(1, stub.body("/api/v1/admin/stream/search")["episode"]?.jsonPrimitive?.int)
            assertFalse("/api/v1/admin/stream/resolve" in stub.requestBodies, "picking resolved before a choice")
        }
    }

    @Test
    fun `a movie plays straight away and keeps its options behind a button`() = runUiTest {
        StubServer(routes).use { stub ->
            val page = showPage(stub, movie)
            waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/stream/search" in stub.requestBodies }
            val search = stub.body("/api/v1/admin/stream/search")
            assertEquals(97951, search["tmdb_id"]?.jsonPrimitive?.int)
            assertEquals("tt4489382", search["imdb_id"]?.jsonPrimitive?.content)

            onNodeWithText("Play").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { page.viewed.isNotEmpty() }
            onAllNodesWithText("Stream options").assertCountEquals(0)
            assertTrue(stub.body("/api/v1/admin/stream/resolve")["auto_create"] != null)
        }
    }

    @Test
    fun `back from the option list returns to the page, not past it`() = runUiTest {
        StubServer(routes).use { stub ->
            val page = showPage(stub, movie)
            onAllNodesWithContentDescription("Stream options").onFirst().performClick()
            waitForIdle()
            onNodeWithText("Stream options").assertIsDisplayed()

            assertTrue(page.backs.consume(), "BACK went past the option list")
            waitForIdle()
            onAllNodesWithText("Stream options").assertCountEquals(0)
            assertFalse(page.leftPage)
        }
    }

    @Test
    fun `an option names its release, the file it plays, and where it was found`() = runUiTest {
        StubServer(routes).use { stub ->
            showPage(stub, show)
            expandFirstSeason()
            onAllNodesWithContentDescription("Stream options").onFirst().performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) {
                onAllNodesWithText("Mayor.of.Kingstown.S01.1080p.WEB.h264-GRP").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Mayor.of.Kingstown.S01E01.1080p.mkv", substring = true).assertIsDisplayed()
            onNodeWithText("Torrentio", substring = true).assertIsDisplayed()
            onNodeWithText("91%", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `an episode nothing will stream for offers to want just that episode`() = runUiTest {
        val refused = """{"error":"Real-Debrid refused this release. Try a different source.","reason":"blocked"}"""
        val failing = routes + mapOf(
            "/api/v1/admin/stream/resolve" to { _: HttpExchange -> refused },
            "/api/v1/admin/discover/want-episode" to { _: HttpExchange -> "{}" },
        )
        StubServer(failing, mapOf("/api/v1/admin/stream/resolve" to 502)).use { stub ->
            val page = showPage(stub, show)
            expandFirstSeason()
            onNodeWithText("The Mayor").performClick()
            waitUntil(timeoutMillis = UiTimeoutMs) { page.gps.failureHelp != null }

            val help = page.gps.failureHelp!!
            assertTrue(help.summary.contains("refused"), help.summary)
            kotlinx.coroutines.runBlocking { help.want!!.run() }
            val want = stub.body("/api/v1/admin/discover/want-episode")
            assertEquals(1, want["season_number"]?.jsonPrimitive?.int)
            assertEquals(1, want["episode_number"]?.jsonPrimitive?.int)
            assertEquals("97951", want["provider_id"]?.jsonPrimitive?.content)
            assertTrue(page.viewed.isEmpty(), "opened a library page for a stream that never played")
        }
    }
}
