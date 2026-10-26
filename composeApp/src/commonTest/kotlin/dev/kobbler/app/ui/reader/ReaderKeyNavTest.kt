package dev.kobbler.app.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderKeyNavTest {

    private fun tv(
        key: ReaderKey,
        zone: ReaderZone = ReaderZone.Body,
        cursorActive: Boolean = false,
        chromeVisible: Boolean = false,
        panelOpen: Boolean = false,
        listening: Boolean = false,
    ) = readerKeyAction(key, zone, cursorActive, chromeVisible, immersive = true, panelOpen = panelOpen, chaptersOnDown = !listening)

    private fun desktop(
        key: ReaderKey,
        zone: ReaderZone = ReaderZone.Body,
        cursorActive: Boolean = false,
        panelOpen: Boolean = false,
    ) = readerKeyAction(key, zone, cursorActive, chromeVisible = true, immersive = false, panelOpen = panelOpen, chaptersOnDown = false)

    @Test
    fun readingPages() {
        val cases = listOf(
            ReaderKey.Left to ReaderKeyAction.PrevPage,
            ReaderKey.PageUp to ReaderKeyAction.PrevPage,
            ReaderKey.Right to ReaderKeyAction.NextPage,
            ReaderKey.PageDown to ReaderKeyAction.NextPage,
            ReaderKey.Space to ReaderKeyAction.NextPage,
        )
        for ((key, expected) in cases) {
            assertEquals(expected, tv(key), "tv $key")
            assertEquals(expected, desktop(key), "desktop $key")
        }
    }

    @Test
    fun verticalRevealsChromeWhileTheCursorIsInactive() {
        assertEquals(ReaderKeyAction.FocusTopBar, tv(ReaderKey.Up))
        assertEquals(ReaderKeyAction.OpenChapters, tv(ReaderKey.Down))
        assertEquals(ReaderKeyAction.FocusBottomBar, tv(ReaderKey.Down, listening = true))
        assertEquals(ReaderKeyAction.FocusTopBar, desktop(ReaderKey.Up))
        assertEquals(ReaderKeyAction.FocusBottomBar, desktop(ReaderKey.Down))
    }

    @Test
    fun verticalMovesTheCursorWhileItIsActive() {
        assertEquals(ReaderKeyAction.CursorUp, tv(ReaderKey.Up, cursorActive = true))
        assertEquals(ReaderKeyAction.CursorDown, tv(ReaderKey.Down, cursorActive = true))
        assertEquals(ReaderKeyAction.CursorUp, desktop(ReaderKey.Up, cursorActive = true))
    }

    @Test
    fun centerDrivesTheCursorAndPagingIsUnaffectedByIt() {
        assertEquals(ReaderKeyAction.CursorSelect, tv(ReaderKey.Center))
        assertEquals(ReaderKeyAction.CursorSelect, tv(ReaderKey.Center, cursorActive = true))
        assertEquals(ReaderKeyAction.NextPage, tv(ReaderKey.Right, cursorActive = true))
    }

    @Test
    fun backUnwindsCursorThenChromeThenLeavesTheReader() {
        assertEquals(ReaderKeyAction.CursorExit, tv(ReaderKey.Back, cursorActive = true))
        assertEquals(ReaderKeyAction.FocusBody, tv(ReaderKey.Back, chromeVisible = true))
        assertEquals(ReaderKeyAction.Pass, tv(ReaderKey.Back))
        // Desktop chrome is never dismissed, so BACK there always leaves the reader.
        assertEquals(ReaderKeyAction.Pass, desktop(ReaderKey.Back))
    }

    @Test
    fun chromeKeepsItsOwnTraversal() {
        for (zone in listOf(ReaderZone.TopBar, ReaderZone.BottomBar)) {
            for (key in listOf(ReaderKey.Left, ReaderKey.Right, ReaderKey.Center, ReaderKey.Space, ReaderKey.PageDown)) {
                assertEquals(ReaderKeyAction.Pass, tv(key, zone = zone, chromeVisible = true), "$zone $key")
                assertEquals(ReaderKeyAction.Pass, desktop(key, zone = zone), "$zone $key")
            }
        }
    }

    @Test
    fun chromeReturnsToReadingOnTheKeyPointingBackAtIt() {
        assertEquals(ReaderKeyAction.FocusBody, tv(ReaderKey.Down, zone = ReaderZone.TopBar, chromeVisible = true))
        assertEquals(ReaderKeyAction.Pass, tv(ReaderKey.Up, zone = ReaderZone.TopBar, chromeVisible = true))
        assertEquals(ReaderKeyAction.FocusBody, tv(ReaderKey.Up, zone = ReaderZone.BottomBar, chromeVisible = true))
        assertEquals(ReaderKeyAction.Pass, tv(ReaderKey.Down, zone = ReaderZone.BottomBar, chromeVisible = true))
        assertEquals(ReaderKeyAction.FocusBody, desktop(ReaderKey.Down, zone = ReaderZone.TopBar))
        assertEquals(ReaderKeyAction.FocusBody, desktop(ReaderKey.Up, zone = ReaderZone.BottomBar))
    }

    @Test
    fun backFromChromeDismissesItOnTvAndLeavesTheReaderOnDesktop() {
        assertEquals(ReaderKeyAction.FocusBody, tv(ReaderKey.Back, zone = ReaderZone.TopBar, chromeVisible = true))
        assertEquals(ReaderKeyAction.FocusBody, tv(ReaderKey.Back, zone = ReaderZone.BottomBar, chromeVisible = true))
        assertEquals(ReaderKeyAction.Pass, desktop(ReaderKey.Back, zone = ReaderZone.TopBar))
    }

    @Test
    fun anOpenPanelTakesOnlyBackAndBackClosesIt() {
        for (zone in ReaderZone.entries) {
            for (key in ReaderKey.entries) {
                val expected = if (key == ReaderKey.Back) ReaderKeyAction.ClosePanel else ReaderKeyAction.Pass
                assertEquals(expected, tv(key, zone = zone, panelOpen = true, chromeVisible = true), "tv $zone $key")
                assertEquals(expected, tv(key, zone = zone, panelOpen = true, cursorActive = true), "tv cursor $zone $key")
                assertEquals(expected, desktop(key, zone = zone, panelOpen = true), "desktop $zone $key")
            }
        }
    }
}
