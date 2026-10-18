package dev.kobbler.kobplayer

import kotlin.math.abs
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

data class DecodedPicture(
    val width: Int,
    val height: Int,
    val visibleWidth: Int,
    val visibleHeight: Int,
)

fun decodedSurfaceSize(frameWidth: Int, frameHeight: Int, picture: DecodedPicture?): Pair<Int, Int> {
    if (picture == null || picture.width <= 0 || picture.height <= 0 ||
        picture.visibleWidth <= 0 || picture.visibleHeight <= 0
    ) {
        return frameWidth to frameHeight
    }
    return (frameWidth.toLong() * picture.width / picture.visibleWidth).toInt() to
        (frameHeight.toLong() * picture.height / picture.visibleHeight).toInt()
}

// 0 is "no vote" to Surface.setFrameRate. Above any panel's rate it is a timebase, not a rate.
// NTSC rates are voted as the whole rate they slow: the Pixel's selector files 23.976 under
// 60Hz, where film judders, and puts 24 on 24Hz.
fun contentFrameRate(num: Int, den: Int): Float {
    if (num <= 0 || den <= 0) return 0f
    val fps = num.toFloat() / den
    if (fps > MaxContentFrameRate) return 0f
    val whole = (fps * NtscSlowdown).roundToInt()
    return if (abs(whole / NtscSlowdown - fps) < 0.01f) whole.toFloat() else fps
}

private const val MaxContentFrameRate = 240f
private const val NtscSlowdown = 1.001f

// A few seconds of counting cannot tell 24 from 23.976, and the vote is the whole rate either way.
private val BroadcastFrameRates = floatArrayOf(24f, 25f, 30f, 50f, 60f)
private const val FrameRateSnapTolerance = 0.03f

fun measuredFrameRate(pictures: Long, elapsedMs: Long): Float {
    if (pictures <= 0 || elapsedMs <= 0) return 0f
    val fps = pictures * 1000f / elapsedMs
    val nearest = BroadcastFrameRates.minBy { abs(it - fps) }
    return if (abs(nearest - fps) / nearest <= FrameRateSnapTolerance) nearest else 0f
}
