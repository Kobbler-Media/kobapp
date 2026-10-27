package dev.kobbler.app.ui.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DiscoverRatingScaleTest {

    private fun detail(mediaType: String, rating: Float) = DiscoverDetail(
        mediaType = mediaType, provider = "tmdb", title = "Title", year = 2020,
        description = null, posterUrl = "", backdropUrl = null, genres = emptyList(),
        rating = rating, cast = emptyList(), network = null, runtime = null, authors = emptyList(),
        libraryId = null, wantStatus = null, isInLibrary = false, identifiers = emptyMap(),
        providerId = "1",
    )

    @Test
    fun `details panel shows each title on its provider's scale`() =
        listOf(
            detail("movie", 7.3f) to "7.3 / 10",
            detail("tv", 8f) to "8.0 / 10",
            detail("book", 4.2f) to "4.2 / 5",
        ).forEach { (detail, expected) ->
            runUiTest {
                setContent { KobappTheme { DiscoverDetailsPanel(detail, FormFactor.Desktop) } }
                onNodeWithText(expected).assertExists()
            }
        }
}
