package dev.kobbler.app.ui.player

import kotlinx.coroutines.withTimeoutOrNull

/**
 * The last save before the process goes. Quitting the desktop app tears the composition
 * down with the JVM right behind it, so nothing launched from a disposal gets to finish.
 */
object PlaybackExit {
    private var save: (suspend () -> Unit)? = null

    fun register(save: suspend () -> Unit) {
        this.save = save
    }

    fun unregister(save: suspend () -> Unit) {
        if (this.save === save) this.save = null
    }

    suspend fun flush() {
        withTimeoutOrNull(FLUSH_TIMEOUT_MS) { runCatching { save?.invoke() } }
    }

    private const val FLUSH_TIMEOUT_MS = 2_000L
}
