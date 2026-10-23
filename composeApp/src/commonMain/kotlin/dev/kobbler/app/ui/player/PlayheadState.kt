package dev.kobbler.app.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * What the playhead means to one reader, recomputed off the main thread and published only
 * when it changes. A derivedStateOf over the collected position looks equivalent but still
 * invalidates its readers on every tick, and each one costs a vsync and a recompose pass.
 */
@Composable
fun <T> StateFlow<Long>.collectAsPlayheadState(vararg keys: Any?, transform: (Long) -> T): State<T> {
    val initial = remember(this, *keys) { transform(value) }
    return produceState(initial, this, *keys) {
        map(transform).distinctUntilChanged().flowOn(Dispatchers.Default).collect { value = it }
    }
}
