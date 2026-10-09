package dev.kobbler.kobplayer

sealed interface VideoDecoding {
    val key: String

    data object Auto : VideoDecoding {
        override val key = "auto"
    }

    data object Software : VideoDecoding {
        override val key = "software"
    }

    data class Gpu(val device: String) : VideoDecoding {
        override val key get() = device
    }

    companion object {
        fun fromKey(key: String?): VideoDecoding = when {
            key == Software.key -> Software
            key != null && key.startsWith("/dev/dri/") -> Gpu(key)
            else -> Auto
        }
    }
}

data class VideoDecoderChoice(val decoding: VideoDecoding, val label: String)

/** What this device can be told to decode on; empty where the platform decides for itself. */
expect fun videoDecoderChoices(): List<VideoDecoderChoice>
