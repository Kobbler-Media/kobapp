package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.PlaybackClient
import dev.kobbler.app.data.SkipSegment
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val POSITION = "/api/v1/admin/tv/episodes/ep-1/watch-state/position"

/**
 * A transcoded session that was rebased 20 minutes in: the player's clock reads zero
 * there, while segments, chapters and saved progress are all in the title's own time.
 */
private const val OFFSET_SECONDS = 1200
private const val PLAYER_DURATION_MS = 2_400_000L

private fun StubServer.reportedPosition(): Int {
    val body = Json.parseToJsonElement(requestBodies.getValue(POSITION)).jsonObject
    assertEquals(PlaybackClient.id, body.getValue("client_id").jsonPrimitive.content)
    return body.getValue("position_seconds").jsonPrimitive.int
}

@OptIn(ExperimentalTestApi::class)
class RebasedPlayerConsumersTest {

    private fun state(offsetSeconds: Int) = GlobalPlayerState().apply {
        mediaType = "episode"
        mediaId = "ep-1"
        title = "Show"
        timeline = PlayerTimeline.of(offsetSeconds)
        skipSegments = listOf(
            SkipSegment(segmentType = "intro", startSeconds = 1800.0, endSeconds = 1980.0, source = "detected"),
        )
    }

    private fun run(
        offsetSeconds: Int,
        playerPositionMs: Long,
        body: androidx.compose.ui.test.ComposeUiTest.(FakePlayer, GlobalPlayerState, StubServer) -> Unit,
    ) {
        StubServer(mapOf(POSITION to { "{}" })).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer().also {
                it.durationMs.value = PLAYER_DURATION_MS
                it.currentMs.value = playerPositionMs
            }
            val gps = state(offsetSeconds)
            runComposeUiTest {
                setContent {
                    KobappTheme {
                        FullscreenVideoPlayer(
                            player = player, state = gps, api = api,
                            formFactor = FormFactor.Phone,
                            onBack = {}, onFinished = {},
                            videoSurface = { },
                        )
                    }
                }
                waitForIdle()
                body(player, gps, stub)
                waitForIdle()
            }
        }
    }

    @Test
    fun theSkipButtonAppearsForTheAbsolutePositionAndSeeksInPlayerTime() {
        // Player clock 700s => 1900s of the title, inside the 1800..1980 intro.
        run(OFFSET_SECONDS, playerPositionMs = 700_000) { player, _, _ ->
            onNodeWithText("Skip Intro").performClick()
            waitForIdle()
            assertEquals("seek:780000", player.calls.last { it.startsWith("seek:") })
        }
    }

    @Test
    fun theSkipButtonIsNotOfferedWhenTheAbsolutePositionIsOutsideTheSegment() {
        // Player clock 300s => 1500s of the title, before the intro starts.
        run(OFFSET_SECONDS, playerPositionMs = 300_000) { _, _, _ ->
            onNodeWithText("Skip Intro").assertDoesNotExist()
        }
    }

    @Test
    fun finishingReportsTheTitlesDurationNotThePlaylists() {
        run(OFFSET_SECONDS, playerPositionMs = 700_000) { player, _, stub ->
            player.state.value = PlayerState.FINISHED
            waitForIdle()
            waitUntil(timeoutMillis = 10_000) { stub.requestBodies.containsKey(POSITION) }
            assertEquals(3600, stub.reportedPosition())
        }
    }

    @Test
    fun directPlayIsUnchanged() {
        // Same fixture with no rebase: every consumer must behave exactly as before.
        run(offsetSeconds = 0, playerPositionMs = 1_900_000) { player, _, stub ->
            onNodeWithText("Skip Intro").performClick()
            waitForIdle()
            assertEquals("seek:1980000", player.calls.last { it.startsWith("seek:") })

            player.state.value = PlayerState.FINISHED
            waitForIdle()
            waitUntil(timeoutMillis = 10_000) { stub.requestBodies.containsKey(POSITION) }
            assertEquals(2400, stub.reportedPosition())
            assertTrue(player.calls.none { it == "seek:780000" })
        }
    }
}
