package dev.kobbler.app.ui.reader

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappDimens
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** A TV crops its edges, and a focused button's ring is drawn outside the button. */
@OptIn(ExperimentalTestApi::class)
class ReaderTopBarTvTest {

    private val width = 960
    private val dimens = KobappDimens()

    @Test
    fun `every top bar button sits inside the TV safe area with room for its focus ring`() = runUiTest(width = width, height = 540) {
        setContent {
            KobappTheme(formFactor = FormFactor.TV) {
                ReaderTopBar(
                    title = "Chapter One",
                    onBack = {}, onToc = {}, onSettings = {}, onTts = {}, onListenAlong = {}, onSearch = {}, onBookmark = {},
                    ttsState = TtsState.IDLE,
                    syncMode = SyncMode.IDLE,
                    hasAudiobook = true,
                    theme = ReaderTheme.Dark,
                    entryFocus = remember { FocusRequester() },
                )
            }
        }
        for (button in listOf("Listen Along", "Read Aloud", "Search", "Bookmark", "Contents", "Settings")) {
            val bounds = onNodeWithContentDescription(button).getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= dimens.focusBleed, "$button's ring runs off the top: top at ${bounds.top}")
            assertTrue(bounds.right <= width.dp - dimens.pageGutterTv, "$button sits in the overscan: right at ${bounds.right} of ${width}dp")
        }
    }
}
