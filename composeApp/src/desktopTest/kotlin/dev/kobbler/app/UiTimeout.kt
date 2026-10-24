package dev.kobbler.app

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.time.Duration.Companion.minutes

// Only a wait that is going to fail runs this long. A tight one fails healthy tests whenever
// the machine is busy with another build, because the first frame and the first stub
// response are wall-clock work that load stretches.
const val UiTimeoutMs = 60_000L

@OptIn(ExperimentalTestApi::class)
fun runUiTest(width: Int = 1024, height: Int = 768, block: suspend DesktopComposeUiTest.() -> Unit) =
    runDesktopComposeUiTest(width = width, height = height, testTimeout = 5.minutes, block = block)
