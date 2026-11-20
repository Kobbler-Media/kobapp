package dev.kobbler.app.platform

enum class PlaybackProtocol(val label: String) { GoogleCast("Chromecast"), Dlna("DLNA") }

enum class PlaybackTargetKind { Tv, Speaker, Group, Other }

/** One TV can be listed once per protocol it answers on; [protocol] tells the entries apart. */
data class PlaybackTarget(
    val id: String,
    val name: String,
    val kind: PlaybackTargetKind,
    val protocol: PlaybackProtocol,
)
