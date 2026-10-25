package dev.kobbler.app.ui.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.kobplayer.ui.PlayerChrome

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.revealControls(chrome: PlayerChrome) {
    when (chrome) {
        PlayerChrome.Tv -> onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        PlayerChrome.Touch -> onRoot().performTouchInput { click(center) }
        PlayerChrome.Pointer -> onRoot().performMouseInput { moveTo(topLeft); moveTo(center) }
    }
    // A tap only lands once the double-tap window has passed, and a remote's entry focus
    // takes several frames to settle on the bar.
    mainClock.advanceTimeBy(500)
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.revealControls(formFactor: FormFactor) = revealControls(
    when (formFactor) {
        FormFactor.Phone -> PlayerChrome.Touch
        FormFactor.Desktop -> PlayerChrome.Pointer
        FormFactor.TV -> PlayerChrome.Tv
    },
)
