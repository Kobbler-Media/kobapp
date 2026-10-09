package dev.kobbler.app.platform.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

private const val ObjectPath = "/org/mpris/MediaPlayer2"
private const val RootIface = "org.mpris.MediaPlayer2"
private const val PlayerIface = "org.mpris.MediaPlayer2.Player"
private val TrackId = DBusPath("/dev/kobbler/track")

/**
 * Publishes now-playing as an MPRIS player, which is what desktop media keys, the Plasma/GNOME
 * media widgets and Bluetooth headset buttons all talk to.
 */
class MprisService(
    private val connection: DBusConnection,
    instance: String = "instance${ProcessHandle.current().pid()}",
    private val dispatch: (() -> Unit) -> Unit,
) : MprisRoot, MprisPlayer, Properties, AutoCloseable {

    private class Controls(
        val playPause: (() -> Unit)?,
        val next: (() -> Unit)?,
        val previous: (() -> Unit)?,
        val stop: (() -> Unit)?,
        val seekTo: ((Long) -> Unit)?,
    )

    private data class NowPlaying(
        val title: String,
        val subtitle: String?,
        val coverUrl: String?,
        val isPlaying: Boolean,
        val durationMs: Long,
    )

    @Volatile private var controls = Controls(null, null, null, null, null)
    @Volatile private var nowPlaying: NowPlaying? = null
    @Volatile private var positionMs = 0L

    init {
        connection.requestBusName("org.mpris.MediaPlayer2.kobbler.$instance")
        connection.exportObject(ObjectPath, this)
    }

    fun setControls(
        onPlayPause: (() -> Unit)?,
        onSkipForward: (() -> Unit)?,
        onSkipBack: (() -> Unit)?,
        onStop: (() -> Unit)?,
        onSeekTo: ((Long) -> Unit)?,
    ) {
        controls = Controls(onPlayPause, onSkipForward, onSkipBack, onStop, onSeekTo)
        announce(PlayerIface, "CanPlay", "CanPause", "CanGoNext", "CanGoPrevious", "CanSeek")
    }

    fun update(title: String, subtitle: String?, coverUrl: String?, isPlaying: Boolean, currentMs: Long, durationMs: Long) {
        positionMs = currentMs
        val next = NowPlaying(title, subtitle?.ifBlank { null }, coverUrl?.ifBlank { null }, isPlaying, durationMs)
        val previous = nowPlaying
        if (next == previous) return
        nowPlaying = next
        // Position is polled by clients per the spec; only what they cannot poll is signalled.
        when {
            previous == null || previous.copy(isPlaying = next.isPlaying) != next -> announce(PlayerIface, "PlaybackStatus", "Metadata")
            else -> announce(PlayerIface, "PlaybackStatus")
        }
    }

    fun clear() {
        if (nowPlaying == null) return
        nowPlaying = null
        positionMs = 0
        announce(PlayerIface, "PlaybackStatus", "Metadata")
    }

    private fun announce(iface: String, vararg names: String) {
        runCatching {
            connection.sendMessage(Properties.PropertiesChanged(ObjectPath, iface, names.associateWith { property(iface, it) }, emptyList()))
        }
    }

    private fun run(action: (() -> Unit)?) {
        action?.let(dispatch)
    }

    private fun property(iface: String, name: String): Variant<*> = when (iface) {
        RootIface -> when (name) {
            "CanQuit", "CanRaise", "HasTrackList" -> Variant(false)
            "Identity" -> Variant("Kobbler")
            "DesktopEntry" -> Variant("kobapp")
            "SupportedUriSchemes", "SupportedMimeTypes" -> Variant(emptyList<String>(), "as")
            else -> throw IllegalArgumentException("No property $iface.$name")
        }
        PlayerIface -> {
            val c = controls
            when (name) {
                "PlaybackStatus" -> Variant(
                    when (nowPlaying?.isPlaying) {
                        true -> "Playing"
                        false -> "Paused"
                        null -> "Stopped"
                    },
                )
                "Metadata" -> Variant(metadata(), "a{sv}")
                "Position" -> Variant(positionMs * 1000)
                "Rate", "MinimumRate", "MaximumRate", "Volume" -> Variant(1.0)
                "CanPlay", "CanPause" -> Variant(c.playPause != null)
                "CanGoNext" -> Variant(c.next != null)
                "CanGoPrevious" -> Variant(c.previous != null)
                "CanSeek" -> Variant(c.seekTo != null)
                "CanControl" -> Variant(true)
                else -> throw IllegalArgumentException("No property $iface.$name")
            }
        }
        else -> throw IllegalArgumentException("No interface $iface")
    }

    private fun metadata(): Map<String, Variant<*>> {
        val np = nowPlaying ?: return emptyMap()
        return buildMap {
            put("mpris:trackid", Variant(TrackId))
            put("xesam:title", Variant(np.title))
            np.subtitle?.let { put("xesam:artist", Variant(listOf(it), "as")) }
            np.coverUrl?.let { put("mpris:artUrl", Variant(it)) }
            if (np.durationMs > 0) put("mpris:length", Variant(np.durationMs * 1000))
        }
    }

    override fun Raise() {}
    override fun Quit() {}

    override fun Next() = run(controls.next)
    override fun Previous() = run(controls.previous)
    override fun PlayPause() = run(controls.playPause)
    override fun Stop() = run(controls.stop)
    override fun Play() { if (nowPlaying?.isPlaying == false) run(controls.playPause) }
    override fun Pause() { if (nowPlaying?.isPlaying == true) run(controls.playPause) }
    override fun Seek(offsetUs: Long) {
        val target = (positionMs + offsetUs / 1000).coerceAtLeast(0)
        controls.seekTo?.let { seek -> dispatch { seek(target) } }
    }
    override fun SetPosition(trackId: DBusPath, positionUs: Long) {
        controls.seekTo?.let { seek -> dispatch { seek(positionUs / 1000) } }
    }
    override fun OpenUri(uri: String) {}

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = property(interfaceName, propertyName) as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        RootIface -> listOf("CanQuit", "CanRaise", "HasTrackList", "Identity", "DesktopEntry", "SupportedUriSchemes", "SupportedMimeTypes")
        PlayerIface -> listOf(
            "PlaybackStatus", "Metadata", "Position", "Rate", "MinimumRate", "MaximumRate", "Volume",
            "CanPlay", "CanPause", "CanGoNext", "CanGoPrevious", "CanSeek", "CanControl",
        )
        else -> emptyList()
    }.associateWith { property(interfaceName, it) }

    override fun getObjectPath(): String = ObjectPath

    override fun close() {
        runCatching { connection.unExportObject(ObjectPath) }
    }
}
