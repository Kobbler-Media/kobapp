package dev.kobbler.kobplayer

import kotlin.math.roundToInt

fun videoFrameSize(
    ratio: AspectRatio,
    videoWidth: Int,
    videoHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
): Pair<Int, Int> {
    if (ratio == AspectRatio.STRETCH || videoWidth <= 0 || videoHeight <= 0) {
        return containerWidth to containerHeight
    }
    val sx = containerWidth.toFloat() / videoWidth
    val sy = containerHeight.toFloat() / videoHeight
    val scale = if (ratio == AspectRatio.FIT) minOf(sx, sy) else maxOf(sx, sy)
    return (videoWidth * scale).roundToInt() to (videoHeight * scale).roundToInt()
}

fun displaySize(width: Int, height: Int, sarNum: Int, sarDen: Int): Pair<Int, Int> {
    if (sarNum <= 0 || sarDen <= 0) return width to height
    return (width.toLong() * sarNum / sarDen).toInt() to height
}
