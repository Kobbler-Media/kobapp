package dev.kobbler.app.ui.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Long enough to ride out a rebuffer or a seek without the display flickering towards sleep.
val SCREEN_HOLD_GRACE = 30.seconds

suspend fun holdScreenWhilePlaying(
    isPlaying: Flow<Boolean>,
    grace: Duration = SCREEN_HOLD_GRACE,
    setKeepScreenOn: (Boolean) -> Unit,
) {
    isPlaying.collectLatest { playing ->
        setKeepScreenOn(true)
        if (!playing) {
            delay(grace)
            setKeepScreenOn(false)
        }
    }
}
