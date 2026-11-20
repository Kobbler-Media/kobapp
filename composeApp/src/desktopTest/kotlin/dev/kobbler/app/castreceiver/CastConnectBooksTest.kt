package dev.kobbler.app.castreceiver

import androidx.compose.ui.test.ExperimentalTestApi
import dev.kobbler.app.UiTimeoutMs
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.platform.CastDevice
import dev.kobbler.app.platform.CastEvent
import dev.kobbler.app.platform.CastFeature
import dev.kobbler.app.runUiTest
import dev.kobbler.app.ui.player.FakePlayer
import dev.kobbler.app.ui.player.StubServer
import dev.kobbler.app.ui.player.audiobook.AudiobookCastHandoff
import dev.kobbler.app.ui.player.listeningBook
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** kobapp on the TV plays video only: a book cast to it stays on the phone, whichever word reaches the phone first. */
@OptIn(ExperimentalTestApi::class)
class CastConnectBooksTest {

    private val routes = mapOf("/api/v1/admin/playback/tokens" to { _: com.sun.net.httpserver.HttpExchange -> """{"token":"cast-tok"}""" })

    private fun castingABook(capabilitiesFirst: Boolean) = runUiTest {
        StubServer(routes).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val player = FakePlayer()
            val book = listeningBook(api, player)
            val tv = TvReceiver(api)
            val sender = LoopbackSender(tv)

            setContent { AudiobookCastHandoff(sender, book) }
            if (capabilitiesFirst) {
                sender.senderJoined()
                runBlocking { withTimeout(UiTimeoutMs) { sender.events.first { it is CastEvent.Capabilities } } }
            }
            sender.device.value = CastDevice("Living Room TV")
            waitUntil(timeoutMillis = UiTimeoutMs) { book.notice.message != null }
            waitUntil(timeoutMillis = UiTimeoutMs) { book.castingTo == null && player.isPlaying.value }

            assertEquals("Living Room TV can't play audiobooks", book.notice.message)
            assertNull(tv.gps.mediaId, "the TV opened something")
            assertTrue(CastFeature.Audiobook !in sender.features)
            assertTrue(CastFeature.ReceiverReportsProgress in sender.features)
        }
    }

    @Test
    fun `a TV that has said what it plays is never handed the book`() = castingABook(capabilitiesFirst = true)

    @Test
    fun `a book handed over before the TV has said turns around and plays on here`() = castingABook(capabilitiesFirst = false)
}
