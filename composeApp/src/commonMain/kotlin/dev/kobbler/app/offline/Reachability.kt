package dev.kobbler.app.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether the server answers. Learned from the requests the app makes anyway; once one fails,
 * the server is probed on a timer until it answers again, so coming back is noticed without
 * the viewer having to do anything.
 */
class Reachability(private val scope: CoroutineScope, private val retryMs: Long = RetryMs) {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private var probe: (suspend () -> Boolean)? = null
    private var retrying: Job? = null

    fun attach(probe: suspend () -> Boolean) {
        this.probe = probe
    }

    fun reportSuccess() {
        _online.value = true
    }

    fun reportFailure() {
        if (retrying?.isActive == true) return
        retrying = scope.launch {
            if (check()) return@launch
            while (!check()) delay(retryMs)
        }
    }

    suspend fun check(): Boolean {
        val probe = probe ?: return _online.value
        val up = withTimeoutOrNull(ProbeTimeoutMs) { runCatching { probe() }.getOrDefault(false) } ?: false
        _online.value = up
        return up
    }

    private companion object {
        const val RetryMs = 15_000L
        const val ProbeTimeoutMs = 3_000L
    }
}
