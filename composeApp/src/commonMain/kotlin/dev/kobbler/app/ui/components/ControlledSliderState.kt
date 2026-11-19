package dev.kobbler.app.ui.components

import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// The value is owned by the caller (player position, a setting), so the state follows it on
// every recomposition instead of keeping its own like rememberSliderState does.
@Composable
fun controlledSliderState(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
): SliderState =
    remember(steps, valueRange) { SliderState(value, steps, valueRange) }.also { it.value = value }
