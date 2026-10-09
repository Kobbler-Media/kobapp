package dev.kobbler.kobplayer

import kotlin.math.roundToInt
import kotlin.math.sign

fun videoFrameSize(
    ratio: AspectRatio,
    videoWidth: Int,
    videoHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
): Pair<Int, Int> {
    if (videoWidth <= 0 || videoHeight <= 0) {
        return containerWidth to containerHeight
    }
    // A fill mode is chosen for one screen shape and remembered per stream shape; held
    // across a rotation it blows a landscape picture up to a portrait screen's height.
    val turned = (videoWidth - videoHeight).sign * (containerWidth - containerHeight).sign < 0
    val effective = if (turned) AspectRatio.FIT else ratio
    if (effective == AspectRatio.STRETCH) return containerWidth to containerHeight
    val sx = containerWidth.toFloat() / videoWidth
    val sy = containerHeight.toFloat() / videoHeight
    val scale = if (effective == AspectRatio.FIT) minOf(sx, sy) else maxOf(sx, sy)
    return (videoWidth * scale).roundToInt() to (videoHeight * scale).roundToInt()
}

fun displaySize(width: Int, height: Int, sarNum: Int, sarDen: Int): Pair<Int, Int> {
    if (sarNum <= 0 || sarDen <= 0) return width to height
    return (width.toLong() * sarNum / sarDen).toInt() to height
}
