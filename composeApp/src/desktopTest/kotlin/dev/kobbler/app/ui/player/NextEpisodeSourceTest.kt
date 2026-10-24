package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Debrid playback of an episode hands over to the next one. When that next episode has a
 * file in the library it plays the file, through the library session, and debrid is never
 * asked for it: no prewarm, no claim, no search, no resolve.
 */
@OptIn(ExperimentalTestApi::class)
class NextEpisodeSourceTest {

    private enum class Entry { Finish, SkipNext, PlayEpisode }

    private fun show(nextFile: String?) = TvShow(
        id = "show-1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(
            Season(
                number = 1,
                episodes = listOf(
                    Episode(id = "ep-1", number = 1, title = "One"),
                    Episode(id = "ep-2", number = 2, title = "Two", filePath = nextFile),
                ),
            ),
        ),
        continueEpisode = ContinueEpisode(seasonIdx = 0, episodeIdx = 0, episodeId = "ep-1"),
    )

    private fun session(id: String) =
        """{"session_id":"$id","method":"direct_play","direct_url":"/api/v1/admin/stream/$id/play","duration_seconds":3600}"""

    private val routes = mapOf<String, (HttpExchange) -> String>(
        "/api/v1/admin/playback/sessions" to { _ -> session("lib2") },
        "/api/v1/admin/stream/search" to { _ ->
            """{"options":[{"id":"o1","release_title":"Show.S01E01","debrid_provider":"realdebrid",
                "debrid_provider_id":"p1","info_hash":"h1","cached":true}],"errors":[]}"""
        },
        "/api/v1/admin/stream/resolve" to { _ -> session("sess-1") },
        "/api/v1/admin/stream/sess-1/outcome" to { _ -> "{}" },
        "/api/v1/admin/stream/prewarm" to { _ ->
            """{"state":"ready","prewarm_id":"pw1","option":{"id":"o2","release_title":"Show.S01E02",
                "debrid_provider":"realdebrid","debrid_provider_id":"p1","info_hash":"h2","cached":true}}"""
        },
        "/api/v1/admin/stream/prewarm/pw1/touch" to { _ -> "{}" },
        "/api/v1/admin/stream/prewarm/pw1" to { _ -> "" },
        "/api/v1/admin/stream/prewarm/pw1/claim" to { _ -> session("sp") },
        "/api/v1/admin/stream/sp/outcome" to { _ -> "{}" },
    )

    private class Rig(val stub: StubServer, val gps: GlobalPlayerState, val player: FakePlayer) {
        fun asked(path: String) = path in stub.requestBodies
        fun askedFor(path: String, episodeId: String) = stub.requestBodies[path]?.contains("\"$episodeId\"") == true
    }

    private fun playNext(
        entry: Entry,
        nextFile: String?,
        libraryHasNext: Boolean = true,
        refreshedNextFile: String? = nextFile,
        check: ComposeUiTest.(Rig) -> Unit,
    ) = runUiTest {
        val codeFor = { path: String, body: String ->
            when {
                path != "/api/v1/admin/playback/sessions" -> null
                "\"ep-2\"" in body && libraryHasNext -> null
                else -> 404
            }
        }
        StubServer(routes, codeFor = codeFor).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
            val player = FakePlayer().also { it.durationMs.value = 3_600_000 }
            val gps = GlobalPlayerState()
            var current by mutableStateOf(show(nextFile))
            setContent {
                InlineTvPlayer(show = current, api = api, formFactor = FormFactor.Phone, player = player, gps = gps, autoPlay = true, onBack = {})
            }
            waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == "ep-1" && gps.isDebrid }
            runOnIdle { current = show(refreshedNextFile) }
            waitForIdle()

            player.currentMs.value = 2_900_000
            if (refreshedNextFile == null) {
                waitUntil(timeoutMillis = UiTimeoutMs) { "/api/v1/admin/stream/prewarm" in stub.requestBodies }
            } else {
                Thread.sleep(1_000)
            }
            waitForIdle()

            runOnIdle {
                when (entry) {
                    Entry.Finish -> assertNotNull(gps.onFinished, "no autoplay handler")()
                    Entry.SkipNext -> assertNotNull(gps.onSkipNext, "no skip-next handler")()
                    Entry.PlayEpisode -> assertNotNull(gps.onPlayEpisode, "no play-episode handler")("ep-2")
                }
            }
            check(Rig(stub, gps, player))
        }
    }

    private fun ComposeUiTest.assertPlayedLocally(rig: Rig, case: String) {
        waitUntil(timeoutMillis = UiTimeoutMs) { rig.gps.mediaId == "ep-2" }
        assertFalse(rig.gps.isDebrid, "$case: the next episode came from debrid")
        assertTrue(rig.player.loadedUrl?.contains("/lib2/") == true, "$case: played ${rig.player.loadedUrl}")
        assertNoDebridFor(rig, case)
    }

    private fun assertNoDebridFor(rig: Rig, case: String) {
        assertFalse(rig.asked("/api/v1/admin/stream/prewarm"), "$case: the next episode was prewarmed")
        assertFalse(rig.asked("/api/v1/admin/stream/prewarm/pw1/claim"), "$case: a prewarm was claimed")
        assertFalse(rig.askedFor("/api/v1/admin/stream/search", "ep-2"), "$case: debrid was searched")
        assertFalse(rig.askedFor("/api/v1/admin/stream/resolve", "ep-2"), "$case: debrid was resolved")
    }

    @Test
    fun `a next episode with a file plays through the library from every way of reaching it`() =
        Entry.entries.forEach { entry ->
            playNext(entry, nextFile = "stream") { rig -> assertPlayedLocally(rig, entry.name) }
        }

    @Test
    fun `a next episode whose file arrived during playback plays through the library`() =
        Entry.entries.forEach { entry ->
            playNext(entry, nextFile = null, refreshedNextFile = "stream") { rig -> assertPlayedLocally(rig, entry.name) }
        }

    @Test
    fun `a next episode with a file the library cannot open is not streamed instead`() =
        Entry.entries.forEach { entry ->
            playNext(entry, nextFile = "stream", libraryHasNext = false) { rig ->
                waitUntil(timeoutMillis = UiTimeoutMs) { rig.gps.loadingError != null || rig.gps.mediaId == null }
                assertNoDebridFor(rig, entry.name)
            }
        }

    @Test
    fun `a next episode without a file is prewarmed and claimed from debrid as before`() =
        Entry.entries.forEach { entry ->
            playNext(entry, nextFile = null, libraryHasNext = false) { rig ->
                waitUntil(timeoutMillis = UiTimeoutMs) { rig.gps.mediaId == "ep-2" }
                assertTrue(rig.gps.isDebrid, "${entry.name}: not debrid")
                assertTrue(rig.asked("/api/v1/admin/stream/prewarm/pw1/claim"), "${entry.name}: the prewarm went unclaimed")
                assertEquals("sp", rig.gps.sessionId, entry.name)
            }
        }
}
