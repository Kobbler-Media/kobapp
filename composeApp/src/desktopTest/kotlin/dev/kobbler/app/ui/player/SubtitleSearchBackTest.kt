package dev.kobbler.app.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.navigation.BackInterceptors
import dev.kobbler.app.ui.navigation.LocalBackInterceptors
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SubtitleSearchBackTest {

    private class Sheet {
        val backs = BackInterceptors()
        var dismissed = 0
    }

    private fun DesktopComposeUiTest.showSheet(formFactor: FormFactor): Sheet {
        val sheet = Sheet()
        setContent {
            CompositionLocalProvider(LocalBackInterceptors provides sheet.backs) {
                KobappTheme(formFactor = formFactor) {
                    SubtitleSearchOverlay(
                        api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                        mediaType = "movie",
                        mediaId = "m1",
                        sessionId = "s1",
                        onDismiss = { sheet.dismissed++ },
                        onSubtitleAdded = {},
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithText("Find subtitles").assertExists()
        return sheet
    }

    @Test
    fun `phone back is the app's bare arrow`() = runUiTest(900, 412) {
        val sheet = showSheet(FormFactor.Phone)
        onNodeWithContentDescription("Back", useUnmergedTree = true)
            .assertWidthIsEqualTo(22.dp)
            .assertHeightIsEqualTo(22.dp)
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()
        assertEquals(1, sheet.dismissed)
    }

    @Test
    fun `on a tv the remote's back closes the sheet, which has no button`() = runUiTest(1280, 720) {
        val sheet = showSheet(FormFactor.TV)
        assertEquals(0, onAllNodesWithContentDescription("Back").fetchSemanticsNodes().size)
        assertTrue(sheet.backs.consume())
        assertEquals(1, sheet.dismissed)
    }
}
