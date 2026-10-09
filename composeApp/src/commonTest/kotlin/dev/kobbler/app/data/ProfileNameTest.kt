package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The name is the one thing every profile selector, avatar and rail foot shows, and it
 * comes from three places depending on what the server knows.
 */
class ProfileNameTest {

    private fun profile(name: String? = null, email: String? = null) =
        Profile(id = "p1", token = "t", apiUrl = "http://x", name = name, email = email)

    @Test
    fun prefersTheServerName() {
        assertEquals("Ron Weasley", profile(name = "Ron Weasley", email = "kobbler@example.com").displayName)
    }

    @Test
    fun fallsBackToTheEmailLocalPart() {
        assertEquals("kobbler", profile(email = "kobbler@example.com").displayName)
    }

    @Test
    fun blankNameIsNotAName() {
        assertEquals("kobbler", profile(name = "   ", email = "kobbler@example.com").displayName)
    }

    @Test
    fun trimsTheStoredName() {
        assertEquals("Ron", profile(name = "  Ron  ", email = "kobbler@example.com").displayName)
    }

    @Test
    fun lastResortWhenThereIsNoEmail() {
        assertEquals("Profile", profile().displayName)
    }
}
