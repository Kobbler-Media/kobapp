package dev.kobbler.app.ui.player

import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.TvShow
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RESOLVE = "/api/v1/admin/stream/resolve"

private val show = TvShow(
    id = "show-1", title = "Show", year = 2020, posterUrl = "",
    seasons = listOf(Season(number = 1, episodes = listOf(Episode(id = "ep-1", number = 1, title = "One")))),
)

private val option = StreamOption(
    id = "opt-1", releaseTitle = "Show.S01E01", size = 1, indexer = "idx", infoHash = "h",
    quality = StreamQuality(null, null, null, null), qualityJson = null, totalScore = 1.0,
    debridProvider = "realdebrid", debridProviderId = "p1", cached = true,
    sourceType = "torrent", fileId = 1, publishDate = null,
)

/** A resolve response in the shape kobapp always gets: direct play, no server-side seek. */
private fun directPlaySession(startTime: String? = null) = """
{"session_id":"sess-1","method":"DirectPlay","direct_url":"/api/v1/admin/stream/sess-1/play",
 "duration_seconds":3600,"subtitles":[],"audio_tracks":[]${startTime?.let { ",\"start_time\":$it" } ?: ""}}
"""

class DebridResumeTest {

    private fun launch(
        resolveBody: String,
        resumeSec: Int,
        player: FakePlayer = FakePlayer(),
    ): Pair<GlobalPlayerState, FakePlayer> {
        player.durationMs.value = 3_600_000
        val gps = GlobalPlayerState()
        StubServer(mapOf(RESOLVE to { resolveBody })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            runBlocking {
                tryPlayTvWithFallback(
                    api = api, player = player, gps = gps, show = show,
                    episodeId = "ep-1", seasonIdx = 0, episodeIdx = 0,
                    search = { found(listOf(option)) }, option = option,
                    resumeSec = resumeSec,
                    onCaching = { _, _ -> },
                )
            }
        }
        return gps to player
    }

    @Test
    fun aDebridEpisodeResumesWhereItWasLeft() {
        val (gps, player) = launch(directPlaySession(), resumeSec = 1200)
        assertEquals("ep-1", gps.mediaId)
        assertEquals(1_200_000L, gps.pendingSeekMs)
        assertEquals(1200, player.loadedSource?.startTimeSec, "the stream did not open at the resume point")
    }

    /**
     * What a debrid stream does on a TV: VLC drops the open-time start, and the player
     * shows the asked-for position until VLC's own clock arrives seconds after PLAYING.
     */
    @Test
    fun aDebridEpisodeWhoseStartWasIgnoredIsPutBack() {
        val (_, player) = launch(directPlaySession(), resumeSec = 1200, FakePlayer().apply { startTimeIgnored = true })
        player.awaitSettled()
        assertEquals(listOf("seek:1200000"), player.seeks(), "${player.calls}")
        assertEquals(1_200_000L, player.currentMs.value)
    }

    @Test
    fun aRebasedTranscodeIsNotSeekedTwice() {
        // The server answered with a playlist that already starts at 20 minutes.
        val (gps, player) = launch(directPlaySession(startTime = "1200"), resumeSec = 1200)
        assertEquals(0L, gps.pendingSeekMs)
        assertNull(player.loadedSource?.startTimeSec, "asked for a resume the playlist had already applied")
        assertTrue(player.calls.none { it.startsWith("seek:") }, "double seek: ${player.calls}")
    }

    @Test
    fun anUnwatchedEpisodeStartsAtTheBeginning() {
        val (gps, player) = launch(directPlaySession(), resumeSec = 0)
        assertEquals(0L, gps.pendingSeekMs)
        assertNull(player.loadedSource?.startTimeSec)
        assertTrue(player.calls.none { it.startsWith("seek:") }, "unexpected seek: ${player.calls}")
    }
}
