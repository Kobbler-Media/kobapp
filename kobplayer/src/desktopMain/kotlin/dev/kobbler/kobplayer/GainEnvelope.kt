package dev.kobbler.kobplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class GainEnvelope(
    private val scope: CoroutineScope,
    private val apply: (Int) -> Unit,
) {
    private val _level = MutableStateFlow(0)
    val level: StateFlow<Int> = _level.asStateFlow()

    @Volatile
    private var ramp: Job? = null

    val isRamping: Boolean get() = ramp?.isActive == true

    fun set(value: Int) {
        ramp?.cancel()
        write(value)
    }

    fun reapply() = apply(_level.value)

    fun rampTo(durationMs: Long, target: () -> Int, then: () -> Unit = {}) {
        ramp?.cancel()
        val from = _level.value
        val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
        ramp = scope.launch {
            for (i in 1..steps) {
                write(from + (target() - from) * i / steps)
                if (i < steps) delay(STEP_MS)
            }
            then()
        }
    }

    private fun write(value: Int) {
        _level.value = value
        apply(value)
    }

    private companion object {
        const val STEP_MS = 20L
    }
}
