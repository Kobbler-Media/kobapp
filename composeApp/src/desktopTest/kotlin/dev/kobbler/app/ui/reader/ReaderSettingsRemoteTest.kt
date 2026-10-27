package dev.kobbler.app.ui.reader

import dev.kobbler.app.runUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.TtsVoiceDto
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** The reader's settings are driven from a remote: focus lands in them and the arrows change them. */
@OptIn(ExperimentalTestApi::class)
class ReaderSettingsRemoteTest {

    private val slider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun `opening settings focuses text size, and right makes it bigger`() = runUiTest(width = 960, height = 540) {
        var fontSize by mutableFloatStateOf(18f)
        setContent {
            val scope = rememberCoroutineScope()
            KobappTheme(formFactor = FormFactor.TV) {
                ReaderSettingsPanel(
                    themeMode = ReaderThemeMode.Dark,
                    fontSize = fontSize,
                    lineHeightMultiplier = 1.6f,
                    horizontalPadding = 20,
                    readerFontFamily = ReaderFontFamily.entries.first(),
                    pageFit = ReaderPageFit.Spread,
                    onThemeChange = {},
                    onFontSizeChange = { fontSize = it },
                    onLineHeightChange = {},
                    onPaddingChange = {},
                    onFontFamilyChange = {},
                    onPageFitChange = {},
                    brightness = 1f,
                    onBrightnessChange = {},
                    ttsController = TtsController(KobblerApi(HttpClient(), "http://127.0.0.1:1", "t"), scope),
                    ttsVoices = listOf(TtsVoiceDto(id = "v1", name = "Narrator")),
                    onDismiss = {},
                    theme = ReaderTheme.Dark,
                )
            }
        }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        assertTrue(onAllNodes(isFocused() and slider).fetchSemanticsNodes().isNotEmpty(), "nothing in the panel took focus")
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertTrue(fontSize > 18f, "RIGHT left the text size at $fontSize")
        assertTrue(onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty(), "a panel taller than a TV cannot be scrolled to its voice settings")
        val before = fontSize
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(fontSize == before, "DOWN changed the text size to $fontSize instead of moving on")
        assertTrue(onAllNodes(isFocused() and slider).fetchSemanticsNodes().isEmpty(), "DOWN stayed on the text size")
    }
}
