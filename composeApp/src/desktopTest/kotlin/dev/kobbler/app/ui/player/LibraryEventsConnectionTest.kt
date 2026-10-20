package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Skip segments and cross-device handoff both listen to the library stream; a player that
 * opened a connection for each held two long-lived sockets and parsed every frame twice.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryEventsConnectionTest {

    private val skipFrame = "event: skip_segments_ready\n" +
        "data: {\"kind\":\"skip_segments_ready\",\"file_id\":\"file-1\"," +
        "\"segments\":[{\"segment_type\":\"intro\",\"start_seconds\":10.0,\"end_seconds\":70.0}]}\n\n"

    @Test
    fun `one connection carries skip segments and the handoff check`() {
        val connections = AtomicInteger()
        val handoffAsks = AtomicInteger()
        val state = GlobalPlayerState().apply {
            mediaType = "movie"
            mediaId = "mov-1"
            sessionId = "sess-1"
            fileId = "file-1"
            title = "Project Hail Mary"
        }
        StubServer(
            mapOf(
                "/api/v1/admin/events" to { _ -> connections.incrementAndGet(); skipFrame },
                "/api/v1/admin/playback/handoff" to { _ -> handoffAsks.incrementAndGet(); "{}" },
            ),
        ).use { server ->
            runComposeUiTest {
                val player = FakePlayer().apply { durationMs.value = 9_444_000 }
                setContent {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        FocusScopeHost(id = "test", store = FocusScopeStore()) {
                            KobappTheme(formFactor = FormFactor.TV) {
                                FullscreenVideoPlayer(
                                    player = player,
                                    state = state,
                                    api = KobblerApi(HttpClient(), server.url, "token"),
                                    formFactor = FormFactor.TV,
                                    onBack = {},
                                    isFullscreen = true,
                                    videoSurface = { },
                                    onOpenGuide = {},
                                )
                            }
                        }
                    }
                }
                waitForIdle()
                Thread.sleep(1_500)
                waitForIdle()
            }
        }
        assertEquals(1, connections.get(), "the player opened more than one library stream")
        assertTrue(state.skipSegments.isNotEmpty(), "skip segments pushed for this file were dropped")
        assertTrue(handoffAsks.get() >= 1, "the handoff baseline was never asked for")
    }
}
