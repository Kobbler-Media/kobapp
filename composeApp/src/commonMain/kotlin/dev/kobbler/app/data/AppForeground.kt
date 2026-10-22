package dev.kobbler.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether any of the app is on screen; fed from the root composable's lifecycle. */
object AppForeground {
    private val inFront = MutableStateFlow(true)
    val state: StateFlow<Boolean> = inFront

    fun set(value: Boolean) {
        inFront.value = value
    }
}
