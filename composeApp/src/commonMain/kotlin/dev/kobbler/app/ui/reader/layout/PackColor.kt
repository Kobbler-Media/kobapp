package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

internal fun parseHexColor(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    val expanded = when (hex.length) {
        3, 4 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val argb = expanded.toLongOrNull(16) ?: return null
    return if (expanded.length == 6) Color(0xFF000000 or argb) else {
        val rgb = argb ushr 8
        val a = argb and 0xFF
        Color((a shl 24) or rgb)
    }
}

// Books routinely hard-code black text; on a dark theme that would be invisible.
internal fun readableOn(background: Color, color: Color): Boolean {
    val l1 = color.luminance()
    val l2 = background.luminance()
    return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f) >= 3f
}
