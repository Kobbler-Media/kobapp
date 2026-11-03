package dev.kobbler.app.watchnext

import dev.kobbler.app.data.InMemoryKeyValueStore
import dev.kobbler.app.data.Profile
import dev.kobbler.app.ui.player.StubServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The home screen is seen by everyone in the room; a PIN profile's titles do not belong there. */
class PinProfileWatchNextTest {

    @Test
    fun `a PIN-protected profile publishes nothing and never asks the server`() = runBlocking {
        StubServer(mapOf("/api/v1/admin/discover" to { _ -> "{}" })).use { stub ->
            val store = InMemoryKeyValueStore().apply {
                putString("kobbler_profiles_v1", Json.encodeToString(listOf(Profile(id = "p1", token = "t", apiUrl = stub.url, hasPin = true))))
                putString("kobbler_current_profile_id", "p1")
            }

            assertEquals(emptyList(), watchNextForStoredProfile(store))
            assertTrue(stub.requestBodies.isEmpty(), "asked the server: ${stub.requestBodies.keys}")
        }
    }
}
