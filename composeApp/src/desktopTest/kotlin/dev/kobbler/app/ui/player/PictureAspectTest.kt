package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.PictureArea
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.Settings
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobplayer.AspectRatio
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A scope film letterboxed inside 1920x1080 and a full-frame race are the same frame; the
 * choice saved for one shape must follow the picture, not the frame round it.
 */
@OptIn(ExperimentalTestApi::class)
class PictureAspectTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    @AfterTest
    fun clear() = listOf("aspect_ratio:wide", "aspect_ratio:16:9").forEach(Settings::remove)

    private fun opened(picture: PictureArea?): AspectRatio {
        Settings.putString("aspect_ratio:wide", AspectRatio.CROP.name)
        Settings.putString("aspect_ratio:16:9", AspectRatio.FIT.name)
        val player = FakePlayer()
        val gps = GlobalPlayerState()
        runUiTest {
            setContent {
                Box(Modifier.size(960.dp, 540.dp)) {
                    KobappTheme(formFactor = FormFactor.Phone) {
                        FullscreenVideoPlayer(player = player, state = gps, api = api, formFactor = FormFactor.Phone, onBack = {}, isFullscreen = true, videoSurface = { })
                    }
                }
            }
            runBlocking {
                gps.load(
                    player = player, api = api, type = "movie", id = "m1",
                    streamUrl = "http://127.0.0.1:1/m1.mkv", newSessionId = null,
                    newTitle = "Film", newSubtitle = null, newPosterUrl = null, newLogoUrl = null, destination = null,
                )
            }
            gps.picture = picture
            gps.videoWidth = 1920
            gps.videoHeight = 1080
            waitForIdle()
        }
        return player.aspectRatio.value
    }

    @Test
    fun `a letterboxed film takes the choice saved for wide pictures`() {
        assertEquals(AspectRatio.CROP, opened(PictureArea(x = 0.0, y = 0.122, width = 1.0, height = 0.756)))
    }

    @Test
    fun `a full frame takes the choice saved for its own shape`() {
        assertEquals(AspectRatio.FIT, opened(null))
    }
}
