package dev.kobbler.kobplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The surface a native player draws into, and the means to trade it for a new one.
 *
 * A player abandoned mid-call keeps drawing into the surface it was given, and no detach
 * reaches it while it is stuck: the only way to take that surface from it is to destroy
 * it. [epoch] tells the view to build its replacement.
 */
internal class SurfaceSlot<S : Any> {
    private val surface = MutableStateFlow<S?>(null)
    private val _epoch = MutableStateFlow(0)

    val current: S? get() = surface.value
    val ready: StateFlow<Boolean> = surface.mapState { it != null }
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    fun created(new: S) {
        surface.value = new
    }

    /** False for a surface no longer in use: one given up by [renew] going away changes nothing. */
    fun destroyed(gone: S): Boolean {
        if (surface.value !== gone) return false
        surface.value = null
        return true
    }

    /** Gives up the surface in use and waits for its replacement; null when there was none to give up, or it never came. */
    suspend fun renew(timeoutMs: Long): S? {
        surface.value ?: return null
        surface.value = null
        _epoch.value++
        return withTimeoutOrNull(timeoutMs) { surface.filterNotNull().first() }
    }
}
