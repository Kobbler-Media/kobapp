package dev.kobbler.app.data

import com.sun.net.httpserver.HttpExchange
import dev.kobbler.app.ui.player.StubServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A player fetches what needs a credential with one in the URL, and mints it once.
 *
 * The alternative was the in-app localhost proxy, which existed only because libVLC 3.x
 * cannot send an Authorization header — so every byte of an audiobook or a debrid remux
 * was copied through the JVM to add one.
 */
class PlaybackTokenTest {

    private fun server(mints: AtomicInteger) = StubServer(
        mapOf(
            "/api/v1/admin/playback/tokens" to { _: HttpExchange ->
                """{"token":"tok-${mints.incrementAndGet()}","expires_at":"2030-01-01T00:00:00Z"}"""
            },
        ),
    )

    @Test
    fun `a route that needs a credential gets one in the query`() = runTest {
        val mints = AtomicInteger()
        server(mints).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val url = api.playerUrl("${stub.url}/api/v1/admin/books/b1/audio/f1")
            assertTrue(url.endsWith("?token=tok-1"), url)
        }
    }

    /** Minting per request would put a round trip in front of every segment. */
    @Test
    fun `the token is minted once and reused`() = runTest {
        val mints = AtomicInteger()
        server(mints).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            repeat(3) { api.playerUrl("${stub.url}/api/v1/admin/stream/s1/play") }
            assertEquals(1, mints.get(), "minted a token per request")
        }
    }

    /** A session-keyed route needs nothing, so it must not even ask. */
    @Test
    fun `a public route mints nothing`() = runTest {
        val mints = AtomicInteger()
        server(mints).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val url = "${stub.url}/api/v1/admin/playback/sessions/s1/stream"
            assertEquals(url, api.playerUrl(url))
            assertEquals(0, mints.get(), "asked for a credential it does not need")
        }
    }

    /** A token the server has forgotten has to be replaceable. */
    @Test
    fun `a lost token is re-minted`() = runTest {
        val mints = AtomicInteger()
        server(mints).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val target = "${stub.url}/api/v1/admin/books/b1/audio/f1"
            assertTrue(api.playerUrl(target).endsWith("tok-1"))
            api.invalidatePlaybackToken()
            assertTrue(api.playerUrl(target).endsWith("tok-2"))
        }
    }

    /** A server that will not mint is not a reason to refuse to play. */
    @Test
    fun `a url is still handed over when no token can be had`() = runTest {
        StubServer(emptyMap()).use { stub ->
            val api = KobblerApi(HttpClient(), stub.url, "token")
            val target = "${stub.url}/api/v1/admin/books/b1/audio/f1"
            assertEquals(target, api.playerUrl(target))
        }
    }
}
