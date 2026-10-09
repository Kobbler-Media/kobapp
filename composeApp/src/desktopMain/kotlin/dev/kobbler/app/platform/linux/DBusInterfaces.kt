@file:Suppress("FunctionName")

package dev.kobbler.app.platform.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32

// Method names are the D-Bus member names verbatim; dbus-java maps them by name.

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offsetUs: Long)
    fun SetPosition(trackId: DBusPath, positionUs: Long)
    fun OpenUri(uri: String)
}

@DBusInterfaceName("org.freedesktop.ScreenSaver")
interface ScreenSaver : DBusInterface {
    fun Inhibit(applicationName: String, reason: String): UInt32
    fun UnInhibit(cookie: UInt32)
}
