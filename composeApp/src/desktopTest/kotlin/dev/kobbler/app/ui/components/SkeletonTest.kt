package dev.kobbler.app.ui.components

import dev.kobbler.app.runUiTest
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.dashboard.home.HomeSkeleton
import dev.kobbler.app.ui.detail.DetailSkeleton
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SkeletonTest {

    private val skeletons: List<Pair<String, @Composable () -> Unit>> = listOf(
        "home" to { HomeSkeleton() },
        "browse" to { BrowseSkeleton() },
        "detail" to { DetailSkeleton() },
        "list" to { ListSkeleton() },
        "grid" to { GridSkeleton() },
        "channel" to { ChannelSkeleton() },
        "player" to { PlayerSkeleton() },
        "reader" to { ReaderSkeleton() },
        "comic" to { ComicSkeleton() },
    )

    @Test
    fun `loading shows the skeleton the screen asked for, on every form factor`() {
        for ((formFactor, size) in listOf(FormFactor.Phone to (412 to 900), FormFactor.TV to (960 to 540), FormFactor.Desktop to (1280 to 800))) {
            for ((name, skeleton) in skeletons) {
                runUiTest(width = size.first, height = size.second) {
                    setContent {
                        KobappTheme(formFactor = formFactor) {
                            AsyncContent<Unit>(Async.Loading, skeleton = skeleton) {}
                        }
                    }
                    onNodeWithTag("$SkeletonTag.$name").assertIsDisplayed()
                }
            }
        }
    }
}
