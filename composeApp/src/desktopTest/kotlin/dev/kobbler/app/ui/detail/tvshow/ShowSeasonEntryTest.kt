package dev.kobbler.app.ui.detail.tvshow

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import dev.kobbler.app.ui.navigation.Destination
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ShowSeasonEntryTest {

    @Test
    fun `opening a show at a season lists that season, not the one being watched`() = detailPage(
        Destination.TvShowDetail("show-1", seasonId = "season-4"),
        mapOf(SHOW_PATH to { _ -> showJson() }),
    ) {
        waitForIdle()
        assertTrue(onAllNodesWithText("S4E1 title").fetchSemanticsNodes().isNotEmpty(), "season 4 listed")
        assertTrue(onAllNodesWithText("S3E1 title").fetchSemanticsNodes().isEmpty(), "season 3 not listed")
    }
}
