package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fact that wraps opens its line: no separator is left before it, or after the one above. */
@OptIn(ExperimentalTestApi::class)
class DetailMetaTest {

    @Test
    fun `a wrapped fact starts its own line flush left`() = runUiTest(width = 400, height = 300) {
        setContent {
            KobappTheme {
                Box(Modifier.width(180.dp)) {
                    DetailMeta(metaText("1h 39m"), metaText("R"), metaText("Horror, Drama, Thriller"))
                }
            }
        }
        val first = onNodeWithText("1h 39m").getBoundsInRoot()
        val wrapped = onNodeWithText("Horror, Drama, Thriller").getBoundsInRoot()
        assertTrue(wrapped.top >= first.bottom, "the long fact did not wrap")
        assertEquals(first.left, wrapped.left, "something sits before the wrapped fact")
    }
}
