package dev.kobbler.kobplayer

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.round
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

data class PictureCrop(val x: Double, val y: Double, val width: Double, val height: Double)

data class FrameRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun visibleIn(containerWidth: Int, containerHeight: Int): FrameRect {
        val l = maxOf(0, -left)
        val t = maxOf(0, -top)
        return FrameRect(l, t, minOf(width, containerWidth - left) - l, minOf(height, containerHeight - top) - t)
    }
}

fun videoFrameRect(
    ratio: AspectRatio,
    videoWidth: Int,
    videoHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
    crop: PictureCrop? = null,
): FrameRect {
    val area = crop?.takeIf { videoWidth > 0 && videoHeight > 0 && it.width > 0 && it.height > 0 }
    if (area == null) {
        val (w, h) = videoFrameSize(ratio, videoWidth, videoHeight, containerWidth, containerHeight)
        return FrameRect((containerWidth - w) / 2, (containerHeight - h) / 2, w, h)
    }
    val (pw, ph) = videoFrameSize(
        ratio,
        (videoWidth * area.width).roundToInt(),
        (videoHeight * area.height).roundToInt(),
        containerWidth,
        containerHeight,
    )
    val w = (pw / area.width).roundToInt()
    val h = (ph / area.height).roundToInt()
    return FrameRect(
        (containerWidth - pw) / 2 - (w * area.x).roundToInt(),
        (containerHeight - ph) / 2 - (h * area.y).roundToInt(),
        w,
        h,
    )
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

const val FrameRateProbeAttempts = 5

/**
 * Counts pictures over a stretch of clean playback, where [sample] answers (pictures shown,
 * clock) or null when the stretch is not clean. Waits on [state] rather than polling, so a
 * paused title costs nothing, and gives up after a few tries: a stream that never plays
 * cleanly at 1x would otherwise be probed for as long as it stays open.
 */
suspend fun probeFrameRate(
    state: StateFlow<PlayerState>,
    settleMs: Long,
    windowMs: Long,
    sample: suspend () -> Pair<Long, Long>?,
): Float {
    repeat(FrameRateProbeAttempts) {
        state.first { it == PlayerState.PLAYING }
        delay(settleMs)
        val start = sample() ?: return@repeat
        delay(windowMs)
        val end = sample() ?: return@repeat
        val rate = measuredFrameRate(end.first - start.first, end.second - start.second)
        if (rate > 0f) return rate
    }
    return 0f
}

// Android's guidance: a non-seamless refresh switch blanks the panel for a second or two, which
// only a movie-length watch repays. A trailer or hero preview stays on seamless switches.
private const val ModeSwitchMinDurationMs = 10 * 60_000L

fun worthAModeSwitch(durationMs: Long, live: Boolean): Boolean = live || durationMs >= ModeSwitchMinDurationMs

data class DisplayMode(val id: Int, val width: Int, val height: Int, val refreshHz: Float)

// 23.976 at 120Hz drifts a frame every eight seconds, which beats 3:2 pulldown; an exact
// multiple still wins where the display has one, as 59.94 does over 60.
private const val ExactCadence = 0.0002f
private const val CloseCadence = 0.002f

fun displayModeFor(modes: List<DisplayMode>, current: DisplayMode, frameRate: Float): Int? {
    if (frameRate <= 0f) return null
    fun drift(mode: DisplayMode): Float {
        val repeats = mode.refreshHz / frameRate
        return abs(repeats - round(repeats)) / repeats
    }
    val best = modes
        .filter { it.width == current.width && it.height == current.height && it.refreshHz >= frameRate * (1 - CloseCadence) }
        .filter { drift(it) <= CloseCadence }
        .minWithOrNull(compareBy<DisplayMode> { drift(it) > ExactCadence }.thenBy { it.refreshHz })
        ?: return null
    return best.id.takeIf { it != current.id }
}
