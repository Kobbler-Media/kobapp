package dev.kobbler.app.platform.linux

import java.awt.Canvas
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * X11 reports a sideways trackpad swipe as buttons 6/7, which OpenJDK passes on as extra
 * mouse buttons 4/5 instead of a wheel — so rails never scrolled, and Compose read a
 * swipe left as the mouse's Back button.
 */
class HorizontalScrollTest {

    @Test
    fun `a sideways swipe press is one wheel step and the rest of it is swallowed`() {
        val cases = listOf(
            Triple(MouseEvent.MOUSE_PRESSED, 4, SwipeStep.Wheel(-1)),
            Triple(MouseEvent.MOUSE_PRESSED, 5, SwipeStep.Wheel(1)),
            Triple(MouseEvent.MOUSE_RELEASED, 4, SwipeStep.Swallow),
            Triple(MouseEvent.MOUSE_CLICKED, 5, SwipeStep.Swallow),
            Triple(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, null),
            Triple(MouseEvent.MOUSE_PRESSED, 6, null),
            Triple(MouseEvent.MOUSE_MOVED, MouseEvent.NOBUTTON, null),
            Triple(MouseEvent.MOUSE_WHEEL, MouseEvent.NOBUTTON, null),
        )
        for ((id, button, expected) in cases) {
            assertEquals(expected, x11SwipeStep(id, button), "event $id button $button")
        }
    }

    @Test
    fun `the wheel step is one Compose reads as horizontal, where the pointer is`() {
        val press = MouseEvent(Canvas(), MouseEvent.MOUSE_PRESSED, 0L, 0, 10, 20, 110, 120, 1, false, MouseEvent.BUTTON1)
        val wheel = horizontalWheel(press, -1)
        assertTrue(wheel.isShiftDown, "Compose only reads a shifted wheel as horizontal")
        assertEquals(-1.0, wheel.preciseWheelRotation)
        assertEquals(10 to 20, wheel.x to wheel.y)
        assertEquals(110 to 120, wheel.xOnScreen to wheel.yOnScreen)
    }
}
