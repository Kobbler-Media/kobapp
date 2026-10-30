package dev.kobbler.app.offline

import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineRulesTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 30 * day

    private fun item(id: String, watched: Boolean = false, completedDaysAgo: Int? = 1, keep: Boolean = false, state: OfflineState = OfflineState.Complete) =
        OfflineItem(OfflineKind.Movie, id, id, watched = watched, keep = keep, state = state, completedAtMs = completedDaysAgo?.let { now - it * day })

    private val items = listOf(
        item("fresh"),
        item("watched", watched = true),
        item("old", completedDaysAgo = 8),
        item("kept", watched = true, completedDaysAgo = 30, keep = true),
        item("downloading", watched = true, completedDaysAgo = null, state = OfflineState.Downloading),
    )

    @Test
    fun eachPolicyPicksItsOwnAndNeverAKeptOrUnfinishedDownload() {
        val cases = mapOf(
            CleanupPolicy.Keep to emptySet(),
            CleanupPolicy.AfterWatched to setOf("watched"),
            CleanupPolicy.AfterWeek to setOf("old"),
        )
        cases.forEach { (policy, expected) ->
            assertEquals(expected, dueForCleanup(items, policy, now, inUse = { false }).map { it.mediaId }.toSet(), policy.name)
        }
    }

    @Test
    fun aTitleBeingPlayedIsNotPulledFromUnderThePlayer() {
        assertEquals(emptyList(), dueForCleanup(items, CleanupPolicy.AfterWatched, now, inUse = { it == "movie-watched" }))
    }

    @Test
    fun onlyEpisodesWithAFileThatHaveNotBeenSeenBeforeAreNew() {
        val show = TvShow(
            id = "s", title = "Show", year = 2020, posterUrl = "",
            seasons = listOf(
                Season(1, listOf(Episode("e1", 1, "One", filePath = "/a"), Episode("e2", 2, "Two", filePath = "/b"))),
                Season(2, listOf(Episode("e3", 1, "Three", filePath = "/c"), Episode("e4", 2, "Four"))),
            ),
        )
        val subscription = ShowSubscription("s", "Show", knownEpisodeIds = setOf("e1", "e2"))
        assertEquals(listOf("e3"), newEpisodes(show, subscription).map { it.id })
    }
}
