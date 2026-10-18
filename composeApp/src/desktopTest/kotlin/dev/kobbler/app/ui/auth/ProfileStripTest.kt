package dev.kobbler.app.ui.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.kobbler.app.data.Profile
import dev.kobbler.app.platform.FormFactor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The picker is a thin strip of profiles, leaving the arrival behind it on show. */
@OptIn(ExperimentalTestApi::class)
class ProfileStripTest {

    private data class Screen(val name: String, val formFactor: FormFactor, val width: Int, val height: Int)

    private val screens = listOf(
        Screen("phone portrait", FormFactor.Phone, 412, 892),
        Screen("phone landscape", FormFactor.Phone, 892, 412),
        Screen("desktop", FormFactor.Desktop, 1280, 800),
        Screen("tv", FormFactor.TV, 1920, 1080),
    )

    @Test
    fun `the profiles stand in a thin strip with no heading`() = screens.forEach { screen ->
        picker(
            profiles = { url -> listOf(Profile(id = "a", token = "Alice", apiUrl = url)) },
            formFactor = screen.formFactor,
            width = screen.width,
            height = screen.height,
        ) { _, _ ->
            waitUntil(timeoutMillis = 10_000) { shows("Alice") }
            assertFalse(shows("Choose your profile"), screen.name)
            assertFalse(shows("Who’s watching tonight?"), screen.name)

            val root = onRoot().fetchSemanticsNode().boundsInRoot
            val strip = onNodeWithTag(ProfileStripTag).fetchSemanticsNode().boundsInRoot
            assertTrue(strip.height < root.height * 0.4f, "${screen.name}: strip ${strip.height} of ${root.height}")

            val tile = with(density) { onNodeWithText("Alice").fetchSemanticsNode().boundsInRoot.let { it.width.toDp() to it.height.toDp() } }
            assertTrue(tile.first.value >= 48f && tile.second.value >= 48f, "${screen.name}: tile $tile")
        }
    }
}
