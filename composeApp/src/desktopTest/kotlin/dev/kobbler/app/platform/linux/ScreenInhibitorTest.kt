package dev.kobbler.app.platform.linux

import org.freedesktop.dbus.types.UInt32
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenInhibitorTest {

    private class FakeScreenSaver : ScreenSaver {
        val held = mutableSetOf<Int>()
        private var next = 1
        override fun Inhibit(applicationName: String, reason: String): UInt32 = UInt32(next++.toLong()).also { held += it.toInt() }
        override fun UnInhibit(cookie: UInt32) { held -= cookie.toInt() }
        override fun getObjectPath() = "/org/freedesktop/ScreenSaver"
    }

    @Test
    fun `keeping the screen on holds exactly one inhibit until released`() = PrivateBus().use { bus ->
        val fake = FakeScreenSaver()
        bus.connect().apply {
            requestBusName("org.freedesktop.ScreenSaver")
            exportObject(fake.objectPath, fake)
        }
        val inhibitor = ScreenInhibitor(bus.connect())

        inhibitor.hold(true)
        inhibitor.hold(true)
        assertEquals(1, fake.held.size, "a second hold stacked another inhibit")

        inhibitor.hold(false)
        assertEquals(emptySet(), fake.held)
        inhibitor.hold(false)
    }
}
