package dev.kobbler.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kobbler.app.data.BufferClass
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.ui.navigation.Destination
import kotlinx.coroutines.CancellationException
import dev.kobbler.app.platform.AppVersion
import dev.kobbler.app.platform.clientKind
import dev.kobbler.app.platform.deviceLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

enum class PlayerMode { Hidden, Mini, Pip, Fullscreen }

/** Where a fullscreen player goes when it is dismissed. See [fullscreenExit]. */
enum class FullscreenExit { ToMiniPlayer, Stop }

/**
 * What BACK does to a fullscreen player — one answer for the remote's key and the
 * overlay's own arrow, which had drifted into two.
 *
 * The mini player is the way down, but only where there is a pointer — its controls are
 * tap-only and it holds no focus target, so on a television it is somewhere playback can
 * never be reached again.
 */
fun GlobalPlayerState.fullscreenExit(
    formFactor: dev.kobbler.app.platform.FormFactor,
    isPlaying: Boolean,
): FullscreenExit = when {
    formFactor != dev.kobbler.app.platform.FormFactor.TV && isPlaying -> FullscreenExit.ToMiniPlayer
    else -> FullscreenExit.Stop
}

/**
 * Active cast session — drives the [CastMiniController] swap in
 * [PersistentVideoSurface] when non-null.
 *
 * Position/duration are millis. The current build has no cast backend; this DTO
 * is the contract a future cast integration will populate.
 */
data class CastSessionDto(
    val deviceName: String,
    val mediaTitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
)

/**
 * App-wide player state. One instance at AdaptiveScaffold level.
 * Tracks what's loaded, display mode, and session lifecycle.
 */
/** Delivery state of the automatic failure report shown on the error dialog. */
enum class ReportStatus { Idle, Sending, Sent, Failed }

// Channels rather than titles: they have no end, so a stop nobody asked for is a dropout.
private val LiveMediaTypes = setOf("livetv", "iptv")

class GlobalPlayerState {
    /**
     * Shared by every detail screen that closes a session on dispose: the close has to
     * outlive the composable, and one scope for all of them is what keeps a long TV
     * session from accumulating one uncancellable scope per screen exit.
     *
     * Main, because what it runs also drives the player and writes this state: the Android
     * player takes its commands, and attaches its video output, from the main thread.
     */
    val teardownScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // What's currently loaded
    var mediaType by mutableStateOf<String?>(null)        // "movie" or "episode"
    var mediaId by mutableStateOf<String?>(null)           // movie ID or episode ID
    var title by mutableStateOf("")
    var subtitle by mutableStateOf<String?>(null)
    var posterUrl by mutableStateOf<String?>(null)
    var logoUrl by mutableStateOf<String?>(null)
    var backdropUrl by mutableStateOf<String?>(null)

    // Where to navigate when tapping the mini player
    var detailDestination by mutableStateOf<Destination?>(null)

    val playbackTitleKey: String?
        get() = playbackTitleKey(
            mediaType, mediaId,
            (detailDestination as? Destination.TvShowDetail)?.takeUnless { it.isEpisodeId }?.showId,
        )

    // Session management
    var sessionId by mutableStateOf<String?>(null)

    // The one place the player's clock is reconciled with absolute media time. Every
    // reader of a position and every writer of a seek target goes through it.
    var timeline by mutableStateOf(PlayerTimeline.Direct)

    // Live TV holds its own session id rather than reusing [sessionId]: a tuner lease is
    // closed through the livetv route, and everything keyed on sessionId (thumbnails,
    // subtitle search) is meaningless for a live stream.
    var liveSessionId by mutableStateOf<String?>(null)
    var liveTunersInUse by mutableIntStateOf(0)
    var liveTunerCount by mutableIntStateOf(0)
    var liveNowGuideId by mutableStateOf<String?>(null)
    var liveNowStart by mutableStateOf<String?>(null)
    var liveNowEnd by mutableStateOf<String?>(null)

    // The same session serves raw TS and a buffered HLS playlist off one tuner
    // connection. [liveTimeshift] says which of the two the player is currently reading;
    // the lease and the session id are the same either way.
    var livePlaylistUrl by mutableStateOf<String?>(null)
    var liveTimeshift by mutableStateOf(false)

    // Field-coded H.264 has to stay off MediaCodec: the Tensor VPU decodes the sequence
    // header of a 1080i broadcast and then errors on every field picture, emitting
    // nothing while the audio plays on. libVLC never learns of it — its own interlaced
    // guard is dead code, MC_API_VIDEO_QUIRKS_SUPPORT_INTERLACED is set unconditionally
    // in mediacodec_ndk.c on both 3.0.x and master. Interlaced MPEG-2 decodes fine, so
    // this is scoped to the codec that breaks rather than to live TV as a whole.
    var liveFieldCoded by mutableStateOf(false)
    var liveBuffer by mutableStateOf<dev.kobbler.app.data.LiveBuffer?>(null)
    // Why the server says the tune is failing. The player itself only ever learns that the
    // stream stopped, so this is what the failure dialog names.
    var liveTuneError by mutableStateOf<String?>(null)
    // Where the live edge sat when playback was paused on the raw TS, in buffer
    // coordinates — the raw stream has no timeline to resume against, so the position is
    // taken from the server before the source is swapped.
    var livePausedAtSeconds by mutableStateOf<Int?>(null)
    // Set while the source is swapped from the raw TS to the buffer playlist. That swap
    // tears the surface down and re-parses, which on its own is indistinguishable from
    // the player having lost the stream — this is what names it as a rewind.
    var liveSeekingLabel by mutableStateOf<String?>(null)
    var adPosture by mutableStateOf(dev.kobbler.app.data.LiveAdPostureShade)
    var adSkipDisabled by mutableStateOf(false)

    // Media enrichment
    var chapters by mutableStateOf<List<dev.kobbler.app.data.SessionChapter>>(emptyList())
    var skipSegments by mutableStateOf<List<dev.kobbler.app.data.SkipSegment>>(emptyList())
    var thumbnailsUrl by mutableStateOf<String?>(null)

    // The library file behind the current session, when it is a local one. Skip
    // detection addresses its events to a file id, so a player has to know its own.
    var fileId by mutableStateOf<String?>(null)
    var lufs by mutableStateOf<Double?>(null)
    // Source video dimensions reported by the server. Drive aspect-ratio bucket
    // lookup before the player reports videoSize. Null until session is loaded
    // (or for sessions where server hasn't probed yet — falls back to player).
    var videoWidth by mutableStateOf<Int?>(null)
    var videoHeight by mutableStateOf<Int?>(null)
    var picture by mutableStateOf<dev.kobbler.app.data.PictureArea?>(null)
    var serverAudioTracks by mutableStateOf<List<dev.kobbler.app.data.AudioTrackDto>>(emptyList())
    var selectedServerAudioIndex by mutableStateOf<Int?>(null)

    // Client-rendered subtitles: server provides VTT URLs, we draw the cues in a
    // Compose overlay so they stay in the visible viewport regardless of video
    // aspect/crop mode (libvlc renders subs in video-frame coords, which get
    // clipped when the surface overflows the container).
    var serverSubtitleTracks by mutableStateOf<List<dev.kobbler.app.data.PlaybackSubtitleTrackDto>>(emptyList())
    var selectedServerSubtitleTrackId by mutableStateOf<String?>(null)
    // Server's chosen subtitle ffmpeg stream_index, kept regardless of whether
    // the track has a VTT URL. Drives VLC-native sub sync on direct play when
    // there's no VTT overlay path.
    var selectedServerSubtitleStreamIndex by mutableStateOf<Int?>(null)

    // Debrid streaming
    var isDebrid by mutableStateOf(false)

    // Raised with the shell for a stream that is still being found, and carried through the
    // load that opens it: isDebrid only lands once that load returns.
    private var streamStart by mutableStateOf(false)

    val isStreamStart: Boolean get() = isDebrid || streamStart
    var onSwitchStream: (() -> Unit)? = null

    // Cast — when non-null, PersistentVideoSurface renders CastMiniController
    // instead of the local mini player and skips the video surface entirely.
    var castSession by mutableStateOf<CastSessionDto?>(null)
    var onCastPlayPause: (() -> Unit)? = null
    var onCastDisconnect: (() -> Unit)? = null
    /** Absolute media time, as every position outside the player is. */
    var onCastSeek: ((Long) -> Unit)? = null
    /** When the viewer last moved the receiver themselves. See CastHandoff. */
    var castScrubbedAt: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null

    // Set to the media id the player is being opened for before its session exists, so
    // a Continue Watching tap raises the player immediately and lets the detail page
    // load behind it instead of after it.
    var preparingFor by mutableStateOf<String?>(null)
        private set

    // Display mode
    var isFullscreen by mutableStateOf(false)
    var isMiniPlayer by mutableStateOf(false)
    var isLive by mutableStateOf(false)

    // Set true while a session is being negotiated server-side (e.g. IPTV
    // probing sources). The player UI shows a spinner; the real stream URL
    // hasn't been handed to VLC yet.
    var isPreparing by mutableStateOf(false)

    // True from the moment a stream is handed to the player until it is actually
    // playing. The transport is drawn from a position and a duration that do not exist
    // yet, so it assembles itself on screen — 0:00 of 0:00, then a duration, then a
    // resume position — offering controls over something with nothing to control.
    var isStarting by mutableStateOf(false)

    // Non-null while a debrid provider is downloading the source remotely.
    // Range 0..1. Drives the in-player "Preparing stream — N%" indicator so
    // the wait feels like part of playback instead of a modal on the detail
    // page. Paired with [cachingTitle] / [onPreparingCancel].
    var cachingProgress by mutableStateOf<Float?>(null)
    var cachingTitle by mutableStateOf<String?>(null)
    var cachingNote by mutableStateOf<String?>(null)
    var onChooseRelease by mutableStateOf<(() -> Unit)?>(null)

    // What the wait is for, shown under "Connecting…". Each caller names its own —
    // a debrid search, a live probe and a session negotiation are not the same wait.
    var preparingNote by mutableStateOf<String?>(null)

    // How a slow start's feed is doing, once it has been slow long enough to say.
    var startProgress by mutableStateOf<String?>(null)

    var playerNote by mutableStateOf<String?>(null)
        private set
    private var noteJob: Job? = null

    fun flashNote(text: String, holdMs: Long = 4_000) {
        noteJob?.cancel()
        playerNote = text
        noteJob = teardownScope.launch {
            delay(holdMs)
            playerNote = null
        }
    }

    fun holdNote(text: String?) {
        noteJob?.cancel()
        playerNote = text
    }

    // Cancels whatever raised the preparing overlay, whichever phase it is in.
    var onPreparingCancel: (() -> Unit)? = null

    // Non-null when session creation or stream load failed. Rendered as an
    // error dialog over the player so the user can screenshot it and report.
    private var loadingErrorState by mutableStateOf<String?>(null)
    var loadingError: String?
        get() = loadingErrorState
        set(value) {
            loadingErrorState = value
            if (value == null) failureHelp = null
        }

    /** The friendly half of [loadingError], for failures that have somewhere to go next. */
    var failureHelp by mutableStateOf<PlaybackFailureHelp?>(null)
        private set

    val instant = InstantStreams()

    // Outlives the playback it was started from: an import finishes long after the viewer stops.
    val keeps = mutableStateMapOf<String, dev.kobbler.app.data.KeepProgress>()

    /** The walk behind the debrid stream now loaded, so a start that fails can move on. */
    internal var debridPlayback: DebridPlayback? = null

    /**
     * What was last handed to the player, kept so an open/decode failure can say
     * which URL died — the player itself reports only that it could not open one.
     */
    var lastStreamUrl by mutableStateOf<String?>(null)
        internal set

    /** The session the player was last opened on, so a reconnect can open it again. */
    internal var streamSession: dev.kobbler.app.data.KobblerApi.StreamSession? = null

    /** A session a reconnect already reopened once, not to be trusted with a second. */
    internal var reopenedSessionId: String? = null

    var reportStatus by mutableStateOf(ReportStatus.Idle)
        private set

    /**
     * The report already sent, so the retry button and a recomposition cannot
     * send it twice. A failure that keeps re-firing is one report, not a flood.
     */
    private var reportedDetail: String? = null

    /**
     * True while a lost stream is being reopened. The preparing shell is what the viewer
     * sees; this is what stops a second reconnect starting on top of the first.
     */
    var isRecovering by mutableStateOf(false)

    /**
     * True once a reconnect has been going on long enough to be worth saying so. Most
     * land well inside that, and announcing those is worse than staying quiet — see
     * [RECONNECT_NOTICE_DELAY_MS].
     */
    var recoveryNotice by mutableStateOf(false)

    /**
     * Raised by [playUnmuted] every time a stream is asked to play. Collected by
     * [watchPlayback], which supervises the start — nothing else is watching for one
     * that simply never happens.
     *
     * Drops the oldest rather than suspending: a reconnect asks for a start from inside
     * the supervision of the start it is replacing, and the newest request is the only
     * one worth having.
     */
    val startRequests: MutableSharedFlow<Unit> = MutableSharedFlow(
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    internal var startSerial = 0
        private set

    /**
     * How the server said it would deliver the loaded stream. Sets what a start is
     * allowed to cost before it is called lost — see [startBudgetMs].
     */
    var sessionMethod by mutableStateOf<String?>(null)

    /** Waits for the current start to become playback. Cancelled by the next one. */
    private var startJob: Job? = null

    /** When each reconnect was started, for the rolling cap in [recoverPlayback]. */
    internal val recoveryMarks: MutableList<TimeSource.Monotonic.ValueTimeMark> = mutableListOf()

    /**
     * Playhead positions this session has reported, by media id. Read by the screens
     * behind the player, which hold a payload fetched before any of it was watched.
     * Deliberately not cleared by [stop] — the point of it is to outlive the playback.
     */
    val reportedPositions = mutableStateMapOf<String, WatchedPoint>()

    // Where this playback was asked to start, in ms. The stream is opened at it rather
    // than seeked to it; this is kept so [repairResume] can tell whether that landed.
    private var pendingSeek by mutableStateOf(0L)
    var pendingSeekMs: Long
        get() = pendingSeek
        set(value) {
            pendingSeek = value
            resumeSettling = value > 0
        }

    // Until [repairResume] has the playhead at [pendingSeekMs], it may still be reading the
    // head of a file that ignored its start, and a save then overwrites the resume point.
    private var resumeSettling = false

    internal fun isShortOfResume(id: String, absoluteMs: Long): Boolean =
        resumeSettling && id == mediaId &&
            absoluteMs < timeline.toAbsoluteMs(pendingSeekMs) - RESUME_TOLERANCE_MS

    // True when audio was muted for silent preload — play paths must unmute
    var mutedForPreload by mutableStateOf(false)

    // Auto-play next callback (set by InlineTvPlayer for episode chaining)
    var onFinished: (() -> Unit)? = null

    // Manual episode stepping. State-backed, unlike onFinished: the player controls read
    // them to decide whether to draw the buttons, so they have to trigger recomposition
    // when playback moves to an episode with no neighbour on that side. Null for movies
    // and live, and for the ends of a show.
    var onSkipNext by mutableStateOf<(() -> Unit)?>(null)
    var onSkipPrevious by mutableStateOf<(() -> Unit)?>(null)

    /** Play an episode of the loaded show by id. Set alongside [onSkipNext]. */
    var onPlayEpisode by mutableStateOf<((String) -> Unit)?>(null)
    var episodeShow by mutableStateOf<TvShow?>(null)

    /**
     * Another device's newer save of this title, offered and not yet answered. While it
     * is set nothing is reported — the playhead here may be the stale one. See
     * PlaybackHandoff.kt.
     */
    var handoff by mutableStateOf<dev.kobbler.app.data.WatchProgress?>(null)
    var handoffScopeId: String? = null
    var handoffSince: kotlin.time.Instant? = null
    var handoffStayCountdown by mutableStateOf<Int?>(null)
    /** A watched title's saved position short of its ending, offered on a play from the top. See ResumeOffer.kt. */
    var resumeOffer by mutableStateOf<Int?>(null)
    var resumeOfferCountdown by mutableStateOf<Int?>(null)
    // Media id to absolute seconds the server is known to hold for it, so a save that would
    // only repeat it — a player left paused while another device moved on — is not written
    // back over the newer one. Keyed rather than tied to mediaId: a close saves after the
    // player has already let go of the title.
    var lastSaved: Pair<String, Int>? = null

    val isActive: Boolean get() = mediaId != null || preparingFor != null
    val isVideoPlaying: Boolean get() = isFullscreen || isMiniPlayer

    val renderableServerSubtitles: List<dev.kobbler.app.data.PlaybackSubtitleTrackDto>
        get() = serverSubtitleTracks.filter {
            !it.url.isNullOrBlank() && (it.format == "vtt" || it.format == "srt")
        }

    val activeServerSubtitleUrl: String?
        get() = selectedServerSubtitleTrackId?.let { id ->
            renderableServerSubtitles.firstOrNull { it.trackId == id }?.url
        }

    val playerMode: PlayerMode get() = when {
        isFullscreen -> PlayerMode.Fullscreen
        isMiniPlayer -> PlayerMode.Mini
        else -> PlayerMode.Hidden
    }

    /**
     * Load new content into the player. Closes any existing session first.
     */
    suspend fun load(
        player: dev.kobbler.kobplayer.Player,
        api: KobblerApi,
        type: String,
        id: String,
        streamUrl: String,
        newSessionId: String?,
        newTitle: String,
        newSubtitle: String?,
        newPosterUrl: String?,
        newLogoUrl: String?,
        destination: Destination?,
        seekToSec: Int = 0,
        session: dev.kobbler.app.data.KobblerApi.StreamSession? = null,
    ) {
        // Handed back, not waited on. The server kills the session's transcodes and reaps
        // them before it answers, and none of that is an input to opening the next stream
        // — awaiting it put a variable, sometimes long, stall in front of every switch.
        val oldSid = sessionId
        if (oldSid != null && id != mediaId) {
            teardownScope.launch { api.closeSession(oldSid) }
        }

        if (id != mediaId) {
            recoveryMarks.clear()
            debridPlayback = null
            // Art raised by beginPreparing belongs to this load; anything older is another title's.
            if (preparingFor == null) {
                backdropUrl = null
                streamStart = false
            }
            // Answered by switching titles, or overtaken by the viewer picking another.
            handoff = null
            resumeOffer = null
        }
        preparingFor = null
        isPreparing = false
        preparingNote = null
        mediaType = type
        mediaId = id
        title = newTitle
        subtitle = newSubtitle
        posterUrl = newPosterUrl
        logoUrl = newLogoUrl
        detailDestination = destination
        sessionId = newSessionId
        sessionMethod = session?.method
        lastStreamUrl = streamUrl
        streamSession = session
        timeline = PlayerTimeline.of(session?.serverStartSeconds)
        lastSaved = id to (timeline.toAbsoluteMs(seekToSec * 1000L) / 1000).toInt()
        chapters = session?.chapters ?: emptyList()
        skipSegments = session?.skipSegments ?: emptyList()
        thumbnailsUrl = session?.thumbnailsUrl
        fileId = session?.fileId
        lufs = session?.lufs
        videoWidth = session?.videoWidth
        videoHeight = session?.videoHeight
        picture = session?.picture
        serverAudioTracks = session?.audioTracks ?: emptyList()
        selectedServerAudioIndex = startAudioIndex(
            audioTracks = serverAudioTracks,
            serverPick = session?.selectedAudioIndex,
            preferredLanguage = dev.kobbler.app.data.UserSettings
                .getString(dev.kobbler.app.ui.dashboard.KEY_DEFAULT_AUDIO_LANG),
        )
        val subs = session?.subtitles ?: emptyList()
        serverSubtitleTracks = subs
        // Prefer the server's selectedSubtitleIndex (new server respects the
        // subtitles-enabled toggle and language pref). When absent (old server
        // that doesn't return the field), fall back to the file's default subtitle
        // ONLY if the user has subtitles enabled — otherwise stay off so the
        // toggle is honored client-side too.
        val subtitlesEnabled = dev.kobbler.app.data.UserSettings
            .getString("pref_subtitles_enabled") != "false"
        selectedServerSubtitleTrackId = session?.selectedSubtitleIndex?.let { idx ->
            subs.firstOrNull { it.streamIndex == idx && it.url != null }?.trackId
        } ?: if (subtitlesEnabled) {
            subs.firstOrNull { it.isDefault && it.url != null }?.trackId
        } else null
        selectedServerSubtitleStreamIndex = session?.selectedSubtitleIndex
            ?: if (subtitlesEnabled) {
                subs.firstOrNull { it.isDefault }?.streamIndex
            } else null

        isStarting = true
        pendingSeekMs = seekToSec * 1000L
        // Enter preload mode — suppresses all output (video surface detached,
        // audio silenced) while VLC buffers and decodes. No mute hack needed.
        player.setPreloading(true)
        mutedForPreload = true
        player.setAudioOutputMode(audioOutputModeSetting())
        player.setVideoDecoding(videoDecodingSetting())
        player.setMatchDisplayRefresh(matchDisplayRefreshSetting())

        if (type != "livetv") liveFieldCoded = false
        val intent = trackIntent(
            audioTracks = serverAudioTracks,
            selectedAudioIndex = selectedServerAudioIndex,
            subtitles = serverSubtitleTracks,
            selectedSubtitleIndex = selectedServerSubtitleStreamIndex,
        )
        PlaybackTrace.mark("session")
        // The surface has to exist before the stream is opened or the decoder is picked
        // without one, which on Android means software for the rest of that stream.
        // Bounded: a platform that hands the decoder its own surface reports ready at
        // once, and an audio-only source must not be held up by a window it never wants.
        val hasOutput = withTimeoutOrNull(2_000) { player.videoOutputReady.first { it } } != null
        // Named in the trace rather than swallowed. Giving up here is not a slow start,
        // it is a silent drop to software decoding for the whole title — which is exactly
        // how it went unnoticed before there was anything watching for it.
        PlaybackTrace.mark(if (hasOutput) "surface" else "surface MISSING")
        if (!hasOutput) noteStartEvent("no video output after 2s, opened anyway: decodes in software")
        player.load(
            dev.kobbler.kobplayer.MediaSource.Url(
                // Tokenised here rather than stored: lastStreamUrl above goes into failure
                // reports, and a credential does not belong in one.
                api.playerUrl(streamUrl),
                fieldCoded = liveFieldCoded,
                networkCacheMs = api.networkCacheMsFor(streamUrl, BufferClass.of(type)),
                audioLanguage = intent.audioLanguage,
                subtitleOrdinal = intent.subtitleOrdinal,
                subtitlesOff = intent.subtitlesOff,
                startTimeSec = seekToSec.takeIf { it > RESUME_MIN_SECONDS },
                live = type in LiveMediaTypes,
            ),
        )
        // Pause immediately — prefetch only, don't start playback.
        // Callers must explicitly call player.play() when ready.
        player.pause()

        // Idempotent: the output is attached before the stream opens, because that is the
        // only moment a hardware decoder can be chosen. This covers the platforms that
        // build it on demand, and is a no-op where it already exists.
        player.warmVideoOutput()
        PlaybackTrace.mark("decoder")

        // Sync VLC track selection to server's pick during the silent preload
        // window — direct play hands the raw file to VLC which would otherwise
        // default to the file's track 0. Runs only when track counts match
        // (signals direct play; HLS exposes only the chosen track to VLC).
        // Bounded wait so a slow/missing parse can't deadlock playUnmuted().
        // Off the critical path. The stream was opened already knowing what to select, so
        // this is only here for what an option cannot express: a track VLC marks
        // unselectable, or two tracks in one language. It repairs, it does not apply —
        // waiting for it cost two seconds of every start and delivered nothing.
        teardownScope.launch { reconcileTracksToServerPick(player, intent) }
        PlaybackTrace.mark("tracks")
    }

    /**
     * Checks what VLC actually selected against what the server asked for, and repairs it.
     *
     * Everything expressible as an option was already applied when the stream opened, so
     * this exists for the two cases that are not: a container track VLC will not select
     * from an option because the file marks it unselectable, and two audio tracks sharing
     * one language, which a language cannot tell apart. It never re-applies a decision
     * that already landed, so a viewer who has since chosen a track keeps it.
     */
    private suspend fun reconcileTracksToServerPick(
        player: dev.kobbler.kobplayer.Player,
        intent: TrackIntent,
    ) {
        // A tuner tune reports no track selection to honour, and this waits on the initial
        // parse — which is the whole budget an inline channel switch has.
        if (mediaType == "livetv") return

        val parsed = withTimeoutOrNull(5_000) {
            player.state.first {
                it != dev.kobbler.kobplayer.PlayerState.IDLE &&
                    it != dev.kobbler.kobplayer.PlayerState.LOADING
            }
        } != null
        if (!parsed) return

        if (intent.subtitlesOff && player.selectedSubtitleTrack.value != null) {
            player.selectSubtitleTrack(null)
        }

        val targetAudio = selectedServerAudioIndex ?: return
        if (serverAudioTracks.size <= 1) return
        // Only when the counts match, which is what says the player was handed the whole
        // file. A transcode exposes the one track it was asked for, already correct.
        val vlcAudio = player.audioTracks.value
        if (vlcAudio.size != serverAudioTracks.size) return
        val nth = serverAudioTracks.sortedBy { it.streamIndex }
            .indexOfFirst { it.streamIndex == targetAudio }
        val wanted = vlcAudio.getOrNull(nth) ?: return
        if (player.selectedAudioTrack.value?.id != wanted.id) player.selectAudioTrack(wanted)
    }

    /** Stop playback and clean up. */
    fun stop(player: dev.kobbler.kobplayer.Player, api: KobblerApi, scope: CoroutineScope) {
        // Ends any start still being supervised and any fallback walk, which would otherwise
        // run on and report a failure over whatever screen the viewer went to.
        startSerial++
        debridPlayback?.abandon()
        player.stop()
        player.setMuted(false)
        mutedForPreload = false
        val sid = sessionId
        if (sid != null) {
            scope.launch { api.closeSession(sid) }
        }
        val liveSid = liveSessionId
        if (liveSid != null) {
            scope.launch { api.closeLiveSession(liveSid) }
        }
        preparingFor = null
        isStarting = false
        mediaType = null
        mediaId = null
        title = ""
        subtitle = null
        posterUrl = null
        logoUrl = null
        backdropUrl = null
        detailDestination = null
        sessionId = null
        sessionMethod = null
        timeline = PlayerTimeline.Direct
        liveSessionId = null
        liveTunersInUse = 0
        liveTunerCount = 0
        liveNowGuideId = null
        liveNowStart = null
        liveNowEnd = null
        livePlaylistUrl = null
        liveTimeshift = false
        liveBuffer = null
        liveTuneError = null
        livePausedAtSeconds = null
        liveSeekingLabel = null
        chapters = emptyList()
        skipSegments = emptyList()
        thumbnailsUrl = null
        fileId = null
        lufs = null
        videoWidth = null
        videoHeight = null
        picture = null
        serverAudioTracks = emptyList()
        selectedServerAudioIndex = null
        serverSubtitleTracks = emptyList()
        selectedServerSubtitleTrackId = null
        selectedServerSubtitleStreamIndex = null
        isDebrid = false
        streamStart = false
        debridPlayback = null
        onSwitchStream = null
        castSession = null
        onCastPlayPause = null
        onCastDisconnect = null
        onCastSeek = null
        castScrubbedAt = null
        isFullscreen = false
        isMiniPlayer = false
        isLive = false
        isPreparing = false
        preparingNote = null
        clearCaching()
        onPreparingCancel = null
        onSkipNext = null
        onSkipPrevious = null
        onPlayEpisode = null
        episodeShow = null
        resumeOffer = null
        loadingError = null
        pendingSeekMs = 0L
        isRecovering = false
        recoveryNotice = false
        recoveryMarks.clear()
        startJob?.cancel()
        startJob = null
        holdNote(null)
    }

    /**
     * Capture an exception into [loadingError] so it surfaces as a screenshot-able
     * dialog. [contextLines] are extra header lines (e.g. media id, endpoint) that
     * help triage. Also flips [isPreparing] off.
     */
    /**
     * Everything worth knowing about the attempt that just died. The stream URL is
     * the load-bearing line: it says whether the server handed us a direct `.ts` or
     * an HLS playlist, which is the first thing a playback bug turns on.
     */
    /** What the current start went through, timed from the press; see [watchSlowStart]. */
    internal var startEvents: List<String> = emptyList()
    internal var startClock: (() -> Long)? = null

    internal fun noteStartEvent(text: String) {
        val line = startClock?.let { "${formatSeconds(it())} $text" } ?: text
        startEvents = startEvents + line
        println("[PlaybackStart] $line")
    }

    fun diagnosticContext(): List<String> = buildList {
        add("App: ${AppVersion.versionName} (${AppVersion.versionCode}) · $clientKind · $deviceLabel")
        if (title.isNotEmpty()) add("Title: $title")
        mediaType?.let { add("Media: $it ${mediaId ?: ""}".trim()) }
        lastStreamUrl?.let { add("Stream: $it") }
        if (pendingSeekMs > 0) add("Resume: asked for ${pendingSeekMs / 1000}s")
        sessionId?.let { add("Session: $it") }
        liveSessionId?.let { add("Live session: $it") }
        if (isLive) {
            add("Live: tuners ${liveTunersInUse}/${liveTunerCount}, timeshift=$liveTimeshift")
            livePlaylistUrl?.let { add("Live playlist: $it") }
        }
        if (videoWidth != null || videoHeight != null) {
            add("Video: ${videoWidth ?: "?"}x${videoHeight ?: "?"}")
        }
        if (startEvents.isNotEmpty()) add("Start: ${startEvents.joinToString(" | ")}")
    }

    /**
     * The player only ever knows that it could not open or decode what it was
     * given, so a session-level failure — which knows *why* — always wins. Both
     * can fire for one attempt, and the vaguer one must not replace the useful one.
     */
    fun reportPlayerFailure(throwable: Throwable) {
        if (loadingError != null) return
        failWith(throwable, diagnosticContext())
    }

    /**
     * Send the failure without being asked. A person who hits an unplayable
     * channel should not also have to think to press a button — by the time they
     * would, the useful context is a screenshot at best.
     *
     * Fire-and-forget on purpose: this must never turn a playback failure into a
     * second, louder failure, so a report that cannot be delivered just offers
     * the retry button.
     */
    suspend fun submitReport(api: KobblerApi, detail: String) {
        if (reportedDetail == detail && reportStatus == ReportStatus.Sent) return
        reportStatus = ReportStatus.Sending
        reportStatus = try {
            api.reportPlaybackFailure(
                detail = detail,
                mediaType = mediaType,
                mediaId = mediaId,
                streamUrl = lastStreamUrl,
                sessionId = liveSessionId ?: sessionId,
            )
            reportedDetail = detail
            ReportStatus.Sent
        } catch (_: CancellationException) {
            ReportStatus.Idle
        } catch (_: Exception) {
            ReportStatus.Failed
        }
    }

    fun failWith(
        throwable: Throwable,
        contextLines: List<String> = emptyList(),
        help: PlaybackFailureHelp? = null,
    ) {
        // Cancellation means we stopped, not that playback failed: leaving the screen
        // kills the in-flight resolve, and reporting that raised a dialog over nothing.
        if (throwable is CancellationException) {
            preparingFor = null
            isPreparing = false
            isStarting = false
            preparingNote = null
            clearCaching()
            onPreparingCancel = null
            return
        }
        reportStatus = ReportStatus.Idle
        isStarting = false
        loadingError = buildString {
            appendLine("Playback failed")
            if (contextLines.isNotEmpty()) {
                appendLine()
                contextLines.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("${throwable::class.simpleName}: ${throwable.message ?: "(no message)"}")
            appendLine()
            append(throwable.stackTraceToString())
        }
        failureHelp = help ?: if (mediaType == "livetv") PlaybackFailureHelp(liveFailureSummary(title, throwable, liveTuneError)) else null
        preparingFor = null
        isPreparing = false
        preparingNote = null
        clearCaching()
        onPreparingCancel = null
    }

    /**
     * Bring up the fullscreen player surface in its loading state to wait for a
     * debrid provider to finish caching the source. Renders inside the player's
     * existing preparing UI (no modal on the detail page).
     */
    /**
     * Raise the fullscreen player in its loading state for [id] before a session exists.
     *
     * The detail screen behind it still does the real work — resolving the episode,
     * creating the session, preloading — but the viewer is looking at the player while
     * that happens rather than at a detail page that suddenly gives way to one.
     */
    fun beginPreparing(
        id: String,
        newTitle: String,
        newSubtitle: String?,
        newPosterUrl: String?,
        note: String? = null,
        newBackdropUrl: String? = null,
        onCancel: (() -> Unit)? = null,
        stream: Boolean = true,
    ) {
        if (isActive) {
            if (stream && preparingFor != null) streamStart = true
            return
        }
        streamStart = stream
        preparingFor = id
        title = newTitle
        subtitle = newSubtitle
        posterUrl = newPosterUrl
        backdropUrl = newBackdropUrl
        preparingNote = note
        onPreparingCancel = onCancel
        loadingError = null
        isPreparing = true
        isFullscreen = true
    }

    /** Take the shell back down when the thing it was raised for never arrived. */
    fun cancelPreparing() {
        if (preparingFor == null) return
        preparingFor = null
        streamStart = false
        isPreparing = false
        isStarting = false
        isFullscreen = false
        title = ""
        subtitle = null
        posterUrl = null
        backdropUrl = null
        preparingNote = null
        onPreparingCancel = null
    }

    fun beginCachingPreparation(title: String, onCancel: () -> Unit, onChooseRelease: (() -> Unit)? = null) {
        cachingProgress = 0f
        cachingTitle = title
        this.onChooseRelease = onChooseRelease
        onPreparingCancel = onCancel
        isPreparing = true
        isFullscreen = true
    }

    fun setCachingProgress(progress: Float) {
        if (cachingProgress != null) cachingProgress = progress.coerceIn(0f, 1f)
    }

    fun endCachingPreparation() {
        clearCaching()
        onPreparingCancel = null
    }

    private fun clearCaching() {
        cachingProgress = null
        cachingTitle = null
        cachingNote = null
        onChooseRelease = null
    }

    /**
     * Hand the preloaded stream over without starting it.
     *
     * A load leaves the player silent and detached so a viewer never sees the frames
     * before the first keyframe. Anything that opens a stream it was not asked to play
     * has to undo that itself, or the picture never arrives.
     */
    fun endPreload(player: dev.kobbler.kobplayer.Player) {
        if (mutedForPreload) {
            player.setPreloading(false)
            mutedForPreload = false
        }
        startJob?.cancel()
        startJob = null
        isStarting = false
        resumeSettling = false
    }

    /** Unmute if we muted for preload, then play. */
    fun playUnmuted(player: dev.kobbler.kobplayer.Player) {
        if (mutedForPreload) {
            player.setPreloading(false)
            mutedForPreload = false
        }
        player.play()
        PlaybackTrace.mark("unmute")
        // Two halves, deliberately: the stream starting is entirely the player's news and
        // is acted on here, while a start that never happens can only be diagnosed by
        // asking the server — which [watchPlayback] does, and which nothing in here has
        // the reach to do.
        startJob?.cancel()
        startJob = teardownScope.launch {
            player.state.first { it == dev.kobbler.kobplayer.PlayerState.PLAYING }
            onStarted(player)
        }
        startSerial++
        startRequests.tryEmit(Unit)
    }

    /** Drop the opening posture once the stream is genuinely playing. */
    internal suspend fun onStarted(player: dev.kobbler.kobplayer.Player) {
        isStarting = false
        repairResume(player)
    }

    /**
     * Put the playhead where it was asked to be, for a source that opened at the head anyway.
     *
     * The resume goes in as an open-time option, which is both faster and more reliable
     * than a seek — but it is the demuxer's to honour, and an exotic source may not. One
     * check, once, after the stream is genuinely playing and has a duration to seek
     * against; the alternative is paying for the parse on every start to protect against
     * the rare source that needs it.
     */
    private suspend fun repairResume(player: dev.kobbler.kobplayer.Player) {
        player.settleAt(pendingSeekMs)
        resumeSettling = false
    }
}

