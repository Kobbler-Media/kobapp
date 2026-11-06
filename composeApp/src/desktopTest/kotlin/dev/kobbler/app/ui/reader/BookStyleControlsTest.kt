package dev.kobbler.app.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.reader.layout.BookStyle
import dev.kobbler.app.ui.reader.layout.StyleAspect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BookStyleControlsTest {

    private fun androidx.compose.ui.test.DesktopComposeUiTest.chip(text: String, aspect: StyleAspect? = null): SemanticsNodeInteraction =
        onNode(hasText(text).let { m -> aspect?.let { m and hasAnyAncestor(hasContentDescription(it.label)) } ?: m })

    private fun androidx.compose.ui.test.DesktopComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        mainClock.advanceTimeBy(300)
        waitForIdle()
    }

    @Test
    fun `presets pick all or nothing, and anything between is custom`() = runUiTest {
        var style by mutableStateOf(BookStyle.Book)
        setContent { KobappTheme { BookStyleControls(style, { style = it }, ReaderTheme.Dark) } }
        chip("Book's style").assertIsSelected()

        chip("My style").performClick()
        waitForIdle()
        assertEquals(BookStyle.Mine, style)
        chip("My style").assertIsSelected()

        chip("Custom").performClick()
        waitForIdle()
        chip("Book", StyleAspect.Colours).performClick()
        waitForIdle()
        assertEquals(BookStyle.Mine.with(StyleAspect.Colours, book = true), style)
        chip("Custom").assertIsSelected()
        chip("Book", StyleAspect.Colours).assertIsSelected()
        chip("Mine", StyleAspect.Sizes).assertIsSelected()
    }

    @Test
    fun `a remote opens the custom choices and changes one`() = runUiTest(width = 960, height = 540) {
        var style by mutableStateOf(BookStyle.Book)
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                ArrowsMoveFocus { BookStyleControls(style, { style = it }, ReaderTheme.Dark) }
            }
        }
        press(Key.Tab)
        assertTrue(onAllNodes(isFocused() and hasText("Book's style")).fetchSemanticsNodes().isNotEmpty(), "focus did not enter the choices")
        listOf(Key.DirectionRight, Key.DirectionRight, Key.Enter).forEach { press(it) }
        assertTrue(onAllNodes(hasContentDescription(StyleAspect.Typeface.label)).fetchSemanticsNodes().isNotEmpty(), "Custom did not open")
        listOf(Key.DirectionDown, Key.DirectionRight, Key.Enter).forEach { press(it) }
        assertEquals(BookStyle.Book.with(StyleAspect.Typeface, book = false), style)
        assertTrue(onAllNodes(isFocused() and hasText("Mine")).fetchSemanticsNodes().isNotEmpty())
    }
}

/** What the app's root does with a remote's arrows, which the panel relies on. */
@Composable
private fun ArrowsMoveFocus(content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    Box(
        Modifier.onPreviewKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown) focus.moveFocus(direction)
            true
        },
    ) { content() }
}
