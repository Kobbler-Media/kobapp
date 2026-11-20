package dev.kobbler.app.data

import dev.kobbler.app.platform.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Position saves name the device they came from. A cast renderer that cannot save for
 * itself is saved for by this device, so its saves have to keep naming the same one.
 */
class PlaybackClientTest {

    @Test
    fun `the client id is minted once per install and read back after a relaunch`() {
        val store = mutableMapOf<String, String>()
        val first = persistedPlaybackClientId(store::get, store::set)
        val relaunched = persistedPlaybackClientId(store::get, store::set)
        assertEquals(first, relaunched)
        assertTrue(first.matches(Regex("app-[0-9a-f]{16}")), first)

        val elsewhere = persistedPlaybackClientId(mutableMapOf<String, String>()::get) { _, _ -> }
        assertNotEquals(first, elsewhere)
    }

    @Test
    fun `the app's own id is the one kept in its settings`() {
        assertEquals(PlaybackClient.id, persistedPlaybackClientId(Settings::getString, Settings::putString))
    }
}
