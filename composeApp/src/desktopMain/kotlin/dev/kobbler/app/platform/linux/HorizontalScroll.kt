package dev.kobbler.app.platform.linux

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/**
 * X11 reports a sideways trackpad swipe as buttons 6/7. OpenJDK only treats 4/5 as a wheel
 * and passes 6/7 on as extra mouse buttons 4/5 — the ones Compose calls Back and Forward —
 * so rails never scrolled sideways and a swipe left navigated back. (JetBrains Runtime
 * translates these itself; the stock JDK the app ships on does not.)
 *
 * Only under XToolkit: everywhere else buttons 4/5 really are a mouse's Back/Forward.
 */
fun installX11HorizontalScroll() {
    val toolkit = Toolkit.getDefaultToolkit()
    if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
    toolkit.systemEventQueue.push(object : EventQueue() {
        override fun dispatchEvent(event: AWTEvent) {
            if (event !is MouseEvent) return super.dispatchEvent(event)
            when (val step = x11SwipeStep(event.id, event.button)) {
                null -> super.dispatchEvent(event)
                SwipeStep.Swallow -> {}
                is SwipeStep.Wheel -> super.dispatchEvent(horizontalWheel(event, step.rotation))
            }
        }
    })
}

internal sealed interface SwipeStep {
    data class Wheel(val rotation: Int) : SwipeStep
    data object Swallow : SwipeStep
}

private const val SwipeLeft = 4
private const val SwipeRight = 5

internal fun x11SwipeStep(id: Int, button: Int): SwipeStep? {
    if (id == MouseEvent.MOUSE_WHEEL || (button != SwipeLeft && button != SwipeRight)) return null
    if (id != MouseEvent.MOUSE_PRESSED) return SwipeStep.Swallow
    return SwipeStep.Wheel(if (button == SwipeLeft) -1 else 1)
}

// Compose reads a shifted vertical wheel as horizontal; there is no other way to say it in AWT.
internal fun horizontalWheel(press: MouseEvent, rotation: Int): MouseWheelEvent =
    MouseWheelEvent(
        press.component, MouseEvent.MOUSE_WHEEL, press.`when`, InputEvent.SHIFT_DOWN_MASK,
        press.x, press.y, press.xOnScreen, press.yOnScreen,
        0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation,
    )
