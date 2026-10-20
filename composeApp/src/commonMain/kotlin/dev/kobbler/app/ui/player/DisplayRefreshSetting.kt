package dev.kobbler.app.ui.player

import dev.kobbler.app.platform.Settings

// Device-level, not per profile: the refresh rates on offer belong to the screen.
const val KEY_MATCH_DISPLAY_REFRESH = "pref_match_display_refresh"

fun matchDisplayRefreshSetting(): Boolean = Settings.getString(KEY_MATCH_DISPLAY_REFRESH) == "true"
