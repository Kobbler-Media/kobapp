package dev.kobbler.app.ui.reader

import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.BlockKind
import dev.kobbler.app.ui.reader.pack.FixedPageDto
import dev.kobbler.app.ui.reader.pack.ImageRef
import dev.kobbler.app.ui.reader.pack.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageFitTest {

    private val tv = IntSize(1920, 1080)
    private val portraitPage = IntSize(600, 900)

    @Test
    fun aPageIsASingleImageOrFixedPageOnAFixedLayoutChapter() {
        val image = Block(kind = BlockKind.Image, image = ImageRef("p.png", "Page", 600, 900))
        val unsized = Block(kind = BlockKind.Image, image = ImageRef("p.png", "Page"))
        val kobo = Block(kind = BlockKind.FixedPage, fixed = FixedPageDto(ImageRef("art.jpg"), 600f, 914f))
        val text = Block(kind = BlockKind.Text, spans = listOf(Span("words")))
        val rule = Block(kind = BlockKind.Rule)

        assertEquals(IntSize(600, 900), fittedPageOf(listOf(image), fixedLayout = true)?.size)
        assertEquals(1, fittedPageOf(listOf(rule, image, rule), fixedLayout = true)?.blockIndex)
        assertNull(fittedPageOf(listOf(unsized), fixedLayout = true), "an unsizeable image cannot be fitted")
        assertNull(fittedPageOf(listOf(image), fixedLayout = false), "reflowable images stay in the text flow")
        assertNull(fittedPageOf(listOf(image, text), fixedLayout = true), "text on the page needs reflowing")
        assertNull(fittedPageOf(listOf(image, image), fixedLayout = true))

        val koboPage = assertNotNull(fittedPageOf(listOf(kobo), fixedLayout = false))
        assertEquals(IntSize(600, 914), koboPage.size, "a Kobo fixed page is always a page")
        assertEquals(BlockKind.FixedPage, koboPage.block.kind)
    }

    @Test
    fun layoutScalesPagesToACommonHeightInsideTheBox() {
        assertEquals(listOf(IntSize(720, 1080)), spreadLayout(tv, listOf(portraitPage)))
        assertEquals(listOf(IntSize(720, 1080), IntSize(720, 1080)), spreadLayout(tv, listOf(portraitPage, portraitPage)))
        assertEquals(listOf(IntSize(1620, 1080)), spreadLayout(tv, listOf(IntSize(1200, 800))))
        assertEquals(listOf(IntSize(1080, 720)), spreadLayout(IntSize(1080, 1920), listOf(IntSize(1200, 800))))
        assertEquals(listOf(IntSize(960, 640), IntSize(960, 640)), spreadLayout(tv, listOf(IntSize(1200, 800), IntSize(1200, 800))))
    }

    @Test
    fun aSpreadOnlyShowsWhenBothPagesKeepTheirSize() {
        assertTrue(spreadFits(tv, portraitPage, portraitPage), "portrait pages on a landscape TV")
        assertTrue(spreadFits(IntSize(2400, 1080), portraitPage, portraitPage), "portrait pages on a landscape phone")
        assertFalse(spreadFits(IntSize(1080, 1920), portraitPage, portraitPage), "portrait screen")
        assertFalse(spreadFits(tv, IntSize(1200, 800), IntSize(1200, 800)), "landscape pages")
    }

    @Test
    fun fittedChaptersFollowTheSettingAndReadingDirection() {
        fun shown(fit: ReaderPageFit, partnerPage: IntSize? = portraitPage, rtl: Boolean = false, box: IntSize = tv, currentPage: IntSize? = portraitPage) =
            fittedChapters(fit, box, current = 4, currentPage = currentPage, partner = 3, partnerPage = partnerPage, rtl = rtl)

        assertEquals(listOf(4), shown(ReaderPageFit.Width), "fit width shows the page alone")
        assertNull(shown(ReaderPageFit.Spread, currentPage = null), "a text chapter is never fitted")
        assertEquals(listOf(4), shown(ReaderPageFit.Page))
        assertEquals(listOf(3, 4), shown(ReaderPageFit.Spread))
        assertEquals(listOf(4, 3), shown(ReaderPageFit.Spread, rtl = true))
        assertEquals(listOf(4), shown(ReaderPageFit.Spread, partnerPage = null))
        assertEquals(listOf(4), shown(ReaderPageFit.Spread, box = IntSize(1080, 1920)))
    }
}
