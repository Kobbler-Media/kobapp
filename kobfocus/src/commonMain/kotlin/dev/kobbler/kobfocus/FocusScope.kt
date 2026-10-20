package dev.kobbler.kobfocus

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Focus memory for one screen or layer. Records which keyed element last held focus
 * and can hand it back after the screen is recomposed from scratch.
 */
@Stable
class FocusScope {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private val preferred = mutableSetOf<String>()
    private val chrome = mutableSetOf<String>()
    private var lastFocusedKey: String? = null

    // What holds focus *now*, as opposed to what held it last. The two diverge whenever
    // focus is taken away without being handed on — a layer going [focusInert] behind an
    // overlay being the case that matters — and only the live one can answer "did that
    // request land". Asking the memory instead made the control that opened the overlay
    // answer yes for itself, so entry focus settled on an element nothing could reach
    // and the panel came up with a dead D-pad.
    private var focusedKey: String? = null

    /** What holds focus now. Null means the D-pad has nothing to move from. */
    val focused: String? get() = focusedKey

    // Observable so a host can re-attempt entry focus as targets appear. A screen whose
    // content comes off the network registers its first one well after the host's effect
    // starts, and a fixed frame budget simply expired against it.
    private val registrationCount = mutableIntStateOf(0)
    val registrations: Int get() = registrationCount.intValue

    fun register(
        key: String,
        requester: FocusRequester,
        preferredEntry: Boolean = false,
        entryEligible: Boolean = true,
    ) {
        requesters[key] = requester
        if (preferredEntry) preferred += key else preferred -= key
        if (entryEligible) chrome -= key else chrome += key
        registrationCount.intValue++
    }

    /**
     * Guarded against the reorder case: a departing node must not evict the entry a
     * newly-attached node just claimed for the same key.
     */
    fun unregister(key: String, requester: FocusRequester) {
        if (requesters[key] === requester) {
            requesters.remove(key)
            preferred -= key
            chrome -= key
        }
    }

    fun onFocused(key: String) {
        lastFocusedKey = key
        focusedKey = key
    }

    fun onBlurred(key: String) {
        if (focusedKey == key) focusedKey = null
    }

    fun save(): String? = lastFocusedKey

    /**
     * Restores focus to [key], waiting for the target to appear.
     *
     * A restore is issued before layout has placed the node it names — lazy rails in
     * particular compose their items over several frames — so this retries across up to
     * [maxFrames] frames rather than failing on the first attempt.
     * Returns true once focus is taken.
     */
    suspend fun restore(key: String?, maxFrames: Int = 8): Boolean {
        if (key == null) return false
        repeat(maxFrames) {
            val requester = requesters[key]
            if (requester != null && requester.takesFocus(key)) return true
            withFrameNanos { }
        }
        return false
    }

    /**
     * A refusal costs nothing, and only a grant waits a frame to be confirmed against what
     * the key's own node reports holding. A touchscreen refuses every clickable, so a frame
     * spent per refusal kept a page of episode rows waking the UI thread at every vsync for
     * minutes; and a grant is not trusted alone, since a target behind an inert layer
     * reports one and then holds nothing.
     */
    private suspend fun FocusRequester.takesFocus(key: String): Boolean {
        if (!runCatching { requestFocus() }.getOrDefault(false)) return false
        withFrameNanos { }
        return focusedKey == key
    }

    /**
     * Focuses the first target that will take it, in registration (composition) order.
     *
     * The deterministic escape from a focus black hole: pinning entry focus to one
     * named element breaks silently the moment that element is conditional, leaving
     * the screen with nothing focused and the D-pad dead.
     *
     * Three passes: the opted-in entry, then content, then chrome. A browse screen's
     * alphabet bar and filter pills come from their own requests and register whenever
     * those land, so a single composition-order pass hands entry focus to whichever race
     * finished first — the letter "A", or a filter pill, rather than the row the viewer
     * came to look at. Chrome is still tried last so a screen that is nothing but chrome
     * does not end up with a dead D-pad.
     *
     * Only the content and chrome passes are held for the full [maxFrames], and a pass
     * with no candidates while other targets are already registered gives up at once
     * rather than spinning. At mount that is almost always the entry pass: nothing has
     * opted in, and holding it open buys a third of a second of dead D-pad.
     */
    suspend fun focusFirst(maxFrames: Int = 24): Boolean {
        if (focusFrom({ preferred.toList() }, PREFERRED_ENTRY_FRAMES)) return true
        if (focusFrom({ requesters.keys.filter { it !in chrome } }, maxFrames)) return true
        return focusFrom({ requesters.keys.toList() }, maxFrames)
    }

    private suspend fun focusFrom(keys: () -> List<String>, maxFrames: Int): Boolean {
        repeat(maxFrames) {
            val candidates = keys()
            // Nothing this pass can ever hit, but targets exist — waiting only delays the
            // pass that can. An empty registry instead means composition has not produced
            // its targets yet (lazy rails register during measure), so that keeps waiting.
            if (candidates.isEmpty() && requesters.isNotEmpty()) return false
            for (key in candidates) {
                if (requesters[key]?.takesFocus(key) == true) return true
            }
            withFrameNanos { }
        }
        return false
    }

    private companion object {
        const val PREFERRED_ENTRY_FRAMES = 8
    }
}

/**
 * Detached scope used outside any [FocusScopeHost].
 *
 * [focusKey] has to be safe to apply anywhere — pre-auth screens and the fullscreen
 * player live outside the navigation host, and throwing there would turn a missing
 * host into a crash. Keys registered here simply have no memory.
 */
val LocalFocusScope = staticCompositionLocalOf { FocusScope() }
