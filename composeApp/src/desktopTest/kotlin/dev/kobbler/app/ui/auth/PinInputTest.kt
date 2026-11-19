package dev.kobbler.app.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.Profile
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.ProfilePinDialog
import dev.kobbler.app.ui.dashboard.ProfilePinMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Both places a PIN is typed accept exactly what the server accepts: no whitespace, at most 32. */
@OptIn(ExperimentalTestApi::class)
class PinInputTest {

    private val entries: List<Pair<String, @Composable () -> Unit>> = listOf(
        "unlock" to {
            ProfilePinEntryScreen(
                auth = AuthState(rememberCoroutineScope(), FormFactor.Desktop),
                profile = Profile(id = "p", token = "t", apiUrl = "http://127.0.0.1:1", name = "Pat"),
                onCancel = {},
                onSuccess = {},
            )
        },
        "set" to { ProfilePinDialog(ProfilePinMode.Set, Color.Red, onDismiss = {}, onConfirm = {}) },
    )

    // The set dialog masks its field, so only the length of what was kept is readable.
    private fun DesktopComposeUiTest.pinLength(): Int =
        onNode(hasSetTextAction()).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().length

    @Test
    fun `whitespace and over-long input is refused, a valid pin is kept`() = entries.forEach { (name, entry) ->
        runUiTest {
            setContent { KobappTheme { entry() } }
            val field = onNode(hasSetTextAction())
            field.performTextInput("12 3")
            waitForIdle()
            assertEquals(0, pinLength(), name)
            field.performTextInput("1234")
            waitForIdle()
            assertEquals(4, pinLength(), name)
            field.performTextInput("x".repeat(MAX_PIN_LENGTH))
            waitForIdle()
            assertEquals(4, pinLength(), name)
        }
    }

    @Test
    fun `a refused pin clears the field and the next keystroke clears the error`() = runUiTest {
        setContent { KobappTheme { entries.first().second() } }
        val field = onNode(hasSetTextAction())
        field.performTextInput("9999")
        onNodeWithText("Unlock").performClick()
        waitUntil(timeoutMillis = UiTimeoutMs) {
            onAllNodes(hasText("Incorrect PIN")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, pinLength())
        field.performTextInput("1")
        waitForIdle()
        assertEquals(0, onAllNodes(hasText("Incorrect PIN")).fetchSemanticsNodes().size)
        assertEquals(1, pinLength())
    }
}
