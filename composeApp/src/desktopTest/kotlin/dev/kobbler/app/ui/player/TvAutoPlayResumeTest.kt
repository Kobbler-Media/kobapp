package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val show = TvShow(
    id = "show-1", title = "Show", year = 2020, posterUrl = "",
    seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One")))),
    continueEpisode = ContinueEpisode(seasonIdx = 0, episodeIdx = 0, episodeId = "ep-1", positionSeconds = 1200),
)

private val routes = mapOf(
    // No local file: the episode can only come from debrid.
    "/api/v1/admin/tv/episodes/ep-1/files" to { _: com.sun.net.httpserver.HttpExchange -> "[]" },
    "/api/v1/admin/stream/search" to { _: com.sun.net.httpserver.HttpExchange ->
        """{"options":[{"id":"o1","release_title":"Show.S01E01","debrid_provider":"realdebrid",
            "debrid_provider_id":"p1","cached":true}],"errors":[]}"""
    },
    "/api/v1/admin/stream/resolve" to { _: com.sun.net.httpserver.HttpExchange ->
        """{"session_id":"sess-1","method":"DirectPlay","direct_url":"/api/v1/admin/stream/sess-1/play",
            "duration_seconds":3600,"subtitles":[],"audio_tracks":[]}"""
    },
)

@OptIn(ExperimentalTestApi::class)
class TvAutoPlayResumeTest {

    @Test
    fun continueWatchingADebridEpisodeResumesAndClosesCleanly() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
            val player = FakePlayer().also { it.durationMs.value = 3_600_000 }
            val gps = GlobalPlayerState()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            setContent {
                InlineTvPlayer(
                    show = show, api = api, formFactor = FormFactor.Phone,
                    player = player, gps = gps, autoPlay = true, onBack = {},
                )
            }

            waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == "ep-1" && gps.isFullscreen }
            assertEquals(1_200_000L, gps.pendingSeekMs)

            runOnIdle { gps.stop(player, api, scope) }
            waitForIdle()
            Thread.sleep(2_000)
            waitForIdle()

            assertNull(gps.mediaId, "the detail screen relaunched the player after it was closed")
        }
    }

    /**
     * The show page is a cached payload, drawn straight away while it revalidates, and
     * Continue resumed from the position in it — wherever this device last saw the
     * episode, not where the device the viewer just left saved it.
     */
    @Test
    fun continueResumesFromTheServerNotTheCachedShow() {
        val cases = listOf(
            "library" to show.copy(seasons = listOf(Season(number = 1, episodes = listOf(
                Episode(id = "ep-1", number = 1, title = "One", filePath = "/e1.mkv"),
            )))),
            "debrid" to show,
        )
        for ((name, cachedShow) in cases) runUiTest {
            val session = "/api/v1/admin/playback/sessions" to { _: com.sun.net.httpserver.HttpExchange ->
                """{"session_id":"s1","direct_url":"/api/v1/admin/stream/s1/play","method":"direct"}"""
            }
            StubServer(routes + listOfNotNull(
                "/api/v1/admin/tv/episodes/ep-1/watch-state" to { _: com.sun.net.httpserver.HttpExchange ->
                    """{"watched":false,"position_seconds":2500}"""
                },
                session.takeIf { name == "library" },
            )).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
                val gps = GlobalPlayerState()
                setContent {
                    InlineTvPlayer(
                        show = cachedShow, api = api, formFactor = FormFactor.Phone,
                        player = FakePlayer().also { it.durationMs.value = 3_600_000 },
                        gps = gps, autoPlay = true, onBack = {},
                    )
                }
                waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == "ep-1" && gps.isFullscreen }
                assertEquals(2_500_000L, gps.timeline.toAbsoluteMs(gps.pendingSeekMs), "$name resumed from the cached show")
            }
        }
    }
}
