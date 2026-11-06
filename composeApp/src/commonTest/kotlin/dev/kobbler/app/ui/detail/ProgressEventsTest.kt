package dev.kobbler.app.ui.detail

import dev.kobbler.app.data.LibraryEventFrame
import dev.kobbler.app.data.PlaybackClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressEventsTest {

    private fun progress(scope: String, clientId: String = "app-other", watched: Boolean = false) = LibraryEventFrame(
        "watch_progress",
        buildJsonObject {
            put("scope_id", JsonPrimitive(scope))
            put("client_id", JsonPrimitive(clientId))
            put("watched", JsonPrimitive(watched))
        },
    )

    private data class Case(val name: String, val frame: LibraryEventFrame, val scope: String?, val refreshes: Boolean)

    @Test
    fun `which progress events refetch a detail page`() {
        val cases = listOf(
            Case("another device saving this title", progress("show-1"), "show-1", true),
            Case("another device saving a different title", progress("show-2"), "show-1", false),
            Case("this device's own periodic save, already mirrored", progress("show-1", PlaybackClient.id), "show-1", false),
            Case("this device finishing the title", progress("show-1", PlaybackClient.id, watched = true), "show-1", true),
            Case("a page that does not know its scope yet", progress("show-9"), null, true),
            Case("an unrelated event", LibraryEventFrame("job_progress", buildJsonObject {}), "show-1", false),
        )
        for (case in cases) assertEquals(case.refreshes, refreshesForProgress(case.frame, case.scope), case.name)
    }
}
