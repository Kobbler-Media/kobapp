package dev.kobbler.app.watchnext

import dev.kobbler.app.data.InProgressItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchNextMappingTest {
    private val api = "https://kob.example"

    private fun item(
        mediaType: String = "episode",
        id: String = "e1",
        title: String = "South Park",
        subtitle: String? = "S01E02 - Weight Gain 4000",
        position: Int? = 600,
        duration: Double? = 1320.0,
        percent: Double? = position?.let { p -> duration?.let { p / it * 100 } },
        updatedAt: String? = "2026-11-02T02:48:38.814857Z",
        poster: String? = "/api/v1/admin/tv/shows/s1/poster",
        backdrop: String? = "/api/v1/admin/tv/shows/s1/backdrop",
        link: String = if (mediaType == "movie") "/movies/$id" else "/tv/episodes/$id",
    ) = InProgressItem(
        mediaType = mediaType, mediaId = id, title = title, subtitle = subtitle, imageUrl = poster, link = link,
        progressPercent = percent, positionSeconds = position, durationSeconds = duration, updatedAt = updatedAt,
        backdropUrl = backdrop,
    )

    private fun map(vararg items: InProgressItem) = watchNextPrograms(items.toList(), profileId = "p1", apiUrl = api)

    @Test
    fun partWatchedEpisodeContinuesWithSeasonEpisodeAndResumePoint() {
        val spec = map(item()).single()
        assertEquals("p1/episode/e1", spec.internalId)
        assertEquals(WatchNextKind.Episode, spec.kind)
        assertEquals(WatchNextReason.Continue, spec.reason)
        assertEquals("South Park", spec.title)
        assertEquals("Weight Gain 4000", spec.episodeTitle)
        assertEquals(1, spec.season)
        assertEquals(2, spec.episode)
        assertEquals(600_000L, spec.positionMs)
        assertEquals(1_320_000L, spec.durationMs)
        assertEquals(1793587718814L, spec.lastEngagementMs)
        assertEquals(WatchNextLaunch("p1", "/tv/episodes/e1", "South Park", "S01E02 - Weight Gain 4000"), spec.launch)
    }

    @Test
    fun landscapeBackdropIsPreferredAndRelativeUrlsResolveAgainstTheServer() {
        val spec = map(item()).single()
        assertEquals("$api/api/v1/admin/tv/shows/s1/backdrop", spec.artUrl)
        assertTrue(spec.artIsLandscape)

        val posterOnly = map(item(backdrop = null)).single()
        assertEquals("$api/api/v1/admin/tv/shows/s1/poster", posterOnly.artUrl)
        assertEquals(false, posterOnly.artIsLandscape)

        val absolute = map(item(backdrop = "https://cdn.example/b.jpg")).single()
        assertEquals("https://cdn.example/b.jpg", absolute.artUrl)
    }

    @Test
    fun unstartedEpisodeIsTheNextOneAndCarriesNoResumePoint() {
        val spec = map(item(position = null, percent = null)).single()
        assertEquals(WatchNextReason.Next, spec.reason)
        assertNull(spec.positionMs)
    }

    @Test
    fun movieWithAPositionContinuesAndOneWithoutIsAWatchlistPick() {
        val (resume, saved) = map(
            item(mediaType = "movie", id = "m1", title = "Heat", subtitle = "1995", position = 3600, duration = 10200.0),
            item(mediaType = "movie", id = "m2", title = "Ronin", subtitle = "1998", position = null, duration = null, percent = null),
        )
        assertEquals(WatchNextKind.Movie, resume.kind)
        assertEquals(WatchNextReason.Continue, resume.reason)
        assertEquals("1995", resume.description)
        assertNull(resume.season)
        assertEquals(WatchNextReason.Watchlist, saved.reason)
        assertEquals("/movies/m2", saved.launch.link)
    }

    @Test
    fun sportsEventIsAnEpisodeWithoutNumbers() {
        val spec = map(item(mediaType = "event", id = "ev1", title = "F1", subtitle = "R12 Race · Great Britain", link = "/sports/events/ev1")).single()
        assertEquals(WatchNextKind.Episode, spec.kind)
        assertNull(spec.season)
        assertNull(spec.episode)
        assertEquals("R12 Race · Great Britain", spec.episodeTitle)
    }

    @Test
    fun finishedReadingAndUnknownItemsAreLeftOut() {
        val specs = map(
            item(id = "done", position = 2556, duration = 2556.0),
            item(id = "nearly", position = 1366, duration = 1366.3),
            item(mediaType = "book", id = "b1", link = "/library/books/b1"),
            item(mediaType = "audiobook", id = "a1", link = "/library/books/a1"),
            item(id = "kept"),
        )
        assertEquals(listOf("p1/episode/kept"), specs.map { it.internalId })
    }

    @Test
    fun keepsTheMostRecentTenNewestFirst() {
        val items = (1..12).map { n -> item(id = "e$n", updatedAt = "2026-10-${n.toString().padStart(2, '0')}T00:00:00Z") }
        val specs = map(*items.toTypedArray())
        assertEquals((12 downTo 3).map { "p1/episode/e$it" }, specs.map { it.internalId })
    }

    @Test
    fun aChangeInProgressChangesTheFingerprintAndNothingElseDoesNot() {
        val a = map(item()).single()
        assertEquals(a.fingerprint, map(item()).single().fingerprint)
        assertTrue(a.fingerprint != map(item(position = 700)).single().fingerprint)
    }
}
