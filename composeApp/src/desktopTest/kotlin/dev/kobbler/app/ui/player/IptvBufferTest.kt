package dev.kobbler.app.ui.player

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LanNetworkCacheMs
import dev.kobbler.app.data.OriginProfiles
import dev.kobbler.app.data.RemoteLiveNetworkCacheMs
import dev.kobbler.app.data.StallStepMs
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A channel is relayed by the library server, which at home is a LAN hop away, but the
 * bytes come from an IPTV provider over the internet at broadcast rate. Every way a
 * channel's source gets opened has to buffer for the provider, not for the relay.
 */
class IptvBufferTest {

    private val routes = mapOf(
        "/api/v1/admin/iptv/sessions" to { _: HttpExchange ->
            """{"session_id":"iptv-2","method":"direct_play","direct_url":"/api/v1/admin/iptv/channels/chan-1/play.ts"}"""
        },
        "/api/v1/admin/playback/sessions/dead-1" to { _: HttpExchange -> "{}" },
    )

    private fun memoryProfiles(): OriginProfiles {
        val backing = mutableMapOf<String, String>()
        return OriginProfiles(read = { backing[it] }, write = { k, v -> backing[k] = v })
    }

    private fun watchingChannel() = GlobalPlayerState().apply {
        mediaType = "iptv"
        mediaId = "chan-1"
        sessionId = "dead-1"
        title = "TNT Sports 2"
    }

    private val channel = IptvChannel(id = "chan-1", name = "TNT Sports 2", iconUrl = null, category = null, language = null, country = null)

    private fun withStub(block: suspend CoroutineScope.(KobblerApi) -> Unit) = runBlocking {
        StubServer(routes).use { stub ->
            block(KobblerApi(HttpClient(), stub.url, "token", originProfiles = memoryProfiles()))
        }
    }

    private suspend fun FakePlayer.openedCacheMs(): Int? {
        withTimeout(5_000) { while (loadedSource == null) delay(10) }
        return loadedSource?.networkCacheMs
    }

    @Test
    fun `every way a channel opens buffers for the provider behind a LAN relay`() {
        val entryPoints: List<Pair<String, suspend CoroutineScope.(KobblerApi, GlobalPlayerState, FakePlayer) -> Unit>> = listOf(
            "tune" to { api, gps, player -> startIptvWatch(channel, player, gps, api, this) },
            "reconnect" to { api, gps, player -> gps.recoverPlayback(player, api) },
        )
        entryPoints.forEach { (name, open) ->
            withStub { api ->
                val player = FakePlayer()
                open(api, watchingChannel(), player)
                assertEquals(RemoteLiveNetworkCacheMs, player.openedCacheMs(), name)
            }
        }
    }

    @Test
    fun `a film from the same LAN server keeps its LAN buffer`() = withStub { api ->
        assertEquals(LanNetworkCacheMs, api.networkCacheMsFor("${api.apiUrl}/api/v1/admin/playback/sessions/s1/direct"))
    }

    /**
     * The stall is learned the moment it happens, and the reconnect that follows a dropout
     * opens through the same buffer policy — so it already gets the deeper cushion.
     */
    @Test
    fun `a channel that ran dry reconnects with a deeper buffer in the same session`() = withStub { api ->
        val gps = watchingChannel().apply { lastStreamUrl = "${api.apiUrl}/api/v1/admin/iptv/channels/chan-1/play.ts" }
        val player = FakePlayer()
        val learning = launch { gps.learnBuffering(player, api, now = { 0L }) }
        delay(50)
        player.state.value = PlayerState.PLAYING
        delay(50)
        player.state.value = PlayerState.BUFFERING
        delay(50)
        learning.cancel()

        gps.recoverPlayback(player, api)

        assertEquals(RemoteLiveNetworkCacheMs + StallStepMs, player.openedCacheMs())
    }
}
