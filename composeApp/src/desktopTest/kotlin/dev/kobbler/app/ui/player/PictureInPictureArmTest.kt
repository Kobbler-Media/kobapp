package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.Settings
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Armed is what lets leaving the app by Home shrink the player into a system window. */
@OptIn(ExperimentalTestApi::class)
class PictureInPictureArmTest {

    private val api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token")

    private fun withSetting(stored: String?, block: () -> Unit) {
        val previous = Settings.getString(KEY_PICTURE_IN_PICTURE)
        try {
            if (stored == null) Settings.remove(KEY_PICTURE_IN_PICTURE) else Settings.putString(KEY_PICTURE_IN_PICTURE, stored)
            block()
        } finally {
            if (previous != null) Settings.putString(KEY_PICTURE_IN_PICTURE, previous) else Settings.remove(KEY_PICTURE_IN_PICTURE)
        }
    }

    /** Plays a film fullscreen, then hands back whether PiP was armed, and whether it still was after BACK. */
    private fun armedPlayingThenAfterBack(formFactor: FormFactor, stored: String?, playing: Boolean = true): Pair<Boolean, Boolean> {
        var result = false to false
        withSetting(stored) {
            runUiTest {
                val player = FakePlayer().apply { durationMs.value = 600_000; isPlaying.value = playing }
                val state = GlobalPlayerState().apply {
                    mediaType = "movie"; mediaId = "m"; sessionId = "s"; title = "Film"; isFullscreen = true
                }
                setContent {
                    FocusScopeHost(id = "test", store = FocusScopeStore()) {
                        KobappTheme(formFactor = formFactor) {
                            if (state.isFullscreen) {
                                FullscreenVideoPlayer(
                                    player = player, state = state, api = api, formFactor = formFactor,
                                    onBack = { state.back(player, api, formFactor, CoroutineScope(Dispatchers.Default)) },
                                    isFullscreen = true, videoSurface = { },
                                )
                            }
                        }
                    }
                }
                waitForIdle()
                val beforeBack = PictureInPicture.armed.value
                runOnUiThread { state.back(player, api, formFactor, CoroutineScope(Dispatchers.Default)) }
                val rightAfterBack = PictureInPicture.armed.value
                waitForIdle()
                assertFalse(PictureInPicture.armed.value, "PiP re-armed once the player settled after BACK")
                result = beforeBack to rightAfterBack
            }
        }
        return result
    }

    @Test
    fun `a phone arms by default and BACK disarms before anything else happens`() {
        assertEquals(true to false, armedPlayingThenAfterBack(FormFactor.Phone, stored = null))
    }

    @Test
    fun `turning the setting off keeps a phone disarmed`() {
        assertEquals(false to false, armedPlayingThenAfterBack(FormFactor.Phone, stored = "false"))
    }

    @Test
    fun `a tv and a desktop stay disarmed until the setting turns it on`() {
        assertEquals(false to false, armedPlayingThenAfterBack(FormFactor.TV, stored = null))
        assertEquals(false to false, armedPlayingThenAfterBack(FormFactor.Desktop, stored = null))
        assertEquals(true to false, armedPlayingThenAfterBack(FormFactor.TV, stored = "true"))
    }

    @Test
    fun `a paused player is never armed`() {
        assertEquals(false to false, armedPlayingThenAfterBack(FormFactor.Phone, stored = null, playing = false))
    }
}
