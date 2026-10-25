package dev.kobbler.app.ui.player

import dev.kobbler.app.data.BufferClass
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.OriginProfile
import dev.kobbler.app.data.OriginProfiles
import dev.kobbler.app.data.RemoteLiveNetworkCacheMs
import dev.kobbler.app.data.RemoteNetworkCacheMs
import dev.kobbler.app.data.StallStepMs
import dev.kobbler.kobplayer.PlayerState
import io.ktor.client.HttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What playback runs into feeds back into the buffer the next start asks for. */
@OptIn(ExperimentalCoroutinesApi::class)
class BufferLearningTest {

    private val host = "kobbler.example.com"
    private val stream = "https://$host/api/v1/admin/playback/sessions/s1/direct"

    private class Setup(val profiles: OriginProfiles, val api: KobblerApi, val player: FakePlayer)

    private fun TestScope.watching(start: OriginProfile = OriginProfile(), mediaType: String = "movie"): Setup {
        val backing = mutableMapOf<String, String>()
        val profiles = OriginProfiles(read = { backing[it] }, write = { k, v -> backing[k] = v })
        profiles.update(host) { start }
        val api = KobblerApi(HttpClient(), "https://$host", "token", originProfiles = profiles)
        val player = FakePlayer()
        val gps = GlobalPlayerState().apply {
            lastStreamUrl = stream
            this.mediaType = mediaType
        }
        backgroundScope.launch { gps.learnBuffering(player, api, now = { testScheduler.currentTime }) }
        runCurrent()
        return Setup(profiles, api, player)
    }

    private fun TestScope.play(player: FakePlayer, fromMs: Long, forMs: Long) {
        player.state.value = PlayerState.PLAYING
        runCurrent()
        var at = fromMs
        while (at < fromMs + forMs) {
            at += 250
            player.currentMs.value = at
            advanceTimeBy(250)
            runCurrent()
        }
    }

    @Test
    fun `running dry mid-play deepens the buffer for that server`() = runTest {
        val s = watching()
        play(s.player, fromMs = 600_000, forMs = 30_000)

        s.player.state.value = PlayerState.BUFFERING
        runCurrent()

        assertEquals(StallStepMs, s.profiles.get(host).stallAllowanceMs)
    }

    @Test
    fun `buffering because the viewer sought is not held against the server`() = runTest {
        val s = watching()
        play(s.player, fromMs = 600_000, forMs = 30_000)

        s.player.currentMs.value = 900_000
        runCurrent()
        s.player.state.value = PlayerState.BUFFERING
        runCurrent()

        assertEquals(0, s.profiles.get(host).stallAllowanceMs)
    }

    @Test
    fun `a long clean stretch gives some of it back`() = runTest {
        val s = watching(start = OriginProfile(stallAllowanceMs = 2 * StallStepMs))
        play(s.player, fromMs = 0, forMs = 11 * 60_000)

        assertEquals(2 * StallStepMs - StallStepMs / 2, s.profiles.get(host).stallAllowanceMs)
    }

    /** A tuner's picture breaks up with the weather, not with the network to the server. */
    @Test
    fun `live tv buffering is not held against the server`() = runTest {
        val s = watching(mediaType = "livetv")
        play(s.player, fromMs = 600_000, forMs = 30_000)

        s.player.state.value = PlayerState.BUFFERING
        runCurrent()

        assertEquals(0, s.profiles.get(host).stallAllowanceMs)
    }

    /**
     * A channel is relayed by the library server but stalls with the IPTV provider behind
     * it, so what one teaches the buffer says nothing about the other.
     */
    @Test
    fun `IPTV and films learn their buffers apart on the same server`() = runTest {
        val film = RemoteNetworkCacheMs
        val channel = RemoteLiveNetworkCacheMs
        listOf(
            Triple("iptv", film, channel + StallStepMs),
            Triple("movie", film + StallStepMs, channel),
        ).forEach { (stalling, filmAfter, channelAfter) ->
            val s = watching(mediaType = stalling)
            play(s.player, fromMs = 600_000, forMs = 30_000)
            s.player.state.value = PlayerState.BUFFERING
            runCurrent()

            assertEquals(filmAfter, s.api.networkCacheMsFor(stream, BufferClass.Vod), "film after a $stalling stall")
            assertEquals(channelAfter, s.api.networkCacheMsFor(stream, BufferClass.RemoteLive), "channel after a $stalling stall")
        }
    }

    @Test
    fun `clean IPTV watching does not ease a film buffer the server earned`() = runTest {
        val s = watching(start = OriginProfile(stallAllowanceMs = 2 * StallStepMs), mediaType = "iptv")
        play(s.player, fromMs = 0, forMs = 11 * 60_000)

        assertEquals(2 * StallStepMs, s.profiles.get(host).stallAllowanceMs)
    }
}
