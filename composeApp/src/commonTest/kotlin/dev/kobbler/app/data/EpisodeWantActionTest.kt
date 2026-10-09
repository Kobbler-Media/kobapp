package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The TV half of [MovieWantActionTest] — both sit on the same `canBeWanted` rule. */
class EpisodeWantActionTest {

    private fun episode(filePath: String? = null, wantStatus: String? = null) =
        Episode(id = "e1", number = 9, title = "The Missing One", filePath = filePath, wantStatus = wantStatus)

    @Test
    fun anEpisodeWithNoFileCanBeWanted() {
        assertTrue(episode().canBeWanted(canWant = true))
    }

    @Test
    fun anEpisodeInTheLibraryIsNotOffered() {
        assertFalse(episode(filePath = "stream").canBeWanted(canWant = true))
    }

    @Test
    fun viewersAreNotOffered() {
        assertFalse(episode().canBeWanted(canWant = false))
    }

    @Test
    fun anAlreadyWantedEpisodeIsNotOfferedAgain() {
        assertFalse(episode(wantStatus = "wanted").canBeWanted(canWant = true))
        assertFalse(episode(wantStatus = "downloading").canBeWanted(canWant = true))
    }

    @Test
    fun noneIsNotAWant() {
        // The server sends "none" rather than omitting the field.
        assertTrue(episode(wantStatus = "none").canBeWanted(canWant = true))
        assertFalse(episode(wantStatus = "none").isWanted)
    }

    @Test
    fun wantStatusAndSeasonIdSurviveTheDtoMapping() {
        // wantEpisode/wantSeason need both, and neither used to reach the model at all.
        val dto = ShowDetailDto(
            id = "s1",
            title = "Show",
            seasons = listOf(
                SeasonDto(
                    id = "season-uuid",
                    seasonIdentifier = "1",
                    seasonSortOrder = 1,
                    episodes = listOf(
                        EpisodeDto(
                            id = "ep-uuid",
                            episodeIdentifier = "9",
                            episodeSortOrder = 9,
                            wantStatus = "wanted",
                        ),
                    ),
                ),
            ),
        )
        val season = dto.toTvShow("http://host").seasons.single()
        assertEquals("season-uuid", season.id)
        assertEquals("wanted", season.episodes.single().wantStatus)
        assertTrue(season.episodes.single().isWanted)
    }

    @Test
    fun autoWantModeSurvivesTheDtoMapping() {
        // The picker shows which mode is on; before this the field was dropped and every
        // show looked unmonitored.
        val dto = ShowDetailDto(id = "s1", title = "Show", isMonitored = true, autoWantMode = "all")
        assertEquals("all", dto.toTvShow("http://host").autoWantMode)
    }

    @Test
    fun autoWantModeDefaultsToNoneWhenTheServerOmitsIt() {
        assertEquals("none", ShowDetailDto(id = "s1", title = "Show").toTvShow("http://host").autoWantMode)
    }
}
