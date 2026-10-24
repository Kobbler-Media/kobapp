package dev.kobbler.app.ui.reader

import dev.kobbler.app.runUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import dev.kobbler.app.ui.reader.epub.XhtmlBlock
import dev.kobbler.app.ui.reader.epub.XhtmlSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/** The spoken sentence lights one paragraph — the one being read — and no repeat of it further down. */
@OptIn(ExperimentalTestApi::class)
class ReadAloudHighlightTest {

    @Test
    fun `only the paragraph being read is highlighted`() = runUiTest {
        val sentence = "The door was open."
        val blocks = listOf(sentence, "Nobody came.", sentence).map {
            XhtmlBlock.Paragraph(listOf(XhtmlSpan.Text(it)))
        }
        setContent { EpubContent(blocks = blocks, theme = ReaderTheme.Dark, highlightedText = sentence) }

        val highlighted = onAllNodes(hasText(sentence)).fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .any { text -> text.spanStyles.any { it.item.background == ReaderTheme.Dark.highlightColor } }
        }
        assertEquals(listOf(true, false), highlighted)
    }
}
