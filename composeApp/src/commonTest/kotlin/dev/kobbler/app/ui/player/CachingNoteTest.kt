package dev.kobbler.app.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class CachingNoteTest {

    @Test
    fun notes() = listOf(
        Triple(0f, 0L, "Real-Debrid") to "Asking Real-Debrid to fetch it…",
        Triple(0f, CACHING_QUEUED_AFTER_MS, "Real-Debrid") to "Queued on Real-Debrid — it hasn't started downloading yet",
        Triple(0.37f, CACHING_QUEUED_AFTER_MS * 3, "Real-Debrid") to "Downloading on Real-Debrid",
        Triple(0f, 0L, null) to "Asking your debrid service to fetch it…",
        Triple(0f, 0L, " ") to "Asking your debrid service to fetch it…",
    ).forEach { (input, note) ->
        assertEquals(note, cachingNote(input.first, input.second, input.third), "$input")
    }
}
