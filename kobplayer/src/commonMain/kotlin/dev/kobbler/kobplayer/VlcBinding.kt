package dev.kobbler.kobplayer

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal enum class VlcInputState { Idle, Opening, Playing, Paused, Stopping, Stopped, Error }

internal enum class VlcTrackType { Audio, Video, Text }

/** One libVLC 4 player, as each platform's binding exposes it. Called only from its [CommandLane]. Times in µs. */
internal interface VlcBinding {
    val inputState: VlcInputState
    val timeUs: Long
    fun play()
    fun setPause(paused: Boolean)
    fun stop()
    fun setTime(us: Long, fast: Boolean = false)
    fun setRate(rate: Float)
    fun tracks(type: VlcTrackType): List<TrackInfo>
    fun selectedTrackId(type: VlcTrackType): String?
    fun selectTrack(type: VlcTrackType, id: String)
    fun deselect(type: VlcTrackType)
    fun chapters(): List<Chapter>
    fun setChapter(index: Int)
    fun sleep(ms: Long)
}

private const val StopWaitMs = 5_000L
private val inputGone = setOf(VlcInputState.Idle, VlcInputState.Stopped, VlcInputState.Error)

// Media handed to a libVLC 4 player that is still playing is swapped in gaplessly: the
// player never leaves its playing state, so the new title reports no opening, playing,
// pause or end, and nothing marks where the old input's events stop.
internal fun VlcBinding.stopAndWait() {
    if (inputState in inputGone) return
    stop()
    val deadline = TimeSource.Monotonic.markNow() + StopWaitMs.milliseconds
    while (inputState !in inputGone && deadline.hasNotPassedNow()) sleep(10)
}

internal fun vlcMediaOptions(source: MediaSource.Url, config: PlayerConfig): List<String> {
    val headers = source.headers.mapKeys { it.key.lowercase() }
    return buildList {
        add("http-user-agent=${headers["user-agent"] ?: config.httpUserAgent}")
        add("network-caching=${source.networkCacheMs ?: config.networkCacheMs}")
        add(vlcDemuxOption(source))
        if (config.audioOnly) add("no-video")
        headers["referer"]?.let { add("http-referrer=$it") }
        source.audioLanguage?.let { add("audio-language=$it") }
        // `none` rather than `no-spu`: that disables the whole subtitle pipeline, so a viewer
        // turning subtitles on later would be refused silently. This only declines the automatic pick.
        if (source.subtitlesOff) add("sub-language=none")
        else source.subtitleOrdinal?.let { add("sub-track=$it") }
    }
}
