package dev.kobbler.kobplayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val Prefs = "kobplayer.decoder"
private const val VerdictKey = "field_picture_verdict"
private const val VerdictForKey = "field_picture_verdict_for"

// Bump when the clips change, so every device answers for the new ones.
private const val SelfTestVersion = 1

private const val DecodeTimeoutMs = 2_000L
private const val DequeueTimeoutUs = 10_000L

// 64x64 white, one IDR, x264 main with its SEI stripped. Identical but for the
// interlaced one's `-flags +ildct+ilme -x264opts tff=1`, which is what makes it MBAFF.
private const val ProgressiveClip =
    "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAALpbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAyAAAAAgAAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAjh0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAEAAAABAAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAIAAAAAAAABAAAAAAGwbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAyAAAAAgBVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABW21pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAARtzdGJsAAAAt3N0c2QAAAAAAAAAAQAAAKdhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAQABIAAAASAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGP//AAAALWF2Y0MBTUAK/+EAFWdNQArcQmwEQAAAAwBAAAAMg8SJ4AEABWjuDyyAAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAB+LAAABwgAAAAGHN0dHMAAAAAAAAAAQAAAAEAAAIAAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAABAAAAAQAAABRzdHN6AAAAAAAAACQAAAABAAAAFHN0Y28AAAAAAAAAAQAAAxkAAAA9dWR0YQAAADVtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAAAhpbHN0AAAACGZyZWUAAAAsbWRhdAAAACBliIQEv/73rd+BTcBA7Wu6Vzi0la3kA5MtsETUVp4u9w=="

private const val InterlacedClip =
    "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAALqbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAyAAAAAgAAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAjl0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAEAAAABAAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAIAAAAAAAABAAAAAAGxbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAyAAAAAgBVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABXG1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAARxzdGJsAAAAuHN0c2QAAAAAAAAAAQAAAKhhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAQABIAAAASAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAGP//AAAALmF2Y0MBTUAV/+EAFmdNQBX4ibARAAADAAEAAAMAMh8UK4ABAAVo/g8sgAAAABBwYXNwAAAAAQAAAAEAAAAUYnRydAAAAAAAAgIQAAAc6AAAABhzdHRzAAAAAAAAAAEAAAABAAACAAAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEAAAAUc3RzegAAAAAAAAAlAAAAAQAAABRzdGNvAAAAAAAAAAEAAAMaAAAAPXVkdGEAAAA1bWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAIaWxzdAAAAAhmcmVlAAAALW1kYXQAAAAhZYiCLAS/9vet34FNwEDzMFEm5XwOUg8f9YGK0Y+fUvX/"

/**
 * This device's [FieldDecodeVerdict], shared by every player in the process.
 *
 * Kept per system build and app version: the decoder is firmware, so an OTA can fix or
 * break it.
 */
internal object DeviceFieldDecode {
    @Volatile
    private var cached: FieldDecodeVerdict? = null
    private val selfTestStarted = AtomicBoolean(false)

    // Before any player has loaded the stored verdict, the tune-time probe is the safety net.
    val current: FieldDecodeVerdict get() = cached ?: FieldDecodeVerdict.Unknown

    fun verdict(context: Context): FieldDecodeVerdict =
        cached ?: stored(context).also { cached = it }

    // The app's version too: it carries the libVLC that hands the decoder its stream.
    private fun buildKey(context: Context): String {
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) app.longVersionCode else @Suppress("DEPRECATION") app.versionCode.toLong()
        return "${Build.FINGERPRINT}#$appVersion#$SelfTestVersion"
    }

    fun remember(context: Context, verdict: FieldDecodeVerdict) {
        if (verdict == FieldDecodeVerdict.Unknown || verdict == verdict(context)) return
        cached = verdict
        // The one line that says why a device is on the slow path, or why it stopped being.
        println("[FieldDecode] this device: $verdict")
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
            .edit()
            .putString(VerdictKey, verdict.name)
            .putString(VerdictForKey, buildKey(context))
            .apply()
    }

    /** Off the main thread, once per process, and only while this build has no answer. */
    fun selfTestIfUnknown(context: Context) {
        val app = context.applicationContext
        if (verdict(app) != FieldDecodeVerdict.Unknown || !selfTestStarted.compareAndSet(false, true)) return
        thread(name = "FieldDecodeSelfTest", isDaemon = true) {
            remember(app, selfTestVerdict(selfTest()))
        }
    }

    private fun stored(context: Context): FieldDecodeVerdict {
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        if (prefs.getString(VerdictForKey, null) != buildKey(context)) return FieldDecodeVerdict.Unknown
        val name = prefs.getString(VerdictKey, null)
        return FieldDecodeVerdict.entries.firstOrNull { it.name == name } ?: FieldDecodeVerdict.Unknown
    }

    private fun selfTest(): SelfTestRun? {
        val decoder = hardwareAvcDecoder() ?: return null
        return SelfTestRun(
            progressive = decode(decoder, ProgressiveClip),
            interlaced = decode(decoder, InterlacedClip),
        )
    }

    // The one VLC's mediacodec module would pick: the first hardware AVC decoder listed.
    private fun hardwareAvcDecoder(): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                info.isHardware()
        }?.name

    private fun MediaCodecInfo.isHardware(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated && !isAlias
        } else {
            !name.startsWith("OMX.google.") && !name.startsWith("c2.android.") && !name.endsWith(".secure")
        }

    private fun decode(decoder: String, clip: String): DecodedClip {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(BytesSource(Base64.decode(clip, Base64.DEFAULT)))
            extractor.selectTrack(0)
            codec = MediaCodec.createByCodecName(decoder).apply {
                configure(extractor.getTrackFormat(0), null, null, 0)
                start()
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.currentTimeMillis() + DecodeTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(DequeueTimeoutUs)
                    if (input >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, DequeueTimeoutUs)
                if (output < 0) continue
                if (info.size > 0) {
                    val luma = codec.getOutputImage(output)?.use { image -> meanLuma(image) }
                    codec.releaseOutputBuffer(output, false)
                    return DecodedClip(frames = 1, meanLuma = luma)
                }
                codec.releaseOutputBuffer(output, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            return DecodedClip(frames = 0, meanLuma = null)
        } catch (e: Exception) {
            println("[FieldDecode] self-test on $decoder failed: $e")
            return DecodedClip(frames = 0, meanLuma = null)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun meanLuma(image: android.media.Image): Int {
        val plane = image.planes[0]
        val buffer = plane.buffer
        var sum = 0L
        var count = 0
        for (row in 0 until image.height step 4) {
            for (col in 0 until image.width step 4) {
                sum += buffer.get(row * plane.rowStride + col * plane.pixelStride).toInt() and 0xff
                count++
            }
        }
        return if (count == 0) 0 else (sum / count).toInt()
    }
}

private class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(size, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() {}
}
