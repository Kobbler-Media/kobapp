package dev.kobbler.app.ui.player

import dev.kobbler.app.runUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.Settings
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobplayer.AspectRatio
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The aspect-ratio memory is keyed to a shape, and it is applied per stream rather than
 * per title.
 *
 * One live session plays two of them: the tuner's broadcast — anamorphic on most SD and
 * some HD channels — and, behind the live edge, the server's square-pixel transcode. A
 * choice made for the first is not an answer about the second, and carrying it across the
 * rewind is what put bars round a picture that had been filling the screen.
 */
@OptIn(ExperimentalTestApi::class)
class LiveAspectSourceTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")
    private val playlist = "http://127.0.0.1:1/live/sessions/s-1/stream.m3u8"

    @Test
    fun `the buffer does not inherit the broadcast's shape`() = runUiTest {
        withSavedAspect(bucket = "4:3", ratio = AspectRatio.CROP) {
            val player = FakePlayer()
            val gps = GlobalPlayerState()
            setContent {
                Box(Modifier.size(960.dp, 540.dp)) {
                    KobappTheme(formFactor = FormFactor.TV) {
                        FullscreenVideoPlayer(
                            player = player,
                            state = gps,
                            api = api,
                            formFactor = FormFactor.TV,
                            onBack = {},
                            isFullscreen = true,
                            videoSurface = { },
                        )
                    }
                }
            }

            runBlocking {
                gps.load(
                    player = player, api = api,
                    type = "livetv", id = "chan-1",
                    streamUrl = "http://127.0.0.1:1/live/chan-1/stream.ts", newSessionId = null,
                    newTitle = "Channel One", newSubtitle = null,
                    newPosterUrl = null, newLogoUrl = null,
                    destination = null,
                )
            }
            gps.videoWidth = 1440
            gps.videoHeight = 1080
            gps.livePlaylistUrl = playlist
            waitForIdle()
            assertEquals(
                AspectRatio.CROP, player.aspectRatio.value,
                "the broadcast opened without the shape saved for its own geometry",
            )

            runBlocking { enterLiveTimeshift(gps, player, api, seekToSec = 60) }
            player.videoSize.value = 1280 to 720
            waitForIdle()

            assertEquals(
                AspectRatio.FIT, player.aspectRatio.value,
                "the transcode kept the broadcast's shape, which is what drew the bars",
            )
        }
    }

    private fun withSavedAspect(bucket: String, ratio: AspectRatio, body: () -> Unit) {
        val key = "aspect_ratio:$bucket"
        val previous = Settings.getString(key)
        Settings.putString(key, ratio.name)
        try {
            body()
        } finally {
            if (previous != null) Settings.putString(key, previous) else Settings.remove(key)
        }
    }
}
