package dev.kobbler.app.platform.linux

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.types.UInt32

/** Holds off the screensaver and display blanking while video plays. */
class ScreenInhibitor(connection: DBusConnection) {
    private val screenSaver = connection.getRemoteObject("org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver", ScreenSaver::class.java)
    private var cookie: UInt32? = null

    @Synchronized
    fun hold(on: Boolean) {
        val held = cookie
        when {
            on && held == null -> cookie = runCatching { screenSaver.Inhibit("Kobbler", "Playing video") }.getOrNull()
            !on && held != null -> {
                cookie = null
                runCatching { screenSaver.UnInhibit(held) }
            }
        }
    }
}
