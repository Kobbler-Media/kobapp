package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.kobbler.app.ui.components.AlphabetBar
import dev.kobbler.app.ui.components.CenterFocusedItem
import dev.kobbler.app.ui.components.alphabetLetterOf
import dev.kobbler.app.ui.components.rowKeyFor
import dev.kobbler.app.ui.components.swallowsUp

/**
 * The focused cell, held off the screen's own composition. Shared by the three browse
 * screens in this package.
 *
 * Written from `onFocusChanged`, so anything that reads it as a value in a screen body
 * recomposes that whole grid on every D-pad press. Every reader below takes the holder
 * and reads inside its own scope instead.
 */
@Stable
internal class BrowseFocus {
    var lazyIndex by mutableIntStateOf(-1)
    var rowKey by mutableStateOf<Any?>(null)
    var letter by mutableStateOf<String?>(null)
    var letterJump by mutableStateOf<LetterJump?>(null)
    var cellId by mutableStateOf<String?>(null)
    val barExit = FocusRequester()
    val barExitId: String? get() = letterJump?.id ?: cellId

    fun onGridFocus(id: String, title: String, lazyIndex: Int, grid: LazyGridState) {
        cellId = id
        letter = alphabetLetterOf(title)
        this.lazyIndex = lazyIndex
        rowKey = rowKeyFor(grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndex }?.row, rowKey)
        letterJump = null
    }
}

/**
 * The first item under a letter just picked from the A-Z bar. Held by [id] as well as
 * position: landing that close to the top of the list pulls the previous page in above
 * it, which shifts every index.
 */
internal class LetterJump(val lazyIndex: Int, val id: String)

/**
 * A grid cell: reports its focus to [focus], and stands as the alphabet bar's way back
 * while it is the letter just picked or the cell the bar was entered from. Leaving
 * composition drops that claim, so the bar never aims at a requester with no node.
 */
@Composable
internal fun Modifier.browseCell(focus: BrowseFocus, id: String, title: String, lazyIndex: Int, grid: LazyGridState): Modifier {
    DisposableEffect(focus, id) { onDispose { if (focus.cellId == id) focus.cellId = null } }
    val isExit by remember(focus, id) { derivedStateOf { focus.barExitId == id } }
    return (if (isExit) focusRequester(focus.barExit) else this)
        .onFocusChanged { if (it.isFocused) focus.onGridFocus(id, title, lazyIndex, grid) }
}

@Composable
internal fun CenterFocusedGridRow(state: LazyGridState, focus: BrowseFocus) {
    CenterFocusedItem(state, focus.lazyIndex, focus.rowKey)
    val jump = focus.letterJump
    LaunchedEffect(jump) {
        if (jump != null) state.scrollToItem(jump.lazyIndex)
    }
}

internal fun Modifier.swallowUpAtTop(focus: BrowseFocus, topRowKey: () -> Any?): Modifier =
    onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionUp &&
            swallowsUp(focus.rowKey, topRowKey())
    }

/**
 * LEFT out of the bar goes to the letter just picked, else back to the cell it was
 * entered from. Handing focus to the grid as a whole lands on whatever cell the lazy
 * layout offers first, so that is only the fallback for a bar entered from elsewhere.
 */
@Composable
internal fun BoxScope.BrowseAlphabetBar(
    letters: List<String>,
    grid: FocusRequester,
    focus: BrowseFocus,
    onLetterClick: (String) -> Unit,
) {
    AlphabetBar(
        letters = letters,
        exitLeft = if (focus.barExitId != null) focus.barExit else grid,
        currentLetter = focus.letter,
        onLetterClick = onLetterClick,
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
}
