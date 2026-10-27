package dev.kobbler.app.ui.reader

import androidx.compose.ui.unit.IntSize
import dev.kobbler.app.ui.reader.epub.XhtmlBlock
import dev.kobbler.app.ui.reader.epub.XhtmlSpan
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
    fun readsPixelSizeFromImageHeaders() {
        val cases = listOf(
            "png" to TestImages.png(640, 960),
            "gif" to TestImages.gif(300, 200),
            "jpeg" to TestImages.jpeg(1200, 1800),
            "webp lossy" to TestImages.webpLossy(800, 1200),
            "webp lossless" to TestImages.webpLossless(1000, 1500),
            "webp extended" to TestImages.webpExtended(1600, 2400),
        )
        val expected = listOf(IntSize(640, 960), IntSize(300, 200), IntSize(1200, 1800), IntSize(800, 1200), IntSize(1000, 1500), IntSize(1600, 2400))
        for ((i, case) in cases.withIndex()) {
            assertEquals(expected[i], imagePixelSize(case.second), case.first)
        }
        assertNull(imagePixelSize(byteArrayOf(1, 2, 3)))
        assertNull(imagePixelSize(ByteArray(0)))
    }

    @Test
    fun aPageIsASingleImageOrFixedPageOnAFixedLayoutChapter() {
        val image = XhtmlBlock.Image("p.png", "Page", width = 600, height = 900)
        val unsized = XhtmlBlock.Image("p.png", "Page")
        val kobo = XhtmlBlock.FixedPage(XhtmlBlock.Image("art.jpg", null, width = 600, height = 914), emptyList())
        val text = XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text("words")))
        val headerBytes = mapOf("p.png" to TestImages.png(1000, 1600))

        assertEquals(IntSize(600, 900), fittedPageOf(listOf(image), emptyMap(), fixedLayout = true)?.size)
        assertEquals(1, fittedPageOf(listOf(XhtmlBlock.Spacer, image, XhtmlBlock.Divider), emptyMap(), fixedLayout = true)?.blockIndex)
        assertEquals(IntSize(1000, 1600), fittedPageOf(listOf(unsized), headerBytes, fixedLayout = true)?.size)
        assertNull(fittedPageOf(listOf(unsized), emptyMap(), fixedLayout = true), "an unsizeable image cannot be fitted")
        assertNull(fittedPageOf(listOf(image), emptyMap(), fixedLayout = false), "reflowable images stay in the text flow")
        assertNull(fittedPageOf(listOf(image, text), emptyMap(), fixedLayout = true), "text on the page needs reflowing")
        assertNull(fittedPageOf(listOf(image, image), emptyMap(), fixedLayout = true))

        val koboPage = assertNotNull(fittedPageOf(listOf(kobo), emptyMap(), fixedLayout = false))
        assertEquals(IntSize(600, 914), koboPage.size, "a Kobo fixed page is always a page")
        assertTrue(koboPage.block is XhtmlBlock.FixedPage)
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
