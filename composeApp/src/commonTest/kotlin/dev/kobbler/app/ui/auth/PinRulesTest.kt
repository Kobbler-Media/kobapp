package dev.kobbler.app.ui.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinRulesTest {
    @Test
    fun matchesServerBounds() {
        assertEquals(3, MIN_PIN_LENGTH)
        assertEquals(32, MAX_PIN_LENGTH)
    }

    @Test
    fun inputAllowsPartialsUpToTheMaximum() {
        val cases = listOf(
            "" to true,
            "1" to true,
            "1234" to true,
            "1".repeat(32) to true,
            "1".repeat(33) to false,
            "12 34" to false,
            "12\t34" to false,
            "12\n34" to false,
        )
        for ((value, expected) in cases) {
            assertEquals(expected, isPinInputAllowed(value), "isPinInputAllowed(${'"'}$value${'"'})")
        }
    }

    @Test
    fun completionRequiresTheServerMinimum() {
        assertFalse(isPinComplete(""))
        assertFalse(isPinComplete("12"))
        assertTrue(isPinComplete("123"))
        assertTrue(isPinComplete("1".repeat(32)))
        assertFalse(isPinComplete("1".repeat(33)))
    }

    // A PIN set from the web admin or the in-app dialog may contain letters and
    // symbols; entry that filtered to digits would lock those profiles out.
    @Test
    fun nonDigitPinsAreEnterable() {
        for (value in listOf("abc", "a1b2", "p@ssw0rd", "1234-5678", "ÜÜÜ")) {
            assertTrue(isPinInputAllowed(value), value)
            assertTrue(isPinComplete(value), value)
        }
    }
}
