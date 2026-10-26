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
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Every linked profile mirrors its server-side record, not just the one in use. */
class LinkedProfileRefreshTest {

    private class Household(val auth: AuthState, val store: KeyValueStore, val avatars: MutableMap<String, String>)

    private fun avatarOf(user: String) = "/api/v1/admin/users/$user/avatar"

    private fun household(
        showPicker: Boolean,
        avatars: Map<String, String> = emptyMap(),
        test: suspend Household.() -> Unit,
    ) = runBlocking {
        val live = ConcurrentHashMap(avatars)
        fun avatarJson(user: String) = live[user]?.let { "\"$it\"" } ?: "null"
        StubServer(
            mapOf(
                "/api/v1/admin/me" to { exchange ->
                    val user = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
                    """{"user_id":"$user","email":"$user@home","role":"viewer","display_name":"$user","avatar_url":${avatarJson(user)}}"""
                },
                "/api/v1/admin/users" to { _ ->
                    listOf("dad", "kid", "gran", "aunt").joinToString(",", "[", "]") {
                        """{"id":"$it","email":"$it@home","role":"viewer","avatar_url":${avatarJson(it)}}"""
                    }
                },
                "/api/v1/admin/sessions/refresh" to { _ -> "{}" },
            ),
        ).use { stub ->
            val profiles = listOf("dad", "kid", "gran").map { Profile(id = it, userId = it, token = it, apiUrl = stub.url) }
            val store = InMemoryKeyValueStore().apply {
                putString("kobbler_profiles_v1", Json.encodeToString(profiles))
                putString("kobbler_current_profile_id", "dad")
                putString("kobbler_show_picker_on_launch", showPicker.toString())
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val auth = AuthState(scope, FormFactor.TV, store)
                withTimeout(5.seconds) { while (auth.screen == AuthScreen.Loading) delay(5) }
                Household(auth, store, live).test()
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun AuthState.awaitName(id: String) =
        withTimeout(5.seconds) { while (profiles.first { it.id == id }.name != id) delay(5) }

    private fun AuthState.avatar(id: String) = profiles.first { it.id == id }.avatarUrl

    @Test
    fun `launch loads every linked profile's avatar, not just the active one`() = household(
        showPicker = true,
        avatars = mapOf("dad" to avatarOf("dad"), "kid" to avatarOf("kid")),
    ) {
        listOf("dad", "kid", "gran").forEach { auth.awaitName(it) }
        assertEquals(avatarOf("dad"), auth.avatar("dad"))
        assertEquals(avatarOf("kid"), auth.avatar("kid"))
        assertEquals(null, auth.avatar("gran"))
        val persisted = Json.decodeFromString<List<Profile>>(store.getString("kobbler_profiles_v1")!!)
        assertEquals(avatarOf("kid"), persisted.first { it.id == "kid" }.avatarUrl)
    }

    @Test
    fun `opening the picker picks up avatars set since launch`() = household(showPicker = false) {
        listOf("dad", "kid", "gran").forEach { auth.awaitName(it) }
        avatars["gran"] = avatarOf("gran")
        auth.showPicker()
        withTimeout(5.seconds) { while (auth.avatar("gran") == null) delay(5) }
    }

    @Test
    fun `household roster carries each user's avatar`() = household(
        showPicker = false,
        avatars = mapOf("aunt" to avatarOf("aunt")),
    ) {
        val roster = auth.fetchRoster().associateBy { it.id }
        assertEquals(avatarOf("aunt"), roster.getValue("aunt").avatar_url)
        assertEquals(null, roster.getValue("kid").avatar_url)
    }
}
