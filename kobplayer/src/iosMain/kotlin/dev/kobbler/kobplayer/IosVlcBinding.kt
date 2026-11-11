@file:OptIn(ExperimentalForeignApi::class)

package dev.kobbler.kobplayer

import cnames.structs.libvlc_media_player_t
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import libvlc.*
import platform.UIKit.UIView
import platform.posix.usleep

internal class IosVlcBinding(val player: CPointer<libvlc_media_player_t>) : VlcBinding {
    // libVLC keeps only the pointer until a picture needs it: the view lives as long as the player.
    var drawable: UIView? = null
        private set

    fun attach(view: UIView) {
        drawable = view
        libvlc_media_player_set_nsobject(player, interpretCPointer<COpaque>(view.objcPtr()))
    }

    fun start(url: String, options: List<String>) {
        val media = libvlc_media_new_location(url) ?: error("libVLC cannot open $url")
        options.forEach { libvlc_media_add_option(media, ":$it") }
        libvlc_media_player_set_media(player, media)
        libvlc_media_release(media)
        libvlc_media_player_play(player)
    }

    fun release() {
        libvlc_media_player_stop_async(player)
        libvlc_media_player_release(player)
    }

    fun setVolume(volume: Int) {
        libvlc_audio_set_volume(player, volume)
    }

    fun setMute(muted: Boolean) = libvlc_audio_set_mute(player, if (muted) 1 else 0)

    fun setFit(ratio: AspectRatio) = libvlc_video_set_display_fit(player, ratio.fit)

    fun videoSize(): Pair<Int, Int>? {
        val track = libvlc_media_player_get_selected_track(player, libvlc_track_video) ?: return null
        val video = track.pointed.u.video?.pointed
        val size = video?.takeIf { it.i_width > 0u && it.i_height > 0u }?.let {
            displaySize(it.i_width.toInt(), it.i_height.toInt(), it.i_sar_num.toInt(), it.i_sar_den.toInt())
        }
        libvlc_media_track_release(track)
        return size
    }

    override val inputState: VlcInputState
        get() = vlcInputState(libvlc_media_player_get_state(player))

    override val timeUs: Long get() = libvlc_media_player_get_time(player)

    override fun play() {
        libvlc_media_player_play(player)
    }

    override fun setPause(paused: Boolean) = libvlc_media_player_set_pause(player, if (paused) 1 else 0)

    override fun stop() {
        libvlc_media_player_stop_async(player)
    }

    override fun setTime(us: Long, fast: Boolean) {
        libvlc_media_player_set_time(player, us, fast)
    }

    override fun setRate(rate: Float) {
        libvlc_media_player_set_rate(player, rate)
    }

    override fun tracks(type: VlcTrackType): List<TrackInfo> {
        val list = libvlc_media_player_get_tracklist(player, type.libVlc, false) ?: return emptyList()
        val count = libvlc_media_tracklist_count(list).toInt()
        return List(count) { index ->
            val track = libvlc_media_tracklist_at(list, index.convert())!!.pointed
            TrackInfo(track.psz_id!!.toKString(), track.psz_name?.toKString() ?: "Track ${index + 1}")
        }.also { libvlc_media_tracklist_delete(list) }
    }

    override fun selectedTrackId(type: VlcTrackType): String? {
        val track = libvlc_media_player_get_selected_track(player, type.libVlc) ?: return null
        return track.pointed.psz_id?.toKString().also { libvlc_media_track_release(track) }
    }

    override fun selectTrack(type: VlcTrackType, id: String) {
        val track = libvlc_media_player_get_track_from_id(player, id) ?: return
        libvlc_media_player_select_track(player, track)
        libvlc_media_track_release(track)
    }

    override fun deselect(type: VlcTrackType) = libvlc_media_player_unselect_track_type(player, type.libVlc)

    override fun chapters(): List<Chapter> = memScoped {
        val descriptions = alloc<CPointerVar<CPointerVar<libvlc_chapter_description_t>>>()
        val count = libvlc_media_player_get_full_chapter_descriptions(player, -1, descriptions.ptr)
        val array = descriptions.value ?: return emptyList()
        List(count.coerceAtLeast(0)) { index ->
            val chapter = array[index]!!.pointed
            Chapter(
                name = chapter.psz_name?.toKString(),
                offsetMs = chapter.i_time_offset / 1000,
                durationMs = chapter.i_duration / 1000,
            )
        }.also { libvlc_chapter_descriptions_release(array, count.convert()) }
    }

    override fun setChapter(index: Int) = libvlc_media_player_set_chapter(player, index)

    override fun sleep(ms: Long) {
        usleep((ms * 1000).convert())
    }
}

internal fun vlcInputState(state: libvlc_state_t): VlcInputState = when (state) {
    libvlc_Opening -> VlcInputState.Opening
    libvlc_Playing -> VlcInputState.Playing
    libvlc_Paused -> VlcInputState.Paused
    libvlc_Stopping -> VlcInputState.Stopping
    libvlc_Stopped -> VlcInputState.Stopped
    libvlc_Error -> VlcInputState.Error
    else -> VlcInputState.Idle
}

private val VlcTrackType.libVlc: libvlc_track_type_t
    get() = when (this) {
        VlcTrackType.Audio -> libvlc_track_audio
        VlcTrackType.Video -> libvlc_track_video
        VlcTrackType.Text -> libvlc_track_text
    }

private val AspectRatio.fit: libvlc_video_fit_mode_t
    get() = when (this) {
        AspectRatio.FIT, AspectRatio.STRETCH -> libvlc_video_fit_smaller
        AspectRatio.CROP -> libvlc_video_fit_larger
    }
