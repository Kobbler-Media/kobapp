package dev.kobbler.app.data

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShowDetailMappingTest {

    private fun ep(id: String, order: Int, airDate: String? = null) =
        EpisodeDto(id = id, episodeIdentifier = "$order", episodeSortOrder = order, airDate = airDate, isInLibrary = true)

    private val specials = SeasonDto(
        id = "s0", seasonIdentifier = "0", seasonSortOrder = 0, isSpecial = true,
        episodes = listOf(ep("sp1", 1)),
    )
    private val seasonOne = SeasonDto(
        id = "s1", seasonIdentifier = "1", seasonSortOrder = 1,
        episodes = listOf(ep("e1", 1), ep("e2", 2)),
    )

    @Test
    fun continueEpisodeIndexesTheSeasonsTheClientKeeps() {
        // The server counts its specials season; the client drops it. Its indices name
        // an episode the page does not have, so Resume found nothing to play.
        val dto = ShowDetailDto(
            id = "show", title = "Show",
            seasons = listOf(specials, seasonOne),
            continueEpisode = ContinueEpisodeDto(seasonIdx = 1, episodeIdx = 1, episodeId = "e2", positionSeconds = 30),
        )
        val cont = dto.toTvShow("http://host").continueEpisode!!
        assertEquals(0, cont.seasonIdx)
        assertEquals(1, cont.episodeIdx)
        assertEquals(30, cont.positionSeconds)
    }

    @Test
    fun continueEpisodeTheClientDoesNotListIsDropped() {
        val dto = ShowDetailDto(
            id = "show", title = "Show",
            seasons = listOf(specials, seasonOne),
            continueEpisode = ContinueEpisodeDto(seasonIdx = 0, episodeIdx = 0, episodeId = "sp1"),
        )
        assertNull(dto.toTvShow("http://host").continueEpisode)
    }

    @Test
    fun airDateReachesTheEpisode() {
        val dto = ShowDetailDto(
            id = "show", title = "Show",
            seasons = listOf(seasonOne.copy(episodes = listOf(ep("e1", 1, "2024-03-03"), ep("e2", 2)))),
        )
        val eps = dto.toTvShow("http://host").seasons.single().episodes
        assertEquals(LocalDate(2024, 3, 3), eps[0].airDate)
        assertNull(eps[1].airDate)
    }
}
