package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import io.ktor.client.HttpClient
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ShowGenresTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")
    private val genres = listOf("Drama", "Crime")

    private val pages: Map<String, @Composable (FormFactor) -> Unit> = mapOf(
        "library show" to { ff ->
            TvShowDetailScreen(
                show = TvShow(id = "s1", title = "Show", year = 2020, posterUrl = "", genres = genres),
                formFactor = ff, onBack = {}, onPlayEpisode = { _, _, _ -> },
            )
        },
        "discover show" to { ff ->
            DiscoverDetailScreen(
                detail = DiscoverDetail(
                    mediaType = "tv", provider = "tmdb", title = "Found", year = 2026,
                    description = null, posterUrl = "", backdropUrl = null, genres = genres,
                    rating = null, cast = emptyList(), network = null, runtime = null,
                    authors = emptyList(), libraryId = null, wantStatus = null, isInLibrary = false,
                    identifiers = emptyMap(), providerId = "43",
                ),
                api = api, formFactor = ff, player = null, gps = GlobalPlayerState(),
                onBack = {}, onViewInLibrary = { _, _ -> },
            )
        },
    )

    @Test
    fun `show pages list the genres`() {
        for ((_, page) in pages) for ((ff, w, h) in listOf(Triple(FormFactor.Phone, 412, 900), Triple(FormFactor.Desktop, 1400, 900))) {
            runUiTest(width = w, height = h) {
                setContent { KobappTheme(formFactor = ff) { page(ff) } }
                waitForIdle()
                onAllNodesWithText("Drama, Crime", substring = true).assertCountEquals(1)
            }
        }
    }
}
