package dev.kobbler.app.ui.player

import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Device-level, not per profile: whether a floating window is welcome belongs to the screen.
const val KEY_PICTURE_IN_PICTURE = "pref_picture_in_picture"

// TV and desktop installs predate the setting and have nothing stored, so they must read
// as off rather than inheriting the old always-on behaviour.
internal fun pictureInPictureEnabled(formFactor: FormFactor, stored: String?): Boolean =
    stored?.toBooleanStrictOrNull() ?: (formFactor == FormFactor.Phone)

fun pictureInPictureSetting(formFactor: FormFactor): Boolean =
    pictureInPictureEnabled(formFactor, Settings.getString(KEY_PICTURE_IN_PICTURE))

const val KEY_MINI_PLAYER_ON_BACK = "pref_mini_player_on_back"

internal fun miniPlayerOnBackEnabled(stored: String?): Boolean = stored?.toBooleanStrictOrNull() ?: false

fun miniPlayerOnBackSetting(): Boolean = miniPlayerOnBackEnabled(Settings.getString(KEY_MINI_PLAYER_ON_BACK))

/**
 * Whether leaving the app by Home may shrink the player into a system window; the Android
 * activity reads it for both auto-enter and onUserLeaveHint. Only a fullscreen player that
 * is playing arms it, so BACK, which leaves that player, never can.
 */
object PictureInPicture {
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    fun follow(formFactor: FormFactor, isPlaying: Boolean) {
        _armed.value = isPlaying && pictureInPictureSetting(formFactor)
    }

    fun disarm() {
        _armed.value = false
    }
}
