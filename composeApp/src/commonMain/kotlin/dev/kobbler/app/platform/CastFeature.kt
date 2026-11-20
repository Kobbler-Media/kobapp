package dev.kobbler.app.platform

/** What a receiver does for itself, so the app knows what it has to do in its place. */
enum class CastFeature {
    /** Saves the watch position against the server on its own. */
    ReceiverReportsProgress,

    /** Plays an HLS playlist; without it a transcode is asked for as one progressive stream. */
    Hls,

    /** Takes a whole book and plays its files in turn. */
    Audiobook,

    /** Plays a read-aloud stream. */
    Tts,
}
