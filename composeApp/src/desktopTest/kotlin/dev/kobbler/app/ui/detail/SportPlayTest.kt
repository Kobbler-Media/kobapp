package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.tvshow.SHOW_PATH
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A sport is followed for what is newest, so a fresh one offers its latest event, not its first. */
@OptIn(ExperimentalTestApi::class)
class SportPlayTest {

    private fun json(category: String) = buildString {
        append("""{"id":"show-1","title":"MotoGP","year":2025,"content_category":"$category","seasons":[""")
        append(
            (1..2).joinToString(",") { s ->
                val eps = (1..3).joinToString(",") { e ->
                    """{"id":"e$s-$e","episode_identifier":"$e","episode_sort_order":$e,"title":"Round $s.$e","runtime":45,"is_in_library":true,"watched":false}"""
                }
                """{"id":"season-$s","season_identifier":"$s","season_sort_order":$s,"episodes":[$eps]}"""
            },
        )
        append("]}")
    }

    private fun played(category: String): Pair<Int, Int>? {
        var played: Pair<Int, Int>? = null
        runUiTest(width = 1280, height = 900) {
            StubServer(mapOf(SHOW_PATH to { _ -> json(category) })).use { stub ->
                val api = KobblerApi(HttpClient(), stub.url, "token")
                val show = runBlocking { api.getShow("show-1") }
                setContent {
                    KobappTheme(formFactor = FormFactor.Desktop) {
                        TvShowDetailScreen(show = show, formFactor = FormFactor.Desktop, onBack = {}, onPlayEpisode = { s, e, _ -> played = s to e }, api = api)
                    }
                }
                waitForIdle()
                onNodeWithText("Play").performClick()
                waitForIdle()
            }
        }
        return played
    }

    @Test
    fun `a sport starts on its newest event`() = assertEquals(1 to 2, played("sport"))

    @Test
    fun `a show still starts on the season's first unwatched episode`() = assertEquals(1 to 0, played("tv"))
}
