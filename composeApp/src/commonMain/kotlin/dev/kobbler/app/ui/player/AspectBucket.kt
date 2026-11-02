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

/** The bucket of what is on screen: the picture, when the encode has bars round it. */
fun aspectBucket(width: Int, height: Int, picture: dev.kobbler.app.data.PictureArea?): String? =
    if (picture == null) aspectBucket(width, height)
    else aspectBucket((width * picture.width).toInt(), (height * picture.height).toInt())
