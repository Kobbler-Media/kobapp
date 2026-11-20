package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.CastSkip
import dev.kobbler.app.data.CastTracks
import dev.kobbler.app.data.KobblerApi

data class ReceiverNow(
    val mediaType: String?,
    val mediaId: String?,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val skip: CastSkip? = null,
    val tracks: CastTracks = CastTracks(),
)

interface ReceiverPlayback {
    fun now(): ReceiverNow

    /** False when the server has no file for the title; throws when it would not answer. */
    suspend fun open(api: KobblerApi, title: ReceiverTitle, positionSec: Int, onFinished: () -> Unit): Boolean

    fun releaseLocal()

    fun seekTo(absoluteMs: Long)
    fun play()
    fun pause()
    fun stop(api: KobblerApi)

    /** [trackId] as the phone numbers it; see [castTracks]. */
    fun selectSubtitle(trackId: Int?)
    suspend fun selectAudio(api: KobblerApi, streamIndex: Int)
}
