package dev.kobbler.app.data

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseDatesParsingTest {

    private val datesJson =
        """{"region":"AU","premiere":"2024-01-02","theatrical_limited":null,"theatrical":"2024-02-01",
           "digital":"2024-04-10","physical":"2024-06-01","tv":null}"""

    private val parsed = ReleaseDates(
        region = "AU",
        premiere = LocalDate(2024, 1, 2),
        theatrical = LocalDate(2024, 2, 1),
        digital = LocalDate(2024, 4, 10),
        physical = LocalDate(2024, 6, 1),
    )

    private fun fetch(field: String?): Pair<ReleaseDates?, ReleaseDates?> {
        val dates = field?.let { ""","release_dates":$it""" }.orEmpty()
        val routes = mapOf<String, (HttpExchange) -> String>(
            "/api/v1/admin/movies/m1" to { _ -> """{"id":"m1","title":"Film","release_date":"2024-02-01"$dates}""" },
            "/api/v1/admin/discover/movie/tmdb/1" to { _ ->
                """{"media_type":"movie","provider":"tmdb","details":{"title":"Film","release_date":"2024-02-01"$dates}}"""
            },
        )
        return StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            runBlocking {
                api.getMovie("m1").releaseDates to api.getDiscoverDetail("movie", "tmdb", "1").releaseDates
            }
        }
    }

    @Test
    fun `library and discover movies carry the release types the server sends`() {
        listOf(
            "present" to (datesJson to parsed),
            "absent" to (null to null),
            "null" to ("null" to null),
            "partial" to ("""{"region":"US","digital":"2025-03-04"}""" to ReleaseDates(region = "US", digital = LocalDate(2025, 3, 4))),
            "unparseable dates" to ("""{"region":"US","theatrical":"soon"}""" to ReleaseDates(region = "US")),
        ).forEach { (name, case) ->
            val (field, expected) = case
            val (movie, discover) = fetch(field)
            assertEquals(expected, movie, "library movie, $name")
            assertEquals(expected, discover, "discover movie, $name")
        }
    }
}
