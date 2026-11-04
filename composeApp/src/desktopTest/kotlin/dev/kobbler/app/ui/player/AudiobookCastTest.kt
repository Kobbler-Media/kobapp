package dev.kobbler.app.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.AudioFileDto
import dev.kobbler.app.data.Book
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.UnifiedChapterDto
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.runUiTest
import dev.kobbler.app.ui.player.audiobook.AudiobookCastHandoff
import dev.kobbler.app.ui.player.audiobook.AudiobookPlayerState
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A book cast to a receiver is the same listen continued there, worked from this screen. */
@OptIn(ExperimentalTestApi::class)
class AudiobookCastTest {

    private val routes = mapOf(
        "/api/v1/admin/playback/tokens" to { _: HttpExchange -> """{"token":"cast-tok"}""" },
    )

    private fun file(id: String, index: Int, seconds: Double) =
        AudioFileDto(id = id, fileIndex = index, format = "mp3", fileName = "$id.mp3", durationSeconds = seconds)

    private fun listening(api: KobblerApi, player: FakePlayer) = AudiobookPlayerState(
        Book(id = "b1", title = "Treehouse", author = "Andy", coverUrl = "/covers/b1.jpg"),
        api,
        player,
        CoroutineScope(Dispatchers.Unconfined),
    ).apply {
        audioFiles = listOf(file("f1", 0, 600.0), file("f2", 1, 900.0))
        chapters = listOf(
            UnifiedChapterDto(id = "c1", chapterIndex = 0, title = "One", startSeconds = 0.0, endSeconds = 600.0, fileId = "f1"),
            UnifiedChapterDto(
                id = "c2", chapterIndex = 1, title = "Two", startSeconds = 0.0, endSeconds = 900.0, fileId = "f2",
                globalStartSeconds = 600.0, globalEndSeconds = 1500.0,
            ),
        )
        dataLoaded = true
        runBlocking { playFileAt(1, 30_000) }
        player.state.value = PlayerState.PLAYING
    }

    private fun handedOver(cast: FakeSender) =
        Json.parseToJsonElement(cast.payloads.single { it.first == "INIT_AUDIOBOOK" }.second).jsonObject

    @Test
    fun `a receiver picked mid-book takes the listen over from where it is`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer()
            val state = listening(api, player)
            val cast = FakeSender()

            setContent { AudiobookCastHandoff(cast, state) }
            cast.connecting("Projector TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { "pause" in player.calls }
            cast.connect("Projector TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { "INIT_AUDIOBOOK" in cast.sent }

            val payload = handedOver(cast)
            assertEquals("b1", payload["bookId"]!!.jsonPrimitive.content)
            assertEquals(listOf("f1", "f2"), payload["fileIds"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf(600.0, 900.0), payload["fileDurations"]!!.jsonArray.map { it.jsonPrimitive.double })
            assertEquals(1, payload["currentFileIndex"]!!.jsonPrimitive.int)
            assertEquals(30.0, payload["currentTime"]!!.jsonPrimitive.double)
            val second = payload["chapters"]!!.jsonArray[1].jsonObject
            assertEquals(1, second["fileIndex"]!!.jsonPrimitive.int)
            assertEquals(0.0, second["startSeconds"]!!.jsonPrimitive.double)
            assertEquals("Projector TV", state.castingTo)
        }
    }

    @Test
    fun `the screen follows the receiver and works it`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer()
            val state = listening(api, player)
            val cast = FakeSender()

            setContent { AudiobookCastHandoff(cast, state) }
            cast.connect("Projector TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { state.castingTo != null }

            runBlocking { cast.say(CastEvent.Position(positionSec = 120.0, durationSec = 900.0, isPlaying = true)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { state.globalPositionMs == 720_000L }
            assertEquals("Two", state.currentChapter?.title)

            runBlocking { cast.say(CastEvent.AudiobookFileChanged(fileIndex = 0)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { state.currentFileIndex == 0 }

            player.calls.clear()
            state.seekToGlobal(700_000)
            state.togglePlayPause()
            state.setSpeed(1.5f)
            waitUntil(timeoutMillis = UiTimeoutMs) { "AUDIOBOOK_RATE" in cast.sent && cast.toggles == 1 }

            assertEquals(
                listOf(
                    "AUDIOBOOK_SEEK" to """{"fileIndex":1,"time":100.0}""",
                    "AUDIOBOOK_RATE" to """{"rate":1.5}""",
                ),
                cast.payloads.filter { it.first.startsWith("AUDIOBOOK_") },
            )
            assertEquals(PlayerState.PAUSED, state.playerState, "the tap is answered before the receiver is")
            assertTrue(player.calls.none { it == "load" || it == "play" || it.startsWith("seek") }, "${player.calls}")
        }
    }

    @Test
    fun `stopping the cast picks the book up here where the receiver left it`() = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer()
            val state = listening(api, player)
            val cast = FakeSender()

            setContent { AudiobookCastHandoff(cast, state) }
            cast.connect("Projector TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { state.castingTo != null }
            runBlocking { cast.say(CastEvent.AudiobookFileChanged(fileIndex = 0)) }
            runBlocking { cast.say(CastEvent.Position(positionSec = 300.0, durationSec = 600.0, isPlaying = true)) }
            waitUntil(timeoutMillis = UiTimeoutMs) { state.globalPositionMs == 300_000L }
            player.calls.clear()

            cast.disconnect()
            waitUntil(timeoutMillis = UiTimeoutMs) { "load" in player.calls }

            assertNull(state.castingTo)
            assertEquals("f1", player.loadedUrl?.substringAfterLast("/audio/")?.substringBefore("?"))
            assertEquals(300, player.loadedSource?.startTimeSec)
        }
    }
}
