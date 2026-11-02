package dev.kobbler.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.Link
import dev.kobbler.kobfocus.focusKey

internal sealed interface ReaderSheet {
    data class Note(val key: String) : ReaderSheet
    data class BlockActions(val block: Int) : ReaderSheet
}

@Composable
internal fun ReaderNoteSheet(blocks: List<Block>, theme: ReaderTheme, onDismiss: () -> Unit) {
    BottomSheet(theme, "note", onDismiss) {
        Column(
            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (block in blocks) {
                Text(block.text.trim(), style = bodyStyle(theme))
            }
        }
        SheetAction("Close", theme, "note.close", onDismiss)
    }
}

/** What Center offers on a block that carries footnotes or links: TV has no pointer to
 *  tap them with, so they are listed next to reading aloud from the block. */
@Composable
internal fun ReaderBlockActions(
    block: Block,
    theme: ReaderTheme,
    onRead: () -> Unit,
    onNote: (String) -> Unit,
    onLink: (Link) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(theme, "actions", onDismiss) {
        SheetAction("Read aloud from here", theme, "actions.read", onRead)
        for ((i, span) in block.spans.withIndex()) {
            val label = span.text.trim().take(MaxLabel)
            when {
                span.note != null -> SheetAction("Footnote $label", theme, "actions.note.$i") { onNote(span.note) }
                span.link != null -> SheetAction("Go to “$label”", theme, "actions.link.$i") { onLink(span.link) }
            }
        }
    }
}

internal fun Block.hasActions(): Boolean = spans.any { it.note != null || it.link != null }

@Composable
private fun BottomSheet(theme: ReaderTheme, panel: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.backgroundColor.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .readerPanel(panel)
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(theme.backgroundColor)
                .clickable(enabled = false) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun SheetAction(text: String, theme: ReaderTheme, key: String, onClick: () -> Unit) {
    Text(
        text,
        style = bodyStyle(theme).copy(color = theme.linkColor),
        modifier = Modifier
            .focusKey(key)
            .fillMaxWidth()
            .tvAutoGlow(cornerRadiusDp = 6f, scale = 1.01f)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
    )
}

@Composable
private fun bodyStyle(theme: ReaderTheme): TextStyle =
    KobappTheme.type.body.copy(color = theme.textColor, fontFamily = theme.fontFamily)

private const val MaxLabel = 24
