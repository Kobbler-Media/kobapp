package dev.kobbler.app.platform

/** What a receiver does for itself, so the app knows what it has to do in its place. */
enum class CastFeature(val wire: String) {
    /** Saves the watch position against the server on its own. */
    ReceiverReportsProgress("receiver_reports_progress"),

    /** Plays an HLS playlist; without it a transcode is asked for as one progressive stream. */
    Hls("hls"),

    /** Takes a whole book and plays its files in turn. */
    Audiobook("audiobook"),

    /** Plays a read-aloud stream. */
    Tts("tts");

    companion object {
        fun fromWire(name: String): CastFeature? = entries.firstOrNull { it.wire == name }
    }
}
