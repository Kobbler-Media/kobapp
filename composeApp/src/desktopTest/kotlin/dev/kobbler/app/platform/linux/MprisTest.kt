package dev.kobbler.app.platform.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.interfaces.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class MprisTest {

    private val path = "/org/mpris/MediaPlayer2"
    private val player = "org.mpris.MediaPlayer2.Player"

    private fun withMpris(block: (MprisService, Properties, MprisPlayer) -> Unit) = PrivateBus().use { bus ->
        val service = MprisService(bus.connect(), instance = "test", dispatch = { it() })
        val client = bus.connect()
        val name = "org.mpris.MediaPlayer2.kobbler.test"
        block(
            service,
            client.getRemoteObject(name, path, Properties::class.java),
            client.getRemoteObject(name, path, MprisPlayer::class.java),
        )
    }

    @Test
    fun `now playing is published as MPRIS properties`() = withMpris { mpris, props, _ ->
        assertEquals("Stopped", props.Get<String>(player, "PlaybackStatus"))

        mpris.update(title = "Wall-E", subtitle = "Pixar", coverUrl = "https://x/p.jpg", isPlaying = true, currentMs = 60_000, durationMs = 5_880_000)

        assertEquals("Playing", props.Get<String>(player, "PlaybackStatus"))
        assertEquals(60_000_000L, props.Get<Long>(player, "Position"))
        // The client proxy unwraps the a{sv} values.
        val meta = props.Get<Map<String, Any>>(player, "Metadata")
        assertEquals("Wall-E", meta["xesam:title"])
        assertEquals(5_880_000_000L, meta["mpris:length"])
        assertEquals("https://x/p.jpg", meta["mpris:artUrl"])

        // The video player passes "" rather than null for what it doesn't have.
        mpris.update(title = "Wall-E", subtitle = "", coverUrl = "", isPlaying = false, currentMs = 61_000, durationMs = 5_880_000)
        assertEquals("Paused", props.Get<String>(player, "PlaybackStatus"))
        assertEquals(setOf("mpris:trackid", "xesam:title", "mpris:length"), props.Get<Map<String, Any>>(player, "Metadata").keys)

        mpris.clear()
        assertEquals("Stopped", props.Get<String>(player, "PlaybackStatus"))
    }

    @Test
    fun `media keys drive the registered controls`() = withMpris { mpris, props, remote ->
        val calls = mutableListOf<String>()
        mpris.setControls(
            onPlayPause = { calls += "playpause" },
            onSkipForward = { calls += "next" },
            onSkipBack = { calls += "prev" },
            onStop = { calls += "stop" },
            onSeekTo = { calls += "seek:$it" },
        )
        mpris.update(title = "Wall-E", subtitle = null, coverUrl = null, isPlaying = true, currentMs = 10_000, durationMs = 100_000)
        assertEquals(true, props.Get<Boolean>(player, "CanSeek"))

        remote.PlayPause()
        remote.Next()
        remote.Previous()
        remote.Seek(5_000_000)
        remote.SetPosition(DBusPath("/dev/kobbler/track"), 42_000_000)
        remote.Stop()
        assertEquals(listOf("playpause", "next", "prev", "seek:15000", "seek:42000", "stop"), calls)

        mpris.setControls(null, null, null, null, null)
        assertEquals(false, props.Get<Boolean>(player, "CanGoNext"))
        remote.PlayPause()
        assertEquals(6, calls.size, "cleared controls still fired")
    }
}
