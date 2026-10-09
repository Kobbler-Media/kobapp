package dev.kobbler.app.ui.player

fun aspectBucket(width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null
    val ratio = width.toDouble() / height.toDouble()
    return when {
        ratio < 1.5 -> "4:3"
        ratio < 1.95 -> "16:9"
        else -> "wide"
    }
}
