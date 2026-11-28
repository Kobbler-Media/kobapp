package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.Movie
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A title played from Discover is added to the library by its resolve, and the page turns
 * into the library one with the movie already playing. That page searched for streams all
 * over again, a search kobload then logged and warmed as if someone were about to pick one.
 */
@OptIn(ExperimentalTestApi::class)
class PlayingMovieNoSearchTest {

    private val movie = Movie(id = "m1", title = "Movie", year = 2020, posterUrl = "", filePath = null)

    @Test
    fun theLibraryPageOfTheMoviePlayingDoesNotSearchForItAgain() = runUiTest {
        StubServer(mapOf("/api/v1/admin/stream/search" to { _: HttpExchange -> """{"options":[]}""" })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token").apply { canStream = true }
            val gps = GlobalPlayerState().apply { mediaId = movie.id }
            setContent {
                InlineMoviePlayer(movie = movie, api = api, formFactor = FormFactor.Phone, player = FakePlayer(), gps = gps, onBack = {})
            }
            waitForIdle()
            Thread.sleep(500)
            waitForIdle()
            assertFalse("/api/v1/admin/stream/search" in stub.requestBodies, "searched: ${stub.requestBodies.keys}")
        }
    }
}
