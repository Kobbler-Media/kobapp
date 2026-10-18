package dev.kobbler.app.ui.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.kobbler.app.data.Profile
import dev.kobbler.app.ui.components.BackdropArtTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The profile picker stands on the library's newest arrivals, cycling, with a note of
 * which title is showing. It asks as a Kids profile when the household has one, since
 * everyone sees this screen.
 */
@OptIn(ExperimentalTestApi::class)
class PickerBackdropTest {

    @Test
    fun `the newest arrivals cycle behind the profiles`() = picker({ url ->
        listOf(Profile(id = "adult", token = "adult-token", apiUrl = url))
    }) { _, _ ->
        waitUntil(timeoutMillis = 10_000) { shows("Recent One") }
        assertTrue(shows("RECENTLY ADDED"))
        repeat(60) {
            if (shows("Recent Two")) return@picker
            mainClock.advanceTimeBy(500)
            waitForIdle()
        }
        assertTrue(shows("Recent Two"), "the picture never moved on")
    }

    @Test
    fun `the next arrival fades in over the last even when it is already cached`() = picker({ url ->
        listOf(Profile(id = "adult", token = "adult-token", apiUrl = url))
    }) { _, _ ->
        waitUntil(timeoutMillis = 10_000) { shows("Recent One") }
        mainClock.autoAdvance = false
        val layers = { onAllNodesWithTag(BackdropArtTag).fetchSemanticsNodes().size }
        var waited = 0
        while (!shows("Recent Two") && waited < 12_000) {
            mainClock.advanceTimeBy(100)
            waited += 100
        }
        assertTrue(shows("Recent Two"), "the picture never moved on")
        mainClock.advanceTimeBy(100)
        assertEquals(2, layers(), "both pictures are on screen mid-fade")
        mainClock.advanceTimeBy(2_000)
        assertEquals(1, layers(), "the old picture is dropped once the fade is done")
    }

    @Test
    fun `a household with a kids profile shows what the kids profile may see`() = picker({ url ->
        listOf(
            Profile(id = "adult", token = "adult-token", apiUrl = url),
            Profile(id = "kid", token = "kid-token", apiUrl = url, isKids = true),
        )
    }) { tokens, _ ->
        waitUntil(timeoutMillis = 10_000) { shows("Recent One") }
        assertTrue(tokens.isNotEmpty())
        assertEquals(setOf("Bearer kid-token"), tokens.toSet())
    }

    @Test
    fun `the first profile keeps focus once the arrivals arrive`() = picker({ url ->
        listOf(Profile(id = "adult", token = "Alice", apiUrl = url))
    }, discoverDelayMs = 1_000) { _, _ ->
        waitUntil(timeoutMillis = 10_000) { shows("Recent One") }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        onNodeWithText("Alice").assertIsFocused()
    }

    @Test
    fun `whoever was here last comes first`() = picker({ url ->
        listOf(
            Profile(id = "a", token = "Alice", apiUrl = url),
            Profile(id = "b", token = "Bob", apiUrl = url),
        )
    }) { _, auth ->
        waitUntil(timeoutMillis = 10_000) { shows("Alice") }
        auth.confirmProfile("b")
        auth.showPicker()
        waitForIdle()
        mainClock.advanceTimeBy(500)
        waitForIdle()
        val left = { name: String -> onNodeWithText(name).fetchSemanticsNode().boundsInRoot.left }
        assertTrue(left("Bob") < left("Alice"), "Bob ${left("Bob")} Alice ${left("Alice")}")
        onNodeWithText("Bob").assertIsFocused()
    }
}
