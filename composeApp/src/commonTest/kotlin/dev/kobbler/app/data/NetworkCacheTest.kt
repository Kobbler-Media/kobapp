package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkCacheTest {

    private val api = "https://kobbler.example.com"
    private val ownStream = "$api/api/v1/admin/playback/sessions/abc/direct"

    private fun cache(
        streamUrl: String,
        roundTripMs: Long? = null,
        profile: OriginProfile = OriginProfile(),
        liveTuner: Boolean = false,
    ) = networkCacheMsFor(streamUrl, api, roundTripMs, profile, liveTuner)

    @Test
    fun `a host that can only be local needs no measurement`() {
        listOf(
            "http://192.168.1.20:8096/stream",
            "http://10.0.0.5/stream",
            "http://172.16.4.1/stream",
            "http://172.31.255.254/stream",
            "http://127.0.0.1:3000/stream",
            "http://localhost:3000/stream",
            "http://kobbler:8080/stream",
            "http://nas.local/stream",
            "https://media.home.arpa/stream",
            "http://[::1]:8096/stream",
            "http://user:pass@192.168.1.20:8096/stream",
        ).forEach { assertEquals(LanNetworkCacheMs, cache(it), it) }
    }

    /**
     * The buffer follows the measured round trip rather than flipping between two values
     * at a threshold. A Chromecast on Wi-Fi a few ms from the server was timed through whole
     * API calls, read as remote and buffered for a second and a half on every start.
     */
    @Test
    fun `the buffer for the library server grows with its round trip`() {
        listOf(
            2L to 320,
            4L to 340,
            25L to 550,
            50L to 800,
            120L to RemoteNetworkCacheMs,
            900L to RemoteNetworkCacheMs,
        ).forEach { (roundTrip, expected) ->
            assertEquals(expected, cache(ownStream, roundTripMs = roundTrip), "${roundTrip}ms")
        }
    }

    @Test
    fun `an unmeasured start uses what the server measured last time, or assumes far`() {
        assertEquals(340, cache(ownStream, profile = OriginProfile(roundTripMs = 4)))
        assertEquals(RemoteNetworkCacheMs, cache(ownStream))
    }

    @Test
    fun `a live measurement outranks the remembered one`() {
        // Remembered from home, measured now from a hotel.
        assertEquals(RemoteNetworkCacheMs, cache(ownStream, roundTripMs = 150, profile = OriginProfile(roundTripMs = 3)))
    }

    @Test
    fun `a CDN is never assumed near just because the library server is`() {
        val debrid = "https://cdn.real-debrid.com/d/ABCDEF/file.mkv"
        assertEquals(RemoteNetworkCacheMs, cache(debrid, roundTripMs = 2))
        assertEquals(RemoteNetworkCacheMs, cache(debrid))
    }

    @Test
    fun `addresses just outside the private ranges stay remote`() {
        listOf(
            "http://172.32.0.1/stream",
            "http://172.15.0.1/stream",
            "http://192.169.1.1/stream",
            "http://8.8.8.8/stream",
        ).forEach { assertEquals(RemoteNetworkCacheMs, cache(it, roundTripMs = 2), it) }
    }

    @Test
    fun `a source with no parseable host is treated as remote`() {
        listOf("", "not a url", "/api/v1/relative").forEach {
            assertEquals(RemoteNetworkCacheMs, cache(it, roundTripMs = 2), it)
        }
    }

    @Test
    fun `stalls a server caused buy it a deeper buffer, which clean watching gives back`() {
        var profile = OriginProfile(roundTripMs = 4)
        repeat(2) { profile = profile.afterStall() }
        assertEquals(340 + 2 * StallStepMs, cache(ownStream, profile = profile))

        repeat(20) { profile = profile.afterStall() }
        assertEquals(MaxStallAllowanceMs, profile.stallAllowanceMs, "one bad evening must not buffer for ever")

        repeat(100) { profile = profile.afterCleanSession() }
        assertEquals(0, profile.stallAllowanceMs)
        assertEquals(340, cache(ownStream, profile = profile))
    }

    @Test
    fun `the allowance applies to every source from a host, local ones included`() {
        val profile = OriginProfile().afterStall()
        assertEquals(LanNetworkCacheMs + StallStepMs, cache("http://10.0.0.5/stream", profile = profile))
        assertEquals(MaxNetworkCacheMs, cache("https://cdn.example.com/f.mkv", profile = OriginProfile(stallAllowanceMs = 9_000)))
    }

    @Test
    fun `a playhead that jumps was moved by a seek`() {
        assertFalse(isSeekJump(previousMs = 600_000, currentMs = 600_250), "a clock tick")
        assertTrue(isSeekJump(previousMs = 600_000, currentMs = 610_000))
        assertTrue(isSeekJump(previousMs = 600_000, currentMs = 590_000))
    }

    @Test
    fun `buffering straight after a seek is the seek's, not a stall`() {
        assertTrue(isStall(msSinceSeek = null))
        assertTrue(isStall(msSinceSeek = 60_000))
        assertFalse(isStall(msSinceSeek = 800))
    }

    @Test
    fun `the port a server is reached on`() {
        listOf(
            "https://kobbler.example.com" to 443,
            "http://kobbler.example.com/api" to 80,
            "https://kobbler.example.com:8443/x" to 8443,
            "http://user:pw@10.0.0.5:8096" to 8096,
            "http://[::1]:3000/" to 3000,
            "http://[::1]/" to 80,
        ).forEach { (url, port) -> assertEquals(port, portOf(url), url) }
    }

    @Test
    fun `latency is unknown until something has been timed`() {
        assertNull(OriginLatency().roundTripMs())
    }

    @Test
    fun `one slow request does not make a near origin look far`() {
        val latency = OriginLatency()
        latency.record(3)
        latency.record(900)
        assertEquals(3, latency.roundTripMs(), "a large body or a busy server is not a distant one")
    }

    @Test
    fun `leaving the network reclassifies within the sample window`() {
        val latency = OriginLatency(samples = 4)
        repeat(4) { latency.record(2) }
        repeat(4) { latency.record(120) }
        assertEquals(120, latency.roundTripMs(), "the ring still holds a stale LAN sample")
    }

    /**
     * A tuner hands its bytes over at broadcast rate, so every ms of buffer is a ms the first
     * picture sits frozen on screen; an allowance learned from films never fits it.
     */
    @Test
    fun `a live tune does not pay the allowance a server earned on films`() {
        val stalled = OriginProfile(stallAllowanceMs = MaxStallAllowanceMs)
        assertEquals(340, cache(ownStream, roundTripMs = 4, profile = stalled, liveTuner = true))
        assertEquals(340 + MaxStallAllowanceMs, cache(ownStream, roundTripMs = 4, profile = stalled))
    }
}
