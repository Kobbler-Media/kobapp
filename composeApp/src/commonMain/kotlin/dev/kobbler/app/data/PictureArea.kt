package dev.kobbler.app.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Where the picture sits inside the frame when bars are baked into the encode, as fractions of it. */
@Immutable
@Serializable
data class PictureArea(val x: Double, val y: Double, val width: Double, val height: Double)
