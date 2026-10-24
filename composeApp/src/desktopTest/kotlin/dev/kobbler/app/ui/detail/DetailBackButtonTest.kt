package dev.kobbler.app.ui.detail

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test

/** The circle is smaller than a text button's padding, which used to squeeze the arrow to a sliver. */
@OptIn(ExperimentalTestApi::class)
class DetailBackButtonTest {

    @Test
    fun `the back arrow keeps its size inside the circle`() = runUiTest {
        setContent {
            KobappTheme(formFactor = FormFactor.Phone) {
                DetailBackButton(onBack = {}, formFactor = FormFactor.Phone)
            }
        }
        onNodeWithContentDescription("Back", useUnmergedTree = true)
            .assertWidthIsEqualTo(20.dp)
            .assertHeightIsEqualTo(20.dp)
    }
}
