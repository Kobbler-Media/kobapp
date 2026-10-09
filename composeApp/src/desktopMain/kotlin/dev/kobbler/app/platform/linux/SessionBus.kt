package dev.kobbler.app.platform.linux

import java.awt.EventQueue
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder

/** Desktop-integration services on the Linux session bus; null elsewhere or when there is no bus. */
object SessionBus {
    private val connection: DBusConnection? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Linux")) return@lazy null
        runCatching { DBusConnectionBuilder.forSessionBus().withShared(false).build() }
            .onFailure { println("[SessionBus] unavailable: ${it.message}") }
            .getOrNull()
    }

    val mpris: MprisService? by lazy {
        connection?.let { conn -> runCatching { MprisService(conn, dispatch = { EventQueue.invokeLater(it) }) }.getOrNull() }
    }

    val screenInhibitor: ScreenInhibitor? by lazy {
        connection?.let { runCatching { ScreenInhibitor(it) }.getOrNull() }
    }
}
