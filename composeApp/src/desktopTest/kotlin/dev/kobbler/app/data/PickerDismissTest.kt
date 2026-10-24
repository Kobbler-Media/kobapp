package dev.kobbler.app.data

import dev.kobbler.app.runUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.ui.auth.ProfilePickerScreen
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
    fun launchShowsThePicker() {
        for ((name, setup) in launchPickers) launched(setup.first, setup.second) { auth ->
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

    /** Switching profile is a profile logout: the picker it opens is the launch picker, with no way back. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun switchPickerHasNoWayBackToTheSession() = launched(alice, showPickerOnLaunch = false) { auth ->
        assertEquals(AuthScreen.LoggedIn, auth.screen)

        auth.showPicker()
        assertEquals(AuthScreen.Picker, auth.screen)
        runUiTest {
            setContent { ProfilePickerScreen(auth, FormFactor.TV) }
            waitForIdle()
            onAllNodesWithContentDescription("Back").assertCountEquals(0)
        }

        auth.cancelPinLogin()
        assertEquals(AuthScreen.Picker, auth.screen)
    }
}
