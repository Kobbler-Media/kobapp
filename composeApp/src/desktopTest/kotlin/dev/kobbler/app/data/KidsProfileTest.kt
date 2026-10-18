package dev.kobbler.app.data

import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.ui.navigation.Destination
import dev.kobbler.app.ui.navigation.defaultNavConfig
import dev.kobbler.app.ui.navigation.navConfigFor
import dev.kobbler.app.ui.player.StubServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A Kids profile is the server's call; the app learns it from /me and stops offering live TV. */
class KidsProfileTest {

    private fun signedIn(isKids: Boolean): Boolean = runBlocking {
        StubServer(
            mapOf("/api/v1/admin/me" to { _ -> """{"user_id":"u1","email":"kid@home","role":"viewer","is_kids":$isKids}""" }),
        ).use { stub ->
            val profile = Profile(id = "kid", token = "t", apiUrl = stub.url, isKids = !isKids)
            val store = InMemoryKeyValueStore().apply {
                putString("kobbler_profiles_v1", Json.encodeToString(listOf(profile)))
                putString("kobbler_current_profile_id", profile.id)
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val auth = AuthState(scope, FormFactor.Desktop, store)
                withTimeout(5.seconds) { while (auth.currentProfile?.email != "kid@home") delay(5) }
                auth.isKids
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `me marks the profile kids or not`() {
        assertEquals(true, signedIn(isKids = true))
        assertEquals(false, signedIn(isKids = false))
    }

    @Test
    fun `kids navigation has no live tv, iptv, sports or activity anywhere`() {
        val hidden = setOf(Destination.Tab.LiveTv, Destination.Tab.Iptv, Destination.Tab.Activity, Destination.Tab.Sports)
        val kids = navConfigFor(isKids = true)
        for (tabs in listOf(kids.bottomBarTabs, kids.drawerTabs, kids.sidebarTabs)) {
            assertTrue(tabs.none { it in hidden }, "$tabs")
            assertTrue(Destination.Tab.Movies in tabs, "$tabs")
        }
        assertEquals(defaultNavConfig, navConfigFor(isKids = false))
    }
}
