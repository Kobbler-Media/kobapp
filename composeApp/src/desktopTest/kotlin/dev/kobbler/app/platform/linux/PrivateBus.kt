package dev.kobbler.app.platform.linux

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder

/** A throwaway dbus-daemon, so tests never touch the desktop's real session bus. */
class PrivateBus : AutoCloseable {
    private val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1")
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val address: String = daemon.inputStream.bufferedReader().readLine().trim()
    private val connections = mutableListOf<DBusConnection>()

    fun connect(): DBusConnection =
        DBusConnectionBuilder.forAddress(address).withShared(false).build().also(connections::add)

    override fun close() {
        connections.forEach { runCatching { it.close() } }
        daemon.destroy()
    }
}
