package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.runUiTest
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A headset's double tap, the notification and the lock screen pause through the media
 * session, not the bar: the picture stopped with nothing on it to say so or to resume from.
 */
@OptIn(ExperimentalTestApi::class)
class OutsidePauseControlsTest {

    private val formFactors = listOf(FormFactor.Phone, FormFactor.TV)

    @Test
    fun `a pause from outside the controls brings them up`() =
        assertEquals(emptyList(), formFactors.mapNotNull { formFactor ->
            playing(formFactor) { player ->
                player.pause()
                waitForIdle()
                "$formFactor: controls stayed hidden".takeUnless { titleShown() }
            }
        })

    @Test
    fun `buffering is not a pause`() =
        assertEquals(emptyList(), formFactors.mapNotNull { formFactor ->
            playing(formFactor) { player ->
                player.isPlaying.value = false
                player.state.value = PlayerState.BUFFERING
                waitForIdle()
                "$formFactor: controls came up".takeIf { titleShown() }
            }
        })

    private fun <T> playing(formFactor: FormFactor, test: ComposeUiTest.(FakePlayer) -> T): T {
        var result: Result<T>? = null
        runUiTest {
            val player = FakePlayer().apply {
                durationMs.value = 1_800_000
                currentMs.value = 600_000
                isPlaying.value = true
                state.value = PlayerState.PLAYING
            }
            val state = GlobalPlayerState().apply {
                mediaType = "episode"
                mediaId = "ep-1"
                sessionId = "sess-1"
                title = "Slough House"
                isFullscreen = true
            }
            setContent {
                Box(Modifier.size(960.dp, 540.dp)) {
                    FocusScopeHost(id = "test", store = FocusScopeStore()) {
                        KobappTheme(formFactor = formFactor) {
                            PersistentVideoSurface(
                                player = player,
                                state = state,
                                api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                                formFactor = formFactor,
                                onToggleFullscreen = null,
                                isFullscreen = true,
                                scope = CoroutineScope(Dispatchers.Unconfined),
                                videoSurface = { },
                            )
                        }
                    }
                }
            }
            waitForIdle()
            check(!titleShown()) { "$formFactor: controls shown before the pause" }
            result = runCatching { test(player) }
        }
        return result!!.getOrThrow()
    }

    private fun ComposeUiTest.titleShown() =
        onAllNodesWithText("Slough House").fetchSemanticsNodes().isNotEmpty()
}
