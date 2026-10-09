package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkCacheTest {

    private val api = "https://kobbler.example.com"

    private fun cache(streamUrl: String, near: Boolean) =
        networkCacheMsFor(streamUrl, api, originIsNear = near)

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
        ).forEach { assertEquals(LanNetworkCacheMs, cache(it, near = false), it) }
    }

    @Test
    fun `a public name that is not obviously local is not obviously remote either`() {
        // Self-hosting behind a domain is the normal case, and the name reads the same
        // from the sofa as from a hotel. The connection is what tells them apart.
        val stream = "$api/api/v1/admin/playback/sessions/abc/direct"
        assertEquals(RemoteNetworkCacheMs, cache(stream, near = false))
        assertEquals(LanNetworkCacheMs, cache(stream, near = true))
    }

    @Test
    fun `a CDN is never assumed near just because the library server is`() {
        val debrid = "https://cdn.real-debrid.com/d/ABCDEF/file.mkv"
        assertEquals(RemoteNetworkCacheMs, cache(debrid, near = true))
        assertEquals(RemoteNetworkCacheMs, cache(debrid, near = false))
    }

    @Test
    fun `addresses just outside the private ranges stay remote`() {
        listOf(
            "http://172.32.0.1/stream",
            "http://172.15.0.1/stream",
            "http://192.169.1.1/stream",
            "http://8.8.8.8/stream",
        ).forEach { assertEquals(RemoteNetworkCacheMs, cache(it, near = true), it) }
    }

    @Test
    fun `a source with no parseable host is treated as remote`() {
        listOf("", "not a url", "/api/v1/relative").forEach {
            assertEquals(RemoteNetworkCacheMs, cache(it, near = true), it)
        }
    }

    @Test
    fun `latency is unknown until something has been timed`() {
        val latency = OriginLatency()
        assertNull(latency.roundTripMs())
        assertFalse(latency.isNear(), "an unmeasured origin must not be assumed near")
    }

    @Test
    fun `one slow request does not make a near origin look far`() {
        val latency = OriginLatency()
        latency.record(3)
        latency.record(900)
        assertTrue(latency.isNear(), "a large body or a busy server is not a distant one")
        assertEquals(3, latency.roundTripMs())
    }

    @Test
    fun `leaving the network reclassifies within the sample window`() {
        val latency = OriginLatency(samples = 4)
        repeat(4) { latency.record(2) }
        assertTrue(latency.isNear())
        repeat(4) { latency.record(120) }
        assertFalse(latency.isNear(), "the ring still holds a stale LAN sample")
    }
}
