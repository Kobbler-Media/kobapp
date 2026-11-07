package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One line at any width: as many named actions as fit, in order, and More only for what does not. */
@OptIn(ExperimentalTestApi::class)
class DetailActionRowFitTest {

    private val names = listOf("Watched", "Save all episodes offline", "Add to your list", "Want", "Keep", "Kids", "Rewatch from the start")
    private val actions = listOf(RowAction("lead", "Rewatch", Icons.Outlined.PlayArrow, {}, detail = "S01 E01")) +
        names.map { RowAction(it, it, Icons.Outlined.Download, {}) }

    private class Natural(val lead: Dp, val actions: List<Dp>, val more: Dp)

    @Test
    fun `fills one line and overflows only what does not fit, at every width`() = runUiTest(width = 2400, height = 400) {
        var rowWidth by mutableStateOf(2000.dp)
        var wide by mutableStateOf(true)
        setContent {
            KobappTheme(formFactor = FormFactor.Desktop) {
                Box(Modifier.width(rowWidth).testTag("row")) {
                    DetailActionRow(actions = actions, accent = Color.Red, wide = wide)
                }
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithContentDescription("More").fetchSemanticsNodes().size, "everything fits at 2000dp")
        val lead = onNodeWithText("Rewatch").width()
        val widths = names.map { onNodeWithContentDescription(it).width() }
        rowWidth = 300.dp
        waitForIdle()
        val natural = Natural(lead, widths, onNodeWithContentDescription("More").width())

        for (mode in listOf(true, false)) {
            wide = mode
            for (w in 120..1000 step 20) {
                rowWidth = w.dp
                waitForIdle()
                checkRow(w.dp, mode, natural)
            }
        }
    }

    private fun DesktopComposeUiTest.checkRow(width: Dp, wide: Boolean, natural: Natural) {
        val at = "width $width wide=$wide"
        val shown = names.filter { onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(names.take(shown.size), shown, "$at: actions out of order")
        val overflowing = onAllNodesWithContentDescription("More").fetchSemanticsNodes().isNotEmpty()
        assertEquals(shown.size < names.size, overflowing, "$at: More shown=$overflowing with ${shown.size} of ${names.size} actions")

        val gap = if (wide) 12.dp else 8.dp
        fun fits(count: Int): Boolean {
            val more = if (count < names.size) gap + natural.more else 0.dp
            val actions = natural.actions.take(count).fold(0.dp) { sum, w -> sum + w + gap }
            return natural.lead + actions + more <= width + 0.5.dp
        }
        val expected = (names.size downTo 0).firstOrNull { fits(it) } ?: 0
        assertEquals(expected, shown.size, "$at: row holds ${shown.size} actions, room for $expected")

        val row = onNodeWithTag("row").getBoundsInRoot()
        val lead = onNodeWithText("Rewatch").getBoundsInRoot()
        val controls = listOf(lead) + shown.map { onNodeWithContentDescription(it).getBoundsInRoot() } +
            listOfNotNull(if (overflowing) onNodeWithContentDescription("More").getBoundsInRoot() else null)
        for (name in shown + listOfNotNull("More".takeIf { overflowing })) {
            val c = onNodeWithContentDescription(name).getBoundsInRoot()
            assertTrue(c.right - c.left > (c.bottom - c.top) * 2, "$at: $name is a bare icon, not a named button: $c")
        }
        val middle = (lead.top + lead.bottom) / 2
        for (c in controls) {
            assertTrue((c.top + c.bottom) / 2 - middle in (-1).dp..1.dp, "$at: a control left the line: $c vs $lead")
            assertTrue(c.right <= row.right + 0.5.dp, "$at: a control runs past the row: $c vs $row")
        }
        if (!wide) assertTrue(controls.last().right >= row.right - 0.5.dp, "$at: the lead left room on a narrow row")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.width(): Dp = getBoundsInRoot().let { it.right - it.left }
}
