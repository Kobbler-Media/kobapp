package dev.kobbler.app.ui

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.graphics.Color

internal fun focusRippleLocals(keyed: Boolean): Array<ProvidedValue<*>> = arrayOf(
    LocalRippleThemeConfiguration provides
        if (keyed) RippleDefaults.ThemeConfiguration else RippleDefaults.InsetFocusRingThemeConfiguration,
    LocalRippleConfiguration provides if (keyed) KeyedRipple else PointedRipple,
)

private val KeyedRipple = RippleConfiguration()

// Material's own focus highlight: a pressed button keeps focus, and its tint would stay
// on long after the finger has gone. Material has no switch for focus alone, so pointer
// mode draws focus as inset rings and makes them invisible.
private val PointedRipple = RippleConfiguration(
    focus = RippleConfiguration.Focus.InsetRing(Color.Transparent, Color.Transparent),
)
