package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CardTitleSizeTest {

    @Test
    fun `continue cards title at the size of the poster rails beside them`() {
        for (formFactor in listOf(FormFactor.Phone, FormFactor.Desktop, FormFactor.TV)) {
            runDesktopComposeUiTest(width = 1200, height = 800) {
                setContent {
                    KobappTheme(formFactor = formFactor) {
                        Row {
                            WideCard(title = "Wide", subtitle = "Wide sub", imageUrl = "", progress = 0.4f)
                            PosterCard(title = "Poster", imageUrl = "", subtitle = "Poster sub")
                        }
                    }
                }
                assertEquals(fontSize("Poster"), fontSize("Wide"), "title on $formFactor")
                assertEquals(fontSize("Poster sub"), fontSize("Wide sub"), "subtitle on $formFactor")
            }
        }
    }
}
