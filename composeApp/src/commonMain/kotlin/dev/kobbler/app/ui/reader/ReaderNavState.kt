package dev.kobbler.app.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.kobbler.app.ui.navigation.InterceptBack
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.delay

private const val CHROME_IDLE_MS = 3000L
private const val FOCUS_RETRY_FRAMES = 8

@Stable
internal class ReaderNavState(val keyNav: Boolean, val immersive: Boolean) {
    val rootFocus = FocusRequester()
    val topBarFocus = FocusRequester()
    val bottomBarFocus = FocusRequester()

    var chromeVisible by mutableStateOf(!immersive)
        private set
    var zone by mutableStateOf(ReaderZone.Body)
    var activityTick by mutableIntStateOf(0)
        private set
    var pendingFocus by mutableStateOf<ReaderZone?>(null)
        private set

    fun poke() {
        activityTick++
    }

    fun showChrome(target: ReaderZone) {
        chromeVisible = true
        zone = target
        pendingFocus = target
        activityTick++
    }

    // Zone is set here rather than left to the focus callbacks: hiding chrome disposes the
    // node that holds focus, and until the root takes it back a key would still be judged
    // against the departed bar.
    fun returnToBody() {
        if (immersive) chromeVisible = false
        zone = ReaderZone.Body
        pendingFocus = ReaderZone.Body
    }

    fun focusConsumed() {
        pendingFocus = null
    }
}

/**
 * [holdChrome] suspends the idle timer for anything that owns focus above the bars — a
 * panel opened from the top bar would otherwise have focus yanked back to the page.
 */
@Composable
internal fun rememberReaderNavState(
    keyNav: Boolean,
    immersive: Boolean,
    holdChrome: Boolean,
): ReaderNavState {
    val state = remember(keyNav, immersive) { ReaderNavState(keyNav, immersive) }

    LaunchedEffect(state.activityTick, state.chromeVisible, holdChrome) {
        if (!state.immersive || !state.chromeVisible || holdChrome) return@LaunchedEffect
        delay(CHROME_IDLE_MS)
        state.returnToBody()
    }

    LaunchedEffect(state.pendingFocus) {
        val target = state.pendingFocus ?: return@LaunchedEffect
        val requester = when (target) {
            ReaderZone.Body -> state.rootFocus
            ReaderZone.TopBar -> state.topBarFocus
            ReaderZone.BottomBar -> state.bottomBarFocus
        }
        // The bar is composed by the frame after chrome is revealed, so the first request
        // lands on an unattached requester.
        repeat(FOCUS_RETRY_FRAMES) {
            if (runCatching { requester.requestFocus() }.isSuccess) {
                state.focusConsumed()
                return@LaunchedEffect
            }
            withFrameNanos {}
        }
        runCatching { state.rootFocus.requestFocus() }
        state.focusConsumed()
    }

    return state
}

internal fun Modifier.readerZone(state: ReaderNavState, zone: ReaderZone): Modifier =
    onFocusChanged { if (it.hasFocus) state.zone = zone }

/**
 * The reader root: an invisible focus holder that owns the D-pad while reading.
 *
 * Compose only delivers keys into the focused subtree, and reading content is not a focus
 * target, so something has to hold focus for the page keys to arrive at all. This is a
 * preview handler, which runs root-first — every key it does not act on must be left
 * unconsumed or the focused chrome element never sees it.
 */
@Composable
internal fun Modifier.readerKeyNav(
    state: ReaderNavState,
    panelOpen: Boolean,
    cursorActive: Boolean,
    chaptersOnDown: Boolean,
    onAction: (ReaderKeyAction) -> Boolean,
): Modifier {
    if (!state.keyNav) return this
    val act by rememberUpdatedState(onAction)
    val panel by rememberUpdatedState(panelOpen)
    val cursorOn by rememberUpdatedState(cursorActive)
    val chapters by rememberUpdatedState(chaptersOnDown)
    return this
        .focusKey("reader", focusRequester = state.rootFocus, preferredEntry = true)
        .onFocusChanged { if (it.isFocused) state.zone = ReaderZone.Body }
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val key = event.key.toReaderKey() ?: return@onPreviewKeyEvent false
            if (state.chromeVisible && !panel) state.poke()
            state.perform(readerKeyAction(key, state.zone, cursorOn, state.chromeVisible, state.immersive, panel, chapters), act)
        }
}

/**
 * BACK as the platform delivers it rather than as a key: Android hands it to
 * OnBackPressedDispatcher whenever the key never reaches Compose (predictive back, or a
 * text field holding focus), and unclaimed there it leaves the reader.
 */
@Composable
internal fun ReaderBackHandler(
    state: ReaderNavState,
    panelOpen: Boolean,
    cursorActive: Boolean,
    onAction: (ReaderKeyAction) -> Boolean,
) {
    val action = readerKeyAction(
        ReaderKey.Back, state.zone, cursorActive, state.chromeVisible, state.immersive, panelOpen, chaptersOnDown = false,
    )
    InterceptBack(enabled = action != ReaderKeyAction.Pass) { state.perform(action, onAction) }
}

private fun ReaderNavState.perform(action: ReaderKeyAction, act: (ReaderKeyAction) -> Boolean): Boolean =
    when (action) {
        ReaderKeyAction.Pass -> false
        ReaderKeyAction.FocusTopBar -> { showChrome(ReaderZone.TopBar); true }
        ReaderKeyAction.FocusBottomBar -> { showChrome(ReaderZone.BottomBar); true }
        ReaderKeyAction.FocusBody -> { returnToBody(); true }
        else -> act(action)
    }

private fun Key.toReaderKey(): ReaderKey? = when (this) {
    Key.DirectionLeft -> ReaderKey.Left
    Key.DirectionRight -> ReaderKey.Right
    Key.DirectionUp -> ReaderKey.Up
    Key.DirectionDown -> ReaderKey.Down
    Key.DirectionCenter, Key.Enter -> ReaderKey.Center
    Key.PageUp -> ReaderKey.PageUp
    Key.PageDown -> ReaderKey.PageDown
    Key.Spacebar -> ReaderKey.Space
    Key.Back, Key.Escape -> ReaderKey.Back
    else -> null
}
