package dev.kobbler.kobplayer

enum class AudioOutputMode { AUTO, STEREO, SURROUND, PASSTHROUGH }

// android.media.AudioFormat values; libVLC's "encoded:<mask>" device takes 1 << encoding.
const val ENCODING_AC3 = 5
const val ENCODING_E_AC3 = 6
const val ENCODING_DTS = 7
const val ENCODING_DTS_HD = 8
const val ENCODING_DOLBY_TRUEHD = 14
const val ENCODING_E_AC3_JOC = 18

val PASSTHROUGH_ENCODINGS = setOf(
    ENCODING_AC3, ENCODING_E_AC3, ENCODING_DTS, ENCODING_DTS_HD, ENCODING_DOLBY_TRUEHD, ENCODING_E_AC3_JOC,
)

fun encodingMask(vararg encodings: Int): Long =
    encodings.filter { it in PASSTHROUGH_ENCODINGS }.fold(0L) { mask, e -> mask or (1L shl e) }

private fun vlcCodecEncoding(codec: String?): Int? = when (codec?.trim()) {
    "a52" -> ENCODING_AC3
    "eac3" -> ENCODING_E_AC3
    "dts" -> ENCODING_DTS
    "mlp", "trhd" -> ENCODING_DOLBY_TRUEHD
    else -> null
}

/**
 * The libVLC audiotrack device for [codec] on a sink accepting [passthroughMask].
 *
 * AUTO never sends multichannel PCM. An HDMI sink can advertise it and still play it as
 * silence — typically a TV relaying a soundbar's EDID over ARC — while stereo PCM and
 * bitstream both get through.
 */
fun audioOutputDevice(mode: AudioOutputMode, codec: String?, passthroughMask: Long): String {
    val encoded = "encoded:$passthroughMask"
    return when (mode) {
        AudioOutputMode.STEREO -> "stereo"
        AudioOutputMode.SURROUND -> "pcm"
        AudioOutputMode.PASSTHROUGH -> if (passthroughMask != 0L) encoded else "pcm"
        AudioOutputMode.AUTO -> {
            val encoding = vlcCodecEncoding(codec)
            if (encoding != null && passthroughMask and (1L shl encoding) != 0L) encoded else "stereo"
        }
    }
}
