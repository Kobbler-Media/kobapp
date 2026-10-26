package dev.kobbler.app.ui.reader

internal enum class ReaderZone { Body, TopBar, BottomBar }

internal enum class ReaderKey { Left, Right, Up, Down, Center, PageUp, PageDown, Space, Back }

internal enum class ReaderKeyAction {
    PrevPage,
    NextPage,
    CursorUp,
    CursorDown,
    CursorSelect,
    CursorExit,
    FocusTopBar,
    FocusBottomBar,
    FocusBody,
    OpenChapters,
    ClosePanel,
    Pass,
}

/**
 * Reading is a mode, not a widget: with focus on the reader root the D-pad drives the page,
 * and only once chrome has focus does it go back to ordinary traversal.
 *
 * [ReaderKeyAction.Pass] means "did not act" and the caller must leave the event unconsumed.
 * The handler runs as a root preview, so consuming a key it cannot act on would stop the
 * focused chrome element from ever seeing it.
 *
 * An open panel owns every key but BACK, which closes it rather than leaving the reader.
 * [chaptersOnDown] is the TV's DOWN: its bottom bar holds nothing but the chapter
 * position, so the remote goes straight to the chapter list unless listen-along has put
 * controls there.
 */
internal fun readerKeyAction(
    key: ReaderKey,
    zone: ReaderZone,
    cursorActive: Boolean,
    chromeVisible: Boolean,
    immersive: Boolean,
    panelOpen: Boolean,
    chaptersOnDown: Boolean,
): ReaderKeyAction = when {
    !panelOpen -> readingKeyAction(key, zone, cursorActive, chromeVisible, immersive, chaptersOnDown)
    key == ReaderKey.Back -> ReaderKeyAction.ClosePanel
    else -> ReaderKeyAction.Pass
}

private fun readingKeyAction(
    key: ReaderKey,
    zone: ReaderZone,
    cursorActive: Boolean,
    chromeVisible: Boolean,
    immersive: Boolean,
    chaptersOnDown: Boolean,
): ReaderKeyAction = when (zone) {
    ReaderZone.Body -> when (key) {
        ReaderKey.Left, ReaderKey.PageUp -> ReaderKeyAction.PrevPage
        ReaderKey.Right, ReaderKey.PageDown, ReaderKey.Space -> ReaderKeyAction.NextPage
        ReaderKey.Up -> if (cursorActive) ReaderKeyAction.CursorUp else ReaderKeyAction.FocusTopBar
        ReaderKey.Down -> when {
            cursorActive -> ReaderKeyAction.CursorDown
            chaptersOnDown -> ReaderKeyAction.OpenChapters
            else -> ReaderKeyAction.FocusBottomBar
        }
        ReaderKey.Center -> ReaderKeyAction.CursorSelect
        ReaderKey.Back -> when {
            cursorActive -> ReaderKeyAction.CursorExit
            immersive && chromeVisible -> ReaderKeyAction.FocusBody
            else -> ReaderKeyAction.Pass
        }
    }
    // Chrome keeps its own traversal: only the key that leaves it, and BACK where BACK
    // means "dismiss the chrome" rather than "leave the reader", are taken here.
    ReaderZone.TopBar -> when (key) {
        ReaderKey.Down -> ReaderKeyAction.FocusBody
        ReaderKey.Back -> if (immersive) ReaderKeyAction.FocusBody else ReaderKeyAction.Pass
        else -> ReaderKeyAction.Pass
    }
    ReaderZone.BottomBar -> when (key) {
        ReaderKey.Up -> ReaderKeyAction.FocusBody
        ReaderKey.Back -> if (immersive) ReaderKeyAction.FocusBody else ReaderKeyAction.Pass
        else -> ReaderKeyAction.Pass
    }
}
