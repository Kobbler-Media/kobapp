package dev.kobbler.app.platform

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

expect fun bookFont(key: String, bytes: ByteArray, weight: FontWeight, style: FontStyle): Font?
