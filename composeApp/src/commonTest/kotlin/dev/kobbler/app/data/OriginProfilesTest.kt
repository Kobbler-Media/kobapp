package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class OriginProfilesTest {

    private fun store(backing: MutableMap<String, String> = mutableMapOf()) =
        OriginProfiles(read = { backing[it] }, write = { k, v -> backing[k] = v })

    @Test
    fun `what one launch learns about a server the next one starts from`() {
        val backing = mutableMapOf<String, String>()
        store(backing).update("kobbler.example.com") { it.copy(roundTripMs = 4).afterStall() }

        assertEquals(
            OriginProfile(roundTripMs = 4, stallAllowanceMs = StallStepMs),
            store(backing).get("kobbler.example.com"),
        )
        assertEquals(OriginProfile(), store(backing).get("cdn.example.com"), "hosts share nothing")
    }

    @Test
    fun `an unreadable profile is forgotten rather than fatal`() {
        val backing = mutableMapOf<String, String>()
        val profiles = store(backing)
        profiles.update("kobbler.example.com") { it.copy(roundTripMs = 4) }
        backing.keys.forEach { backing[it] = "{not json" }

        assertEquals(OriginProfile(), profiles.get("kobbler.example.com"))
    }
}
