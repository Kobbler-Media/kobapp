package dev.kobbler.app.data

import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** The server wants a season at `/tv/seasons/{id}/want`; the show-scoped path is a 404. */
class WantSeasonRouteTest {
    @Test
    fun `wanting a season reaches the server's season route`() {
        StubServer(mapOf("/api/v1/admin/tv/seasons/se-1/want" to { _ -> "{}" })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            runBlocking { api.wantSeason("se-1") }
            assertTrue("/api/v1/admin/tv/seasons/se-1/want" in stub.requestBodies)
        }
    }
}
