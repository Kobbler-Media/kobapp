package dev.kobbler.app.data

import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.ui.player.StubServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Served by kobbler at /player/, the app signs in as whoever the browser's kobbler session is. */
class HostedSessionTest {

    private class Rig(val auth: AuthState, val mints: AtomicInteger, val signIns: List<String>)

    private fun hosted(
        stored: (apiUrl: String) -> List<Profile> = { emptyList() },
        current: String? = null,
        cookieUser: String? = "dad",
        test: suspend Rig.(apiUrl: String) -> Unit,
    ) = runBlocking {
        val mints = AtomicInteger()
        val signIns = mutableListOf<String>()
        StubServer(
            routes = mapOf(
                "/api/v1/admin/me" to { exchange ->
                    val bearer = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                    val user = bearer?.removePrefix("minted-") ?: cookieUser
                    """{"user_id":"$user","email":"$user@home","role":"viewer"}"""
                },
                "/api/v1/admin/auth/player-token" to { _ ->
                    mints.incrementAndGet()
                    """{"token":"minted-$cookieUser","expires_at":"2027-01-01T00:00:00Z"}"""
                },
                "/api/v1/admin/sessions/refresh" to { _ -> "{}" },
            ),
            codes = if (cookieUser == null) mapOf("/api/v1/admin/me" to 401) else emptyMap(),
        ).use { stub ->
            val store = InMemoryKeyValueStore().apply {
                putString("kobbler_profiles_v1", Json.encodeToString(stored(stub.url)))
                current?.let { putString("kobbler_current_profile_id", it) }
                putString("kobbler_show_picker_on_launch", "true")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val host = HostedServer(stub.url) { signIns += it }
                val auth = AuthState(scope, FormFactor.Desktop, store, host)
                withTimeout(5.seconds) { while (auth.screen == AuthScreen.Loading) delay(5) }
                Rig(auth, mints, signIns).test(stub.url)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `first visit mints a token for the browser's user and skips the picker`() = hosted { apiUrl ->
        assertEquals(AuthScreen.LoggedIn, auth.screen)
        assertEquals(1, mints.get())
        val profile = auth.currentProfile!!
        assertEquals("minted-dad", profile.token)
        assertEquals(apiUrl, profile.apiUrl)
        assertEquals("dad", profile.userId)
    }

    @Test
    fun `a profile already linked for the browser's user is reused`() = hosted(
        stored = { listOf(Profile(id = "p1", userId = "dad", token = "dad", apiUrl = it)) },
    ) {
        assertEquals(AuthScreen.LoggedIn, auth.screen)
        assertEquals(0, mints.get())
        assertEquals("p1", auth.currentProfileId)
    }

    @Test
    fun `a different browser user gets their own profile`() = hosted(
        stored = { listOf(Profile(id = "k1", userId = "kid", token = "kid", apiUrl = it)) },
        current = "k1",
    ) {
        assertEquals(1, mints.get())
        assertEquals("minted-dad", auth.currentProfile!!.token)
        assertEquals(setOf("k1", auth.currentProfileId), auth.profiles.map { it.id }.toSet())
    }

    @Test
    fun `no kobbler session sends the browser to sign in`() = hosted(cookieUser = null) { apiUrl ->
        withTimeout(5.seconds) { while (signIns.isEmpty()) delay(5) }
        assertEquals(listOf(apiUrl), signIns)
        assertEquals(0, mints.get())
        assertNull(auth.currentProfile)
    }
}
