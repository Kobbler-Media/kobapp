package dev.kobbler.app.ui.player

import dev.kobbler.app.data.KobblerApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val EPISODE_POSITION = "/api/v1/admin/tv/episodes/ep-1/watch-state/position"

/**
 * The server decides when a save finishes a title — at its credits, or 90% in. Skipping to
 * the next episode leaves only a mid-play save behind, and the episode list read the
 * mirror, so the episode just finished still showed as part-watched.
 */
class ReportedWatchedTest {

    private fun saving(response: String, test: (KobblerApi, GlobalPlayerState) -> Unit) =
        StubServer(mapOf(EPISODE_POSITION to { _ -> response })).use { stub ->
            test(KobblerApi(HttpClient(), stub.url, "token"), GlobalPlayerState())
        }

    @Test
    fun `a save the server calls finished is mirrored as finished`() {
        for (mirror in listOf(true, false)) {
            saving("""{"id":"ws-1","watched":true}""") { api, state ->
                runBlocking { state.reportProgress(api, "episode", "ep-1", 2_948_000, mirror = mirror) }
                assertEquals(WatchedPoint(2948, finished = true), state.reportedPositions["ep-1"], "mirror=$mirror")
            }
        }
    }

    @Test
    fun `a mid-play save that finishes nothing leaves the mirror alone`() {
        for (response in listOf("""{"id":"ws-1","watched":false}""", """{"id":"ws-1","watched":null}""", "{}")) {
            saving(response) { api, state ->
                runBlocking { state.reportProgress(api, "episode", "ep-1", 600_000, mirror = false) }
                assertNull(state.reportedPositions["ep-1"], response)
            }
        }
    }

    @Test
    fun `seeking back out of the ending un-finishes the mirror`() = saving("""{"id":"ws-1","watched":false}""") { api, state ->
        state.reportedPositions["ep-1"] = WatchedPoint(2948, finished = true)
        runBlocking { state.reportProgress(api, "episode", "ep-1", 600_000, mirror = false) }
        assertEquals(WatchedPoint(600), state.reportedPositions["ep-1"])
    }
}
