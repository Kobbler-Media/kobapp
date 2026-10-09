package dev.kobbler.kobplayer

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback state machine:
 * ```
 * IDLE → LOADING → BUFFERING → PLAYING ⇄ PAUSED → FINISHED
 *                                  ↓           ↓
 *                                ERROR       ERROR
 * ```
 * - LOADING: media URL set, connecting/preparing
 * - BUFFERING: had data, now stalled (network hiccup). Distinct from LOADING.
 * - VLC's internal PLAYING→READY transitions are absorbed — you never see them.
 */
enum class PlayerState {
    IDLE,
    LOADING,
    BUFFERING,
    PLAYING,
    PAUSED,
    FINISHED,
    ERROR,
}

data class TrackInfo(
    val id: String,
    val label: String,
    val language: String? = null,
)

sealed class MediaSource {
    data class Url(
        val url: String,
        /**
         * Only `user-agent` and `referer` are honoured: libVLC 3.x takes no others, and
         * nothing relays them any more. A credential belongs in the URL.
         */
        val headers: Map<String, String> = emptyMap(),
        /**
         * This source is field-coded — DVB-T broadcast is PAFF, one coded picture per
         * field.
         *
         * A fact about the stream, not an instruction to the player: what to do about it
         * belongs to whichever decoder is about to be handed the stream, and the answer
         * differs by silicon. See the Android player, which is where the one SoC that
         * cannot decode field pictures is named.
         */
        val fieldCoded: Boolean = false,
        /**
         * How deep to buffer before this source starts, in ms. Null takes
         * [PlayerConfig.networkCacheMs].
         *
         * Per-source because the number is about the path to the bytes, not about the
         * player: a file off a server on the same LAN needs a fraction of what a remote
         * one does, and one figure that suits a debrid CDN spends most of a second doing
         * nothing on every start and every seek at home. It is paid again on each seek,
         * which is what makes it the dominant cost of a resume.
         */
        val networkCacheMs: Int? = null,
        /**
         * Track selection applied as the stream opens rather than corrected afterwards.
         *
         * Correcting afterwards means waiting for the initial parse, which is not finished
         * until every decoder and the video output exist — long after the tracks
         * themselves are known.
         */
        val audioLanguage: String? = null,
        val subtitleOrdinal: Int? = null,
        val subtitlesOff: Boolean = false,
        /**
         * Where to start, in seconds, applied by the demuxer as the stream opens.
         *
         * Seeking afterwards means waiting for a duration to seek against, and a duration
         * arrives with the initial parse — which is the single longest step of a resume.
         * It also pays the network cache twice: once filling from the head of the file,
         * then again from the seek target, having thrown the first fill away.
         */
        val startTimeSec: Int? = null,
    ) : MediaSource()
}

data class PlayerConfig(
    val networkCacheMs: Int = 1500,
    val httpUserAgent: String = "Mozilla/5.0",
    val hardwareAcceleration: Boolean = true,
    /** When true, skip video surface allocation — audio playback only. */
    val audioOnly: Boolean = false,
    /** Debug-level backend logging (libvlc `-vv`). Costs thousands of logcat lines/sec during playback. */
    val verboseLogging: Boolean = false,
)

enum class AspectRatio {
    FIT,
    STRETCH,
    CROP,
}

data class Chapter(
    val name: String?,
    val offsetMs: Long,
    val durationMs: Long,
)

/**
 * Typed native player access. Each platform provides its own subclass.
 *
 * Use the convenience methods to safely access platform-specific players
 * from common code (returns null on the wrong platform):
 * ```
 * player.native.asVlcj()?.player?.chapters()?.setChapter(3)
 * ```
 *
 * Or use exhaustive `when` for platform-specific branching:
 * ```
 * when (val n = player.native) {
 *     is NativePlayer.Vlcj -> n.player.audio().equalizer()
 *     is NativePlayer.LibVlc -> ...
 *     is NativePlayer.VlcKit -> ...
 *     is NativePlayer.HtmlVideo -> ...
 *     is NativePlayer.None -> ...
 * }
 * ```
 */
sealed class NativePlayer {
    /** No native player available (stub platforms). */
    data object None : NativePlayer()

    fun asVlcj(): Vlcj? = this as? Vlcj
    fun asLibVlc(): LibVlc? = this as? LibVlc
    fun asVlcKit(): VlcKit? = this as? VlcKit
    fun asHtmlVideo(): HtmlVideo? = this as? HtmlVideo

    // Platform subclasses defined in platform source sets:
    // desktopMain:  class Vlcj(val player: EmbeddedMediaPlayer) : NativePlayer()
    // androidMain:  class LibVlc(val player: MediaPlayer, val libVLC: LibVLC) : NativePlayer()
    // iosMain:      class VlcKit(val player: VLCMediaPlayer) : NativePlayer()
    // wasmJsMain:   class HtmlVideo(val element: HTMLVideoElement) : NativePlayer()

    // Forward declarations so common code can reference them in `when` branches.
    // Properties are typed as Any here — use the platform-specific typed extensions below.
    class Vlcj internal constructor(val player: Any) : NativePlayer()
    class LibVlc internal constructor(val player: Any, val libVLC: Any) : NativePlayer()
    class VlcKit internal constructor(val player: Any) : NativePlayer()
    class HtmlVideo internal constructor(val element: Any) : NativePlayer()
}

/**
 * KobPlayer — a KMP media player interface.
 *
 * **Tier 1:** Use the properties and methods below for 90% of use cases.
 * Everything is a [StateFlow] — collect with `.collectAsState()` in Compose.
 *
 * **Tier 2:** Access `native` for the raw underlying player when you need
 * platform-specific APIs that aren't on this interface.
 */
@Stable
interface Player {
    // -- Playback state --
    val state: StateFlow<PlayerState>
    val isPlaying: StateFlow<Boolean>
    val currentMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    /** Playback progress 0.0..1.0, derived from currentMs/durationMs. */
    val progress: StateFlow<Float>
    val error: StateFlow<Throwable?>

    // -- Buffering --
    /** True when the player is waiting for data (LOADING or BUFFERING state). */
    val isBuffering: StateFlow<Boolean>
    /** How full the buffer is, 0.0..1.0. */
    val bufferPercent: StateFlow<Float>

    // -- Audio --
    /** Current volume, 0.0..[maxVolume]. */
    val volume: StateFlow<Float>
    /** Maximum volume. 2.0 on VLC platforms (supports amplification), 1.0 on web. */
    val maxVolume: Float
    val isMuted: StateFlow<Boolean>
    val audioTracks: StateFlow<List<TrackInfo>>
    val selectedAudioTrack: StateFlow<TrackInfo?>

    // -- Subtitles --
    val subtitleTracks: StateFlow<List<TrackInfo>>
    val selectedSubtitleTrack: StateFlow<TrackInfo?>

    // -- Chapters --
    val chapters: StateFlow<List<Chapter>>
    val currentChapter: StateFlow<Chapter?>

    // -- Speed --
    val playbackSpeed: StateFlow<Float>

    // -- Display --
    val aspectRatio: StateFlow<AspectRatio>
    /** Display dimensions (width, height), sample aspect applied. (0,0) until video track is decoded. */
    val videoSize: StateFlow<Pair<Int, Int>>

    // -- Commands --
    fun load(source: MediaSource)
    fun play()
    fun pause()
    /** Stop playback and unload media. Player can be reused via load(). */
    fun stop() { pause() }
    fun togglePlayPause()
    fun seekTo(ms: Long)
    /** Relative seek: skip(5000) = forward 5s, skip(-10000) = back 10s. */
    fun skip(deltaMs: Long)
    fun setVolume(volume: Float)
    fun setMuted(muted: Boolean)
    fun volumeUp(step: Float = 0.1f)
    fun volumeDown(step: Float = 0.1f)
    fun setPlaybackSpeed(speed: Float)
    fun selectAudioTrack(track: TrackInfo?)
    fun selectSubtitleTrack(track: TrackInfo?)
    fun setAspectRatio(ratio: AspectRatio)
    fun setAudioOutputMode(mode: AudioOutputMode) {}
    /** Applies from the next [load]; see [videoDecoderChoices]. */
    fun setVideoDecoding(decoding: VideoDecoding) {}
    /** Hint that the player is silently preloading — platforms that render to a
     *  surface (Android) can defer video output until preloading ends to avoid
     *  showing corrupt frames before the first keyframe. No-op on other platforms. */
    fun setPreloading(preloading: Boolean) {}
    fun setChapter(chapter: Chapter)
    /** Save current frame to file. */
    suspend fun takeScreenshot(path: String)
    /**
     * Build the video output now rather than at [play], without ending a silent preload.
     *
     * The decoder and the video output are only created once a surface is attached, and a
     * preload detaches it to stay invisible — so on every start the hardware decoder was
     * being spun up at the one moment the viewer is watching for a picture. Call this
     * once the preload has seeked, so what the surface can show is the right frame.
     */
    fun warmVideoOutput() {}

    /**
     * True once there is a surface for decoded video to go to.
     *
     * A hardware decoder is chosen when the elementary stream is opened, and on Android it
     * can only be chosen if there is a window to hand it: open the media with no surface
     * attached and VLC falls through to the software decoder and stays there for the whole
     * of that playback. So a load waits for this. Platforms that hand the decoder a surface
     * of their own are ready by definition.
     */
    val videoOutputReady: StateFlow<Boolean> get() = AlwaysReady

    /** Release all resources. Called automatically by [rememberPlayer] on dispose. */
    fun release()

    // -- Tier 2: typed native access --
    val native: NativePlayer
}

/**
 * A seek target held against [durationMs], where a non-positive duration means the
 * demuxer has not reported one yet.
 *
 * Clamping to an unknown duration pins every target to zero, which is how a resume seek
 * issued during a slow parse turned into "start from the beginning".
 */
fun clampSeekTarget(ms: Long, durationMs: Long): Long =
    if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)

/**
 * The state a player is in once its media clock has moved again.
 *
 * VLC closes a buffering cycle with a full-cache event when a stream opens, but after a
 * seek it does not always raise one — and a full-cache event was the only way out of
 * [PlayerState.BUFFERING]. The transport then spun for the rest of the title while the
 * audio played on underneath. A clock that advances is the proof the stall ended, and it
 * is the one signal the seek and the open path share.
 *
 * [preloading] holds a muted preload where it is: it auto-starts and is paused again
 * from the playing event, and the clock it ticks in between is not the viewer's playback.
 */
/** What a play/pause tap should do to a player in a given state. */
enum class PlayPauseIntent { Play, Pause, None }

/**
 * A transport tap read against the state the player is in.
 *
 * The tap used to be honoured only in [PlayerState.PLAYING] and [PlayerState.PAUSED], so
 * for the length of every buffering spell the button did nothing at all. A stall is a
 * state playback passes through, not one it sits in: [PlayerState.BUFFERING] is only ever
 * entered from playback, and [PlayerState.LOADING] is a start already under way — either
 * way sound is coming or already here, and the tap means stop.
 */
fun playPauseIntent(current: PlayerState): PlayPauseIntent =
    when (current) {
        PlayerState.PAUSED -> PlayPauseIntent.Play
        PlayerState.PLAYING, PlayerState.BUFFERING, PlayerState.LOADING -> PlayPauseIntent.Pause
        PlayerState.IDLE, PlayerState.FINISHED, PlayerState.ERROR -> PlayPauseIntent.None
    }

fun stateAfterClockAdvance(current: PlayerState, preloading: Boolean = false): PlayerState =
    when {
        preloading -> current
        current == PlayerState.LOADING || current == PlayerState.BUFFERING -> PlayerState.PLAYING
        else -> current
    }

private val AlwaysReady: StateFlow<Boolean> = MutableStateFlow(true)
