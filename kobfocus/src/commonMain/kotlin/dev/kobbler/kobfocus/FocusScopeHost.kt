package dev.kobbler.kobfocus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.collectLatest

/**
 * Owns the focus memory for every screen reachable from here.
 *
 * Hoist one of these above your navigation host; each destination then gets its own
 * [FocusScope] via [FocusScopeHost], and the memory survives that destination being
 * torn down and rebuilt.
 */
@Stable
class FocusScopeStore(private val capacity: Int = 32) {
    private val saved = mutableMapOf<Any, String?>()

    /**
     * Bounded: every distinct destination visited would otherwise be retained forever,
     * and detail destinations carry ids, so the set is unbounded in a long session.
     * Eviction is oldest-first, which is also least-recently-visited.
     */
    fun store(id: Any, key: String?) {
        saved.remove(id)
        saved[id] = key
        while (saved.size > capacity) saved.remove(saved.keys.first())
    }

    fun recall(id: Any): String? = saved[id]

    fun forget(id: Any) {
        saved.remove(id)
    }
}

@Composable
fun rememberFocusScopeStore(): FocusScopeStore = remember { FocusScopeStore() }

/**
 * The store every [FocusScopeHost] shares by default.
 *
 * Screens are hosted from several places in the tree — the tab router, the detail
 * router, the reader, the fullscreen player — and they must all record into one store
 * or returning to a screen hosted elsewhere finds no memory. Provide it once above
 * navigation.
 */
val LocalFocusScopeStore = staticCompositionLocalOf { FocusScopeStore() }

/**
 * Provides a [FocusScope] to [content], restoring the element that held focus the last
 * time this [id] was shown and saving the current one when it leaves.
 *
 * [restoreEnabled] exists for layers that are composed but not interactive — see
 * [focusInert]. A layer that cannot take focus must not try to grab it back.
 */
@Composable
fun FocusScopeHost(
    id: Any,
    store: FocusScopeStore = LocalFocusScopeStore.current,
    restoreEnabled: Boolean = true,
    fallback: (suspend FocusScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = remember(id) { FocusScope() }

    // No wrapper element here on purpose. An enclosing focusGroup() made 2D traversal
    // fail outright — moveFocus returned false from every element inside it, so the
    // D-pad went dead on detail screens. Entry focus comes from focusKey registration
    // instead.
    CompositionLocalProvider(LocalFocusScope provides scope) {
        content()
    }

    // Re-attempted as targets register rather than once on mount: a dashboard fetches
    // its rows, so at mount there is nothing to focus and the single attempt expired
    // against an empty registry, leaving the D-pad dead until the viewer bounced off the
    // sidebar and back. It stops at the first grant, so it never steals focus from a
    // viewer who has already moved.
    LaunchedEffect(id, restoreEnabled) {
        if (!restoreEnabled) return@LaunchedEffect
        var granted = false
        snapshotFlow { scope.registrations }.collectLatest {
            if (granted) return@collectLatest
            granted = when {
                scope.restore(store.recall(id)) -> true
                fallback != null -> { fallback(scope); true }
                else -> scope.focusFirst()
            }
        }
    }

    DisposableEffect(id) {
        onDispose { store.store(id, scope.save()) }
    }
}
