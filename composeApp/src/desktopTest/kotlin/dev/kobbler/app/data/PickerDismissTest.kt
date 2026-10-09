package dev.kobbler.app.data

import dev.kobbler.app.platform.FormFactor
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
import kotlin.time.Duration.Companion.seconds

class PickerDismissTest {
    // Port 1 refuses at once, so the launch-time server refresh fails like being offline.
    private val alice = Profile(id = "alice", token = "t", apiUrl = "http://127.0.0.1:1")
    private val bob = Profile(id = "bob", token = "t", apiUrl = "http://127.0.0.1:1")

    private val launchPickers = listOf(
        "picker on launch" to (alice to true),
        "pin-locked profile" to (alice.copy(hasPin = true) to false),
    )

    private fun launched(current: Profile, showPickerOnLaunch: Boolean, body: (AuthState) -> Unit) = runBlocking {
        val store = InMemoryKeyValueStore().apply {
            putString("kobbler_profiles_v1", Json.encodeToString(listOf(current, bob)))
            putString("kobbler_current_profile_id", current.id)
            putString("kobbler_show_picker_on_launch", showPickerOnLaunch.toString())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val auth = AuthState(scope, FormFactor.TV, store)
            withTimeout(5.seconds) { while (auth.screen == AuthScreen.Loading) delay(5) }
            body(auth)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun launchPickerBackStaysOnPicker() {
        for ((name, setup) in launchPickers) launched(setup.first, setup.second) { auth ->
            assertEquals(AuthScreen.Picker, auth.screen, name)
            assertEquals(false, auth.pickerDismissible, name)
            auth.dismissPicker()
            assertEquals(AuthScreen.Picker, auth.screen, name)
        }
    }

    @Test
    fun launchPickerCancelledLinkReturnsToPicker() {
        for ((name, setup) in launchPickers) launched(setup.first, setup.second) { auth ->
            auth.cancelPinLogin()
            assertEquals(AuthScreen.Picker, auth.screen, name)
        }
    }

    @Test
    fun switchPickerReturnsToSession() = launched(alice, showPickerOnLaunch = false) { auth ->
        assertEquals(AuthScreen.LoggedIn, auth.screen)

        auth.showPicker()
        assertEquals(true, auth.pickerDismissible)
        auth.dismissPicker()
        assertEquals(AuthScreen.LoggedIn, auth.screen)

        auth.showPicker()
        auth.cancelPinLogin()
        assertEquals(AuthScreen.LoggedIn, auth.screen)
    }
}
