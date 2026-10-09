package dev.kobbler.app

import androidx.compose.ui.window.WindowPlacement
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowMemoryTest {

    private val laptop = Rectangle(0, 0, 1920, 1080)
    private val external = Rectangle(1920, 0, 2560, 1440)

    @Test
    fun `a saved window round-trips through its stored form`() {
        val saved = SavedWindow(WindowPlacement.Floating, x = 2100, y = 80, width = 1400, height = 900)
        assertEquals(saved, SavedWindow.decode(saved.encode()))
    }

    @Test
    fun `garbage in the store is ignored`() {
        for (raw in listOf(null, "", "Floating,1,2", "Sideways,1,2,3,4", "Floating,a,b,c,d")) {
            assertNull(SavedWindow.decode(raw), "decoded $raw")
        }
    }

    @Test
    fun `a position is kept only while a screen still shows the window's top edge`() {
        val onExternal = SavedWindow(WindowPlacement.Floating, x = 2100, y = 80, width = 1400, height = 900)
        val cases = listOf(
            listOf(laptop, external) to onExternal,
            // External monitor unplugged: reopening there would put the window off-screen.
            listOf(laptop) to onExternal.copy(x = null, y = null),
        )
        for ((screens, expected) in cases) assertEquals(expected, onExternal.fitTo(screens), "screens $screens")
    }

    @Test
    fun `a window larger than every screen is shrunk to fit the largest`() {
        val huge = SavedWindow(WindowPlacement.Floating, x = 0, y = 0, width = 5000, height = 3000)
        assertEquals(huge.copy(width = 2560, height = 1440), huge.fitTo(listOf(laptop, external)))
    }
}
