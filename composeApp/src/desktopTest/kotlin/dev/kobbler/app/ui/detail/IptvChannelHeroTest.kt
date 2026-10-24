package dev.kobbler.app.ui.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.kobfocus.FocusScopeHost
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class IptvChannelHeroTest {

    /** The phone hero stacks logo, name and actions; a fixed height squeezed the actions to nothing. */
    @Test
    fun `a phone shows the watch and favourite actions in full`() {
        for (name in listOf("BBC One", "The Extremely Long Named Sports Channel Network HD")) {
            runUiTest(width = 400, height = 800) {
                setContent {
                    KobappTheme(formFactor = FormFactor.Phone) {
                        FocusScopeHost(id = "iptv-channel") {
                            IptvChannelDetailScreen(
                                channel = IptvChannel(id = "i1", name = name, iconUrl = null, category = "Sport", language = null, country = "UK"),
                                epg = emptyList(),
                                api = KobblerApi(HttpClient(), "http://127.0.0.1:9", "token"),
                                player = null,
                                gps = GlobalPlayerState(),
                                onBack = {},
                                formFactor = FormFactor.Phone,
                            )
                        }
                    }
                }
                waitForIdle()
                val watch = onNodeWithText("Watch live").getUnclippedBoundsInRoot()
                val favourite = onNodeWithContentDescription("Favourite", substring = true).getUnclippedBoundsInRoot()
                assertTrue(watch.height >= 36.dp, "$name: watch is ${watch.height} tall")
                assertTrue(favourite.height >= 24.dp, "$name: favourite is ${favourite.height} tall")
            }
        }
    }
}
