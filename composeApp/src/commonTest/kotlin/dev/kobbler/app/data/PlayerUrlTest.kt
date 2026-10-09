package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a player is handed, and what it is not.
 *
 * libVLC 3.x cannot send an Authorization header, so every URL that needed one used to be
 * relayed through an in-app localhost proxy — the whole of a 4K remux copied through the
 * JVM to add one header, on whatever the cheapest device in the house is. The
 * session-keyed routes need no credential at all, and what is left carries a short-lived
 * playback token in the query, which VLC fetches directly.
 */
class PlayerUrlTest {

    /** Served unauthenticated on the session id the client had to be authorised to mint. */
    @Test
    fun `session-keyed routes need no credential`() {
        val urls = listOf(
            "https://kob.example.com/api/v1/admin/playback/sessions/abc/stream",
            "https://kob.example.com/api/v1/admin/playback/sessions/abc/master.m3u8",
            "https://kob.example.com/api/v1/admin/playback/sessions/abc/stream.m3u8?g=0",
            "https://kob.example.com/api/v1/admin/playback/sessions/abc/segments/gen_0/seg_00000.m4s",
            "https://kob.example.com/api/v1/admin/livetv/sessions/abc/stream.m3u8",
            "https://kob.example.com/api/v1/admin/livetv/sessions/abc/segments/seg_00000.m4s",
            // The live TS names its session in the query, and riding an open tune is the
            // form the server serves without a credential.
            "https://kob.example.com/api/v1/admin/livetv/channels/abc/stream.ts?session=xyz",
        )
        for (url in urls) assertFalse(playerUrlNeedsToken(url), url)
    }

    @Test
    fun `the two routes that still authenticate are named`() {
        val urls = listOf(
            // Debrid playback: the only high-bitrate path left that needs a credential.
            "https://kob.example.com/api/v1/admin/stream/sess-1/play",
            // Every audiobook.
            "https://kob.example.com/api/v1/admin/books/b1/audio/f1",
            // No session to ride, so this would cold-tune a physical tuner.
            "https://kob.example.com/api/v1/admin/livetv/channels/abc/stream.ts",
        )
        for (url in urls) assertTrue(playerUrlNeedsToken(url), url)
    }

    @Test
    fun `a token joins a url that already has a query`() {
        assertEquals(
            "https://k/api/v1/admin/books/b1/audio/f1?token=abc",
            withPlaybackToken("https://k/api/v1/admin/books/b1/audio/f1", "abc"),
        )
        assertEquals(
            "https://k/api/v1/admin/stream/s1/play?x=1&token=abc",
            withPlaybackToken("https://k/api/v1/admin/stream/s1/play?x=1", "abc"),
        )
    }

    /** The token is a credential, so it has to survive being put in a URL intact. */
    @Test
    fun `a token is escaped`() {
        assertTrue(withPlaybackToken("https://k/a", "a+b/c=").endsWith("token=a%2Bb%2Fc%3D"))
    }
}
