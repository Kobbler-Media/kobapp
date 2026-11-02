package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.ForYouItem
import dev.kobbler.app.data.ForYouSeed
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.ui.dashboard.home.forYouRefusals
import dev.kobbler.app.ui.dashboard.home.shortBecauseLabel
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A pick can be refused outright, or by dropping the watch it was drawn from. */
class ForYouRefusalTest {

    private val entry = ForYouItem(
        item = DiscoverItem(
            provider = "tmdb", providerId = "1399", mediaType = "tv", title = "Bluey", year = 2018, description = null,
            posterUrl = "", backdropUrl = null, genres = emptyList(), rating = null, ratingCount = null, authors = emptyList(),
            libraryId = null, wantStatus = null, isInLibrary = false,
        ),
        because = listOf("Peppa Pig"),
        seeds = listOf(ForYouSeed("tv", 12225, "Peppa Pig")),
    )

    @Test
    fun `both refusals reach the server`() {
        StubServer(
            mapOf(
                "/api/v1/admin/discover/for-you/dismiss" to { _ -> "" },
                "/api/v1/admin/watch-history/exclude" to { _ -> "" },
            ),
        ).use { stub ->
            runBlocking {
                val refusals = forYouRefusals(entry, KobblerApi(HttpClient(), stub.url, "token"), this)
                assertEquals(listOf("Not interested", "Not based on Peppa Pig"), refusals.map { it.label })
                refusals.forEach { it.onClick() }
            }
            assertEquals("""{"media_type":"tv","tmdb_id":1399}""", stub.requestBodies["/api/v1/admin/discover/for-you/dismiss"])
            assertEquals(
                """{"items":[{"media_type":"tv","tmdb_id":12225}],"excluded":true}""",
                stub.requestBodies["/api/v1/admin/watch-history/exclude"],
            )
        }
    }

    @Test
    fun `the phone label fits a card`() {
        assertEquals("Like Peppa Pig", shortBecauseLabel(entry.because))
        assertTrue(shortBecauseLabel(emptyList()).isEmpty())
    }
}
