package dev.kobbler.app.ui.components

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BrowseHeaderTest {

    private val tabs = listOf("Guide", "Channels", "Recordings")
    private val longCount = "25806 channels · 1 favourites"

    @Composable
    private fun Tabs(onHeight: (Int) -> Unit) {
        Box(Modifier.onGloballyPositioned { onHeight(it.size.height) }) {
            SegmentedFilter(
                options = tabs,
                selectedIndex = 0,
                accent = KobappTheme.colors.accHome,
                onSelected = {},
            )
        }
    }

    private fun measure(
        width: androidx.compose.ui.unit.Dp = 360.dp,
        content: @Composable (onHeight: (Int) -> Unit) -> Unit,
    ): Int {
        var height = 0
        runUiTest {
            setContent {
                KobappTheme {
                    Box(Modifier.width(width)) {
                        content { height = it }
                    }
                }
            }
            waitForIdle()
        }
        return height
    }

    private fun headerHeight(width: androidx.compose.ui.unit.Dp) = measure(width) { onHeight ->
        Box(Modifier.onGloballyPositioned { onHeight(it.size.height) }) {
            BrowseHeader(
                title = "Live TV",
                accent = KobappTheme.colors.accHome,
                countLine = longCount,
                compact = true,
                trailing = { Tabs {} },
            )
        }
    }

    @Test
    fun aLongCountLineDoesNotSquashTheTrailingControl() {
        val alone = measure { onHeight -> Tabs(onHeight) }
        val inHeader = measure { onHeight ->
            BrowseHeader(
                title = "Live TV",
                accent = KobappTheme.colors.accHome,
                countLine = longCount,
                compact = true,
                trailing = { Tabs(onHeight) },
            )
        }
        assertEquals(alone, inHeader)
    }

    @Test
    fun aTrailingControlThatCannotFitDropsToItsOwnRow() {
        val narrow = headerHeight(360.dp)
        val wide = headerHeight(1200.dp)
        val pill = measure { onHeight -> Tabs(onHeight) }
        assertTrue(narrow >= wide + pill, "narrow=$narrow wide=$wide pill=$pill")
    }

    @Test
    fun everythingOnOneRowWhenThereIsRoom() {
        val wide = headerHeight(1200.dp)
        val pill = measure { onHeight -> Tabs(onHeight) }
        assertTrue(wide < pill * 2, "wide=$wide pill=$pill")
    }

    @Test
    fun aShortCountLineAlsoLeavesItAlone() {
        val alone = measure { onHeight -> Tabs(onHeight) }
        val inHeader = measure { onHeight ->
            BrowseHeader(
                title = "TV Shows",
                accent = KobappTheme.colors.accHome,
                countLine = "50 in library",
                compact = true,
                trailing = { Tabs(onHeight) },
            )
        }
        assertEquals(alone, inHeader)
    }
}
