package dev.kobbler.app.ui.player

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobplayer.AspectRatio
import dev.kobbler.kobplayer.TrackInfo
import dev.kobbler.kobplayer.ui.OverlayChapter
import dev.kobbler.kobplayer.ui.PlayerChrome
import dev.kobbler.kobplayer.ui.PlayerColors
import dev.kobbler.kobplayer.ui.SeekBarRegion
import dev.kobbler.kobplayer.ui.SideGestureConfig
import dev.kobbler.kobplayer.ui.VideoPlayerOverlay

@Composable
fun PlayerOverlay(
    title: String,
    subtitle: String? = null,
    logoUrl: String? = null,
    isPlaying: Boolean,
    currentMs: () -> Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    audioTracks: List<TrackInfo> = emptyList(),
    selectedAudioTrack: TrackInfo? = null,
    onSelectAudioTrack: (TrackInfo?) -> Unit = {},
    subtitleTracks: List<TrackInfo> = emptyList(),
    selectedSubtitleTrack: TrackInfo? = null,
    onSelectSubtitleTrack: (TrackInfo?) -> Unit = {},
    onFindSubtitles: (() -> Unit)? = null,
    playbackSpeed: Float = 1.0f,
    onSetPlaybackSpeed: (Float) -> Unit = {},
    aspectRatio: AspectRatio = AspectRatio.FIT,
    onSetAspectRatio: (AspectRatio) -> Unit = {},
    chapterMarkers: List<Float> = emptyList(),
    regions: List<SeekBarRegion> = emptyList(),
    chapters: List<OverlayChapter> = emptyList(),
    modifier: Modifier = Modifier,
    isLive: Boolean = false,
    loading: Boolean = false,
    thumbnailPreview: (@Composable (seekFraction: Float) -> Unit)? = null,
    chapterThumbnail: (@Composable (startMs: Long) -> Unit)? = null,
    sideGestures: SideGestureConfig? = null,
    onSeekPastEnd: (() -> Unit)? = null,
    overlaid: Boolean = false,
    extraControls: @Composable RowScope.() -> Unit = {},
) {
    val k = KobappTheme.colors
    val accent = KobappTheme.accent
    // Focus is a remote/keyboard affordance. A phone has neither, and the seek bar being
    // focusable was enough for the platform to hand it focus on open and ring it.
    val formFactor = LocalFormFactor.current
    val touchOnly = formFactor == FormFactor.Phone
    val colors = PlayerColors(
        primary = k.textHi,
        primaryDim = k.textHi.copy(alpha = 0.78f),
        primaryFaint = k.textHi.copy(alpha = 0.45f),
        accent = accent,
        controlBackground = Color.Black.copy(alpha = 0.45f),
        seekBarActive = accent,
        seekBarInactive = k.textHi.copy(alpha = 0.18f),
        menuBackground = k.elev2,
    )
    VideoPlayerOverlay(
        title = title, subtitle = subtitle, logoUrl = logoUrl,
        isPlaying = isPlaying, currentMs = currentMs, durationMs = durationMs,
        onPlayPause = onPlayPause, onSeek = onSeek, onBack = onBack,
        onToggleFullscreen = onToggleFullscreen, isFullscreen = isFullscreen,
        audioTracks = audioTracks, selectedAudioTrack = selectedAudioTrack,
        onSelectAudioTrack = onSelectAudioTrack,
        subtitleTracks = subtitleTracks, selectedSubtitleTrack = selectedSubtitleTrack,
        onSelectSubtitleTrack = onSelectSubtitleTrack,
        onFindSubtitles = onFindSubtitles,
        playbackSpeed = playbackSpeed, onSetPlaybackSpeed = onSetPlaybackSpeed,
        aspectRatio = aspectRatio, onSetAspectRatio = onSetAspectRatio,
        chapterMarkers = chapterMarkers, regions = regions, chapters = chapters,
        colors = colors,
        modifier = modifier,
        isLive = isLive,
        loading = loading,
        thumbnailPreview = thumbnailPreview,
        chapterThumbnail = chapterThumbnail,
        sideGestures = sideGestures,
        chrome = when (formFactor) {
            FormFactor.Phone -> PlayerChrome.Touch
            FormFactor.Desktop -> PlayerChrome.Pointer
            FormFactor.TV -> PlayerChrome.Tv
        },
        // Touch has the panel's own button and no direction keys to spend.
        onSeekPastEnd = onSeekPastEnd.takeUnless { touchOnly },
        overlaid = overlaid,
        focusDecoration = { key, cornerRadiusDp, scale ->
            when {
                touchOnly -> Modifier
                // The seek bar is as wide as the player, so a ring around it is a box
                // drawn round the whole screen rather than a pointer at the thing that
                // moves. It lights its own handle instead — see MediaSeekBar.
                key == "seek" -> Modifier.focusKey(key)
                else -> Modifier.focusKey(key).tvAutoGlow(cornerRadiusDp = cornerRadiusDp, scale = scale)
            }
        },
        extraControls = extraControls,
    )
}
