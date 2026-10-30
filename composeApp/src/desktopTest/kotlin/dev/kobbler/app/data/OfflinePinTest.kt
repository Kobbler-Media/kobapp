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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A PIN-locked profile has to open with no network, or nothing downloaded on it can be reached. */
class OfflinePinTest {
    private fun withAuth(test: suspend (AuthState, StubServer) -> Unit) = runBlocking {
        val stub = StubServer(
            mapOf("/api/v1/admin/me/verify-pin" to { exchange -> "" }),
            codeFor = { path, body -> if (path.endsWith("verify-pin")) (if ("1234" in body) 204 else 403) else null },
        )
        val store = InMemoryKeyValueStore().apply {
            putString("kobbler_profiles_v1", Json.encodeToString(listOf(Profile(id = "kid", userId = "kid", token = "t", apiUrl = stub.url, hasPin = true))))
            putString("kobbler_current_profile_id", "kid")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val auth = AuthState(scope, FormFactor.Phone, store)
            withTimeout(5.seconds) { while (auth.screen == AuthScreen.Loading) delay(5) }
            test(auth, stub)
        } finally {
            scope.cancel()
            stub.close()
        }
    }

    @Test
    fun aPinTheServerAcceptedStillUnlocksWithNoNetwork() = withAuth { auth, stub ->
        assertTrue(auth.verifyPin("kid", "1234"))
        stub.close()
        assertTrue(auth.verifyPin("kid", "1234"))
        assertFalse(auth.verifyPin("kid", "9999"))
    }

    @Test
    fun aPinNeverCheckedOnlineIsNotTakenOnTrustOffline() = withAuth { auth, stub ->
        stub.close()
        assertFalse(auth.verifyPin("kid", "1234"))
    }

    @Test
    fun aRejectedPinIsNotRememberedAsGood() = withAuth { auth, stub ->
        assertFalse(auth.verifyPin("kid", "9999"))
        stub.close()
        assertFalse(auth.verifyPin("kid", "9999"))
    }
}
