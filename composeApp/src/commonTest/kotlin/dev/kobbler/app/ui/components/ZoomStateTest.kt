package dev.kobbler.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class ZoomStateTest {

    private fun zoom() = ZoomState().apply { bounds = IntSize(1000, 2000) }

    @Test
    fun `pinching keeps the point under the fingers still`() {
        val state = zoom()
        state.transform(centroid = Offset(800f, 1000f), pan = Offset.Zero, zoom = 2f)
        assertEquals(2f, state.scale)
        assertEquals(Offset(-300f, 0f), state.offset)
    }

    @Test
    fun `panning stops at the edges of the zoomed content`() {
        val state = zoom()
        state.transform(Offset(500f, 1000f), Offset.Zero, 2f)
        state.transform(Offset(500f, 1000f), Offset(5000f, -5000f), 1f)
        assertEquals(Offset(500f, -1000f), state.offset)
    }

    @Test
    fun `zoom stays between whole and the maximum`() {
        val state = zoom()
        state.transform(Offset(500f, 1000f), Offset(40f, 40f), 0.5f)
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
        state.transform(Offset(500f, 1000f), Offset.Zero, 100f)
        assertEquals(ZoomState.MaxScale, state.scale)
    }

    @Test
    fun `double tap zooms in on the spot and back out`() {
        val state = zoom()
        state.toggle(Offset(500f, 1000f))
        assertEquals(ZoomState.TapScale, state.scale)
        state.toggle(Offset(100f, 100f))
        assertEquals(1f, state.scale)
        assertEquals(Offset.Zero, state.offset)
    }
}
