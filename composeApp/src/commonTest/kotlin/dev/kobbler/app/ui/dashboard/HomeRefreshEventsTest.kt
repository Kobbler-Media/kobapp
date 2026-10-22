package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.data.LibraryEventFrame
import dev.kobbler.app.data.PlaybackClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeRefreshEventsTest {

    private fun progress(clientId: String, watched: Boolean = false) = LibraryEventFrame(
        "watch_progress",
        buildJsonObject {
            put("client_id", JsonPrimitive(clientId))
            put("watched", JsonPrimitive(watched))
        },
    )

    @Test
    fun `which events refetch the home page`() {
        val cases = listOf(
            "this device's own periodic save" to (progress(PlaybackClient.id) to false),
            "this device finishing a title" to (progress(PlaybackClient.id, watched = true) to true),
            "another device's save" to (progress("app-other") to true),
            "a library change" to (LibraryEventFrame("movie_updated", buildJsonObject {}) to true),
            "an unrelated event" to (LibraryEventFrame("job_progress", buildJsonObject {}) to false),
        )
        for ((name, case) in cases) {
            val (frame, expected) = case
            assertEquals(expected, refreshesHome(frame), name)
        }
    }
}
