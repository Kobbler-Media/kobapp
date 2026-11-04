package dev.kobbler.app.ui.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BackButtonGutter
import dev.kobbler.app.ui.components.BackButtonSize
import dev.kobbler.app.ui.components.BackButtonTop
import dev.kobbler.app.ui.detail.tvshow.press
import dev.kobbler.app.ui.navigation.BackInterceptors
import dev.kobbler.app.ui.navigation.LocalBackInterceptors
import dev.kobbler.app.ui.player.PLAYBACK_WORKED
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StreamOptionsPageTest {

    private val remux = StreamOption(
        id = "remux", releaseTitle = "Dune.Part.Two.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.Atmos.7.1-FraMeSToR",
        size = 78_000_000_000, indexer = "hash_index:torrentio", infoHash = "h1",
        quality = StreamQuality("2160p", "HEVC", "DV", "TrueHD Atmos"), qualityJson = null,
        totalScore = 0.97, debridProvider = "Real-Debrid", debridProviderId = "rd", cached = true,
        sourceType = "torrent", fileId = null, publishDate = null, playback = PLAYBACK_WORKED,
        group = "played", audioLanguages = listOf("en", "es"),
    )
    private val web = remux.copy(
        id = "web", releaseTitle = "Dune.Part.Two.2024.1080p.WEB-DL.DDP5.1.H.264-FLUX", size = 9_400_000_000,
        quality = StreamQuality("1080p", "H.264", null, "DD+ 5.1"), cached = false, playback = null,
        group = "uncached", audioLanguages = emptyList(), indexer = "hash_index:dmm",
    )

    private class Page {
        val backs = BackInterceptors()
        var dismissed = 0
        lateinit var focus: FocusManager
        val picked = mutableListOf<String>()
    }

    private fun DesktopComposeUiTest.showPage(formFactor: FormFactor): Page {
        val page = Page()
        setContent {
            CompositionLocalProvider(LocalBackInterceptors provides page.backs) {
                KobappTheme(formFactor = formFactor) {
                    page.focus = LocalFocusManager.current
                    StreamOptionsOverlay(
                        options = listOf(remux, web),
                        errors = emptyList(),
                        loading = false,
                        onSelect = { page.picked += it.id },
                        onDismiss = { page.dismissed++ },
                    )
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeBy(400)
        waitForIdle()
        return page
    }

    @Test
    fun `phone back is the app's arrow at the search bar's spot`() = runUiTest(412, 900) {
        val page = showPage(FormFactor.Phone)
        val arrow = onNodeWithContentDescription("Back", useUnmergedTree = true).getBoundsInRoot()
        val centreX = (arrow.left + arrow.right) / 2
        val centreY = (arrow.top + arrow.bottom) / 2
        val expected = BackButtonGutter(FormFactor.Phone, gutterFor(FormFactor.Phone)) + BackButtonSize / 2
        assertTrue(abs((centreX - expected).value) <= 1f, "arrow centre x is $centreX, want $expected")
        assertTrue(abs((centreY - (BackButtonTop + BackButtonSize / 2)).value) <= 1f, "arrow centre y is $centreY")
        assertEquals(22.dp, arrow.right - arrow.left)

        onNodeWithContentDescription("Back").performClick()
        waitForIdle()
        assertEquals(1, page.dismissed)
    }

    @Test
    fun `back leaves the page from the remote on tv, which has no button`() = runUiTest(1280, 720) {
        val page = showPage(FormFactor.TV)
        assertEquals(0, onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size)
        assertTrue(page.backs.consume())
        assertEquals(1, page.dismissed)
    }

    @Test
    fun `a row spells out quality, size, source, availability and language apart from its release name`() {
        for ((ff, size) in listOf(FormFactor.Phone to (412 to 900), FormFactor.TV to (1280 to 720))) runUiTest(size.first, size.second) {
            showPage(ff)
            for (chip in listOf("2160p", "Instant", "Played before", "DV", "HEVC", "TrueHD Atmos", "EN · ES", "Torrentio", "72.6 GB")) {
                onNodeWithText(chip).assertIsDisplayed()
            }
            onNodeWithText(remux.releaseTitle).assertIsDisplayed()
            onAllNodesWithText("match", substring = true).assertCountEquals(0)
        }
    }

    @Test
    fun `a long release name stays on one line until held, then shows whole`() = runUiTest(360, 800) {
        showPage(FormFactor.Phone)
        val name = onNodeWithText(remux.releaseTitle, useUnmergedTree = true)
        val oneLine = name.getBoundsInRoot().let { it.bottom - it.top }
        name.performTouchInput { longClick() }
        waitForIdle()
        val whole = onNodeWithText(remux.releaseTitle, useUnmergedTree = true).getBoundsInRoot().let { it.bottom - it.top }
        assertTrue(oneLine < 24.dp, "release name took $oneLine before holding")
        assertTrue(whole > oneLine * 1.5f, "release name grew from $oneLine to only $whole")
    }

    @Test
    fun `a touch on a phone row and OK on a focused tv row both pick that stream`() {
        runUiTest(412, 900) {
            val page = showPage(FormFactor.Phone)
            onNodeWithText(web.releaseTitle).performClick()
            waitForIdle()
            assertEquals(listOf("web"), page.picked)
        }
        runUiTest(1280, 720) {
            val page = showPage(FormFactor.TV)
            onNode(isFocused() and hasText(remux.releaseTitle)).assertExists("the best stream did not take focus")
            runOnIdle { page.focus.moveFocus(FocusDirection.Down) }
            waitForIdle()
            onNode(isFocused() and hasText(web.releaseTitle)).assertExists("DOWN did not reach the next stream")
            press(Key.Enter)
            assertEquals(listOf("web"), page.picked)
        }
    }
}
