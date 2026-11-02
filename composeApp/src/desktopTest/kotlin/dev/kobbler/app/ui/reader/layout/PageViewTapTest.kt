package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.runUiTest
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockOffset
import dev.kobbler.app.ui.reader.pack.Link
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import dev.kobbler.app.ui.reader.pack.Position
import dev.kobbler.app.ui.reader.pack.Span
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PageViewTapTest {

    private val link = Link(position = Position(3, 1, 0))
    private val blocks = listOf(
        Block(spans = listOf(Span("Go "), Span("elsewhere", link = link), Span(" now"), Span("7", note = "n7"))),
        Block(spans = listOf(Span("Plain words to press on.")), spaceBefore = 1f),
    )

    @Test
    fun `taps route to links, notes and plain positions`() = runUiTest(width = 400, height = 300) {
        val chapter = runBlocking {
            layoutChapter(blocks, listOf(PackTextStyle()), LayoutRig.typography, IntSize(400, 300), density, createFontFamilyResolver())
        }
        val events = mutableListOf<Any?>()
        setContent {
            PageView(
                chapter, 0, blocks, listOf(PackTextStyle()), LayoutRig.typography, imageModel = { it },
                modifier = Modifier.testTag("page"),
                onTap = { position, _ -> events += position },
                onLink = { events += it },
                onNote = { events += it },
                onLongPress = { events += "long" to it },
            )
        }
        fun centerOf(block: Int, offset: Int): Offset {
            val slice = chapter.pages[0].slices.first { it.blockIndex == block } as TextSlice
            val box = slice.layout.getBoundingBox(offset)
            return Offset(slice.left + box.center.x, slice.top - slice.layoutTop + box.center.y)
        }
        val page = onNodeWithTag("page")
        page.performTouchInput { click(centerOf(0, 5)) }
        page.performTouchInput { click(centerOf(0, blocks[0].text.length - 1)) }
        page.performTouchInput { click(centerOf(1, 2)) }
        page.performTouchInput { click(Offset(200f, 290f)) }
        page.performTouchInput { longClick(centerOf(1, 6)) }
        waitForIdle()
        assertEquals(link, events[0])
        assertEquals("n7", events[1])
        assertEquals(1, (events[2] as BlockOffset).block)
        assertNull(events[3])
        val (kind, pressed) = events[4] as Pair<*, *>
        assertEquals("long", kind)
        assertEquals(1, (pressed as BlockOffset).block)
        assertTrue(pressed.offset in 6..7)
        assertEquals(5, events.size)
    }
}
