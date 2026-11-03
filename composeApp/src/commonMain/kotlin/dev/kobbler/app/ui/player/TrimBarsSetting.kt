package dev.kobbler.app.ui.player

import dev.kobbler.app.data.PictureArea
import dev.kobbler.app.platform.Settings
import dev.kobbler.kobplayer.PictureCrop

// Device-level, not per profile: whether bars are worth trimming depends on the screen's shape.
const val KEY_TRIM_BLACK_BARS = "pref_trim_black_bars"

fun trimBlackBarsSetting(): Boolean = Settings.getString(KEY_TRIM_BLACK_BARS) == "true"

fun PictureArea.toCrop() = PictureCrop(x, y, width, height)
