package dev.kobbler.app

internal data class StopSignals(
    val pipJustExited: Boolean = false,
    val finishing: Boolean = false,
    val inPip: Boolean = false,
    val screenOn: Boolean,
)

// Turning the screen off stops a PiP window that stays in PiP, which is otherwise exactly
// how a close button that leaves the mode change for later looks; nobody closes a window
// they cannot see, so only a finish counts while the screen is off.
internal fun StopSignals.dismissesPip(): Boolean = finishing || (screenOn && (pipJustExited || inPip))
