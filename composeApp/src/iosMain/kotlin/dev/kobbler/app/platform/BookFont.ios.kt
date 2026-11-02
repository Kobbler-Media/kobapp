package dev.kobbler.app.platform

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

actual fun bookFont(key: String, bytes: ByteArray, weight: FontWeight, style: FontStyle): Font? =
    androidx.compose.ui.text.platform.Font(key, bytes, weight, style)
