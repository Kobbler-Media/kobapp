package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import dev.kobbler.app.UiTimeoutMs
import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.ContinueEpisode
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Continue watching a show from the Home hero has to start where it was left.
 *
 * The hero hands the detail screen an autoplay intent and nothing else, so the position
 * comes back out of the show payload on the other side. Everything in that chain is a
 * different shape of the same number — a watch state in seconds, a rebase the server may
 * or may not have applied, an open-time option in seconds — and the failure mode when one
 * link drops it is not an error, it is the episode playing from the top.
 */
@OptIn(ExperimentalTestApi::class)
class HeroResumeTest {

    private val show = TvShow(
        id = "show-1", title = "Show", year = 2020, posterUrl = "",
        seasons = listOf(
            Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One"))),
        ),
        continueEpisode = ContinueEpisode(
            seasonIdx = 0, episodeIdx = 0, episodeId = "ep-1", positionSeconds = 1_380,
        ),
    )

    /** A local episode: one session, served whole, and the client owns the position. */
    private val directPlayRoutes = mapOf(
        "/api/v1/admin/playback/sessions" to { _: HttpExchange ->
            """{"session_id":"sess-1","method":"direct_play",
                "direct_url":"/api/v1/admin/playback/sessions/sess-1/stream",
                "duration_seconds":3600,"subtitles":[],"audio_tracks":[]}"""
        },
    )

    /** A session that reports the resume it was asked for, which direct play did not apply. */
    private val echoedResumeRoutes = mapOf(
        "/api/v1/admin/playback/sessions" to { _: HttpExchange ->
            """{"session_id":"sess-1","method":"direct_play",
                "direct_url":"/api/v1/admin/playback/sessions/sess-1/stream",
                "duration_seconds":3600,"subtitles":[],"audio_tracks":[],
                "start_time":1380}"""
        },
    )

    /**
     * The server echoes back the resume it was asked for, whatever it did with it — and on
     * a direct-played file it did nothing with it, because the whole file is served from
     * its start.
     *
     * Taken for a rebase, that echo moves the player's zero 23 minutes into the episode:
     * the clock reads right while the picture sits at the top, the seek that would have
     * moved it is skipped as already applied, and every later seek lands short by the same
     * amount — a drag back to anywhere before the resume point restarting the episode.
     */
    @Test
    fun `an echoed resume on a direct-played file is not a rebase`() = runUiTest {
        StubServer(echoedResumeRoutes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 3_600_000 }
            val gps = GlobalPlayerState()

            setContent {
                InlineTvPlayer(
                    show = show, api = api, formFactor = FormFactor.Phone,
                    player = player, gps = gps, autoPlay = true, onBack = {},
                )
            }

            waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == "ep-1" }

            assertEquals(
                PlayerTimeline.Direct, gps.timeline,
                "the player's zero was moved to a resume point the stream does not have",
            )
            assertEquals(
                1_380, player.loadedSource?.startTimeSec,
                "the client left the seek to a rebase that never happened: ${player.loadedSource}",
            )
        }
    }

    @Test
    fun `the hero resumes a local episode where it was left`() = runUiTest {
        StubServer(directPlayRoutes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 3_600_000 }
            val gps = GlobalPlayerState()

            setContent {
                InlineTvPlayer(
                    show = show, api = api, formFactor = FormFactor.Phone,
                    player = player, gps = gps, autoPlay = true, onBack = {},
                )
            }

            waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == "ep-1" }

            assertEquals(
                1_380_000L, gps.pendingSeekMs,
                "the resume position never reached the player",
            )
            assertEquals(
                1_380, player.loadedSource?.startTimeSec,
                "the stream was opened at the top of the episode: ${player.loadedSource}",
            )
        }
    }

    /**
     * A transcoded session the server rebased to the resume point already begins there, so
     * asking the client to seek as well would land it 23 minutes further in again.
     */
    @Test
    fun `a rebased session is not seeked twice`() {
        assertEquals(0, resumeSeekSeconds(1_380, serverStartSeconds = 1_380))
        assertEquals(1_380, resumeSeekSeconds(1_380, serverStartSeconds = null))
    }

    /**
     * The resume has to be part of creating the session, not something done to it after.
     *
     * A transcode is produced from wherever it was told to start. Left at zero, the client
     * is then asking to seek 23 minutes into segments the server has not reached and will
     * not reach for the length of the episode — so the only thing it can do is play from
     * the top. Direct play forgives this, which is why it survived: the file is served
     * whole and the seek lands.
     */
    @Test
    fun `the resume position is part of creating the session`() = runUiTest {
        StubServer(directPlayRoutes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also { it.durationMs.value = 3_600_000 }
            val gps = GlobalPlayerState()

            setContent {
                InlineTvPlayer(
                    show = show, api = api, formFactor = FormFactor.Phone,
                    player = player, gps = gps, autoPlay = true, onBack = {},
                )
            }

            waitUntil(timeoutMillis = UiTimeoutMs) { gps.mediaId == "ep-1" }

            val body = stub.requestBodies["/api/v1/admin/playback/sessions"]
            assertTrue(
                body?.contains("\"start_time\":1380") == true,
                "the session was created with no resume point, so a transcode of it would " +
                    "begin at zero: $body",
            )
        }
    }

    /** Below the resume floor there is nothing to rebase to. */
    @Test
    fun `a session at the start asks for no rebase`() = runTest {
        StubServer(directPlayRoutes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            startPlayback(api, "episode", "ep-1", savedFallback = 2)
            val body = stub.requestBodies["/api/v1/admin/playback/sessions"]
            assertTrue(body?.contains("start_time") != true, "asked to rebase to a mis-tap: $body")
        }
    }
}
