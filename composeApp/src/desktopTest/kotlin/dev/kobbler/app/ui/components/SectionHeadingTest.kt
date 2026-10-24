package dev.kobbler.app.ui.components

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SectionHeadingTest {

    @Composable
    private fun Badge(onSize: (Int, Int) -> Unit) {
        Box(Modifier.onGloballyPositioned { onSize(it.size.width, it.size.height) }) {
            Text("20 new this week", style = KobappTheme.type.label)
        }
    }

    private fun badgeSize(content: @Composable ((Int, Int) -> Unit) -> Unit): Pair<Int, Int> {
        var size = 0 to 0
        runUiTest {
            setContent {
                KobappTheme {
                    Box(Modifier.width(360.dp)) {
                        content { w, h -> size = w to h }
                    }
                }
            }
            waitForIdle()
        }
        return size
    }

    @Test
    fun aLongTitleDoesNotSquashTheTrailingBadge() {
        val alone = badgeSize { onSize -> Badge(onSize) }
        val inHeading = badgeSize { onSize ->
            SectionHeading(
                title = "Because you watched Mr Inbetween",
                accent = KobappTheme.colors.accHome,
                trailing = { Badge(onSize) },
            )
        }
        assertEquals(alone, inHeading)
    }

    @Test
    fun aLongTitleDoesNotSquashSeeAll() {
        val alone = badgeSize { onSize -> Badge(onSize) }
        val inHeading = badgeSize { onSize ->
            SectionHeading(
                title = "Because you watched Mr Inbetween",
                accent = KobappTheme.colors.accHome,
                seeAllLabel = "see all →",
                onSeeAll = {},
                trailing = { Badge(onSize) },
            )
        }
        assertEquals(alone, inHeading)
    }
}
