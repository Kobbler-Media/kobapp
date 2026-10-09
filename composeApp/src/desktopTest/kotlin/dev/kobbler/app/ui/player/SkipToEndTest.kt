package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.SkipSegment
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.FocusScopeStore
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Credits run to the end of the file, so skipping them used to seek the player to its own
 * duration — a seek VLC drops on a stream, leaving the picture where it was.
 */
@OptIn(ExperimentalTestApi::class)
class SkipToEndTest {

    private data class Outcome(val nexts: Int, val backs: Int, val seeks: List<String>, val finished: Boolean, val paused: Boolean)

    private fun pressSkip(
        segment: SkipSegment,
        label: String,
        offsetSeconds: Int? = null,
        mediaType: String = "episode",
    ): Outcome {
        var nexts = 0
        var backs = 0
        val timeline = PlayerTimeline.of(offsetSeconds)
        val titleMs = 1_380_000L
        val player = FakePlayer().apply {
            durationMs.value = titleMs - timeline.offsetMs
            currentMs.value = timeline.toPlayerMs((segment.startSeconds * 1000).toLong() + 2_000)
        }
        val state = GlobalPlayerState().apply {
            this.mediaType = mediaType
            mediaId = "ep-1"
            sessionId = "sess-1"
            title = "Episode"
            this.timeline = timeline
            skipSegments = listOf(segment)
        }
        runComposeUiTest {
            setContent {
                Box(Modifier.size(960.dp, 540.dp)) {
                    FocusScopeHost(id = "test", store = FocusScopeStore()) {
                        KobappTheme(formFactor = FormFactor.Phone) {
                            FullscreenVideoPlayer(
                                player = player,
                                state = state,
                                api = KobblerApi(HttpClient(), "http://127.0.0.1:1", "token"),
                                formFactor = FormFactor.Phone,
                                onBack = { backs++ },
                                onFinished = if (mediaType == "episode") ({ nexts++ }) else null,
                                isFullscreen = true,
                                videoSurface = { },
                            )
                        }
                    }
                }
            }
            waitForIdle()
            player.calls.clear()
            onNodeWithText(label).performClick()
            waitForIdle()
            Thread.sleep(300)
            waitForIdle()
        }
        return Outcome(
            nexts, backs,
            player.calls.filter { it.startsWith("seek:") },
            state.reportedPositions["ep-1"]?.finished == true,
            "pause" in player.calls,
        )
    }

    private val creditsToEof = SkipSegment("credits", startSeconds = 1_290.0, endSeconds = 1_380.0)

    @Test
    fun `skipping credits that run to the end goes to the next episode`() {
        for (offset in listOf(null, 600)) {
            val out = pressSkip(creditsToEof, "Skip Credits", offsetSeconds = offset)
            assertEquals(1, out.nexts, "offset=$offset: next episode not started (seeks ${out.seeks})")
            assertEquals(emptyList(), out.seeks, "offset=$offset: seeked into the end of the stream")
            assertTrue(out.finished, "offset=$offset: not recorded as watched")
        }
    }

    @Test
    fun `skipping a film's closing credits hands the screen back`() {
        val out = pressSkip(creditsToEof, "Skip Credits", mediaType = "movie")
        assertEquals(1, out.backs)
        assertEquals(emptyList(), out.seeks)
        assertTrue(out.paused, "left playing, so the exit parks the credits in the mini player")
    }

    @Test
    fun `skipping credits that end a few seconds early still finishes`() {
        val out = pressSkip(creditsToEof.copy(endSeconds = 1_377.0), "Skip Credits")
        assertEquals(1, out.nexts)
    }

    @Test
    fun `skipping an intro still seeks past it`() {
        val intro = SkipSegment("intro", startSeconds = 60.0, endSeconds = 150.0)
        for (offset in listOf(null, 30)) {
            val out = pressSkip(intro, "Skip Intro", offsetSeconds = offset)
            val expected = PlayerTimeline.of(offset).toPlayerMs(150_000)
            assertEquals(listOf("seek:$expected"), out.seeks, "offset=$offset")
            assertEquals(0, out.nexts)
        }
    }
}
