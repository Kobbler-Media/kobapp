package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.InMemoryKeyValueStore
import dev.kobbler.app.data.Profile
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A cast only opens in kobapp when kobapp can get it on screen unattended; otherwise the
 * TV runs the web receiver, as it would without kobapp installed.
 */
class CastLaunchTest {

    private val open = Profile(id = "p1", token = "t", apiUrl = "http://s")
    private val pinned = open.copy(id = "p2", hasPin = true)

    private fun store(current: String?, picker: Boolean = false) = InMemoryKeyValueStore().apply {
        putString("kobbler_profiles_v1", Json.encodeToString(listOf(open, pinned)))
        current?.let { putString("kobbler_current_profile_id", it) }
        putString("kobbler_show_picker_on_launch", picker.toString())
    }

    @Test
    fun `kobapp takes the cast only when it can reach its player unattended`() {
        val cases = listOf(
            Triple("signed out", InMemoryKeyValueStore(), false) to false,
            Triple("no current profile", store(current = null), false) to false,
            Triple("PIN profile", store(current = "p2"), false) to false,
            Triple("picker on launch", store(current = "p1", picker = true), false) to false,
            Triple("open profile", store(current = "p1"), false) to true,
            Triple("already up behind a picker", store(current = "p1", picker = true), true) to true,
        )
        for ((case, expected) in cases) {
            val (name, store, serving) = case
            assertEquals(expected, castConnectCanServe(store, serving), name)
        }
    }
}
