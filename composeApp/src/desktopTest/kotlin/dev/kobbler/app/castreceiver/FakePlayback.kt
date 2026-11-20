package dev.kobbler.app.castreceiver

import dev.kobbler.app.data.KobblerApi
import java.util.concurrent.CopyOnWriteArrayList

/** The TV's player as the receiver drives it, recording what it was told. */
class FakePlayback : ReceiverPlayback {
    val calls = CopyOnWriteArrayList<String>()
    val opened = CopyOnWriteArrayList<Pair<ReceiverTitle, Int>>()
    var openedWith: KobblerApi? = null
    var onFinished: (() -> Unit)? = null
    var openFails: Throwable? = null
    var now = ReceiverNow(null, null, "", null, null, 0, 0, playing = false)

    override fun now() = now

    override suspend fun open(api: KobblerApi, title: ReceiverTitle, positionSec: Int, onFinished: () -> Unit): Boolean {
        openFails?.let { throw it }
        openedWith = api
        opened += title to positionSec
        this.onFinished = onFinished
        now = ReceiverNow(title.mediaType, title.mediaId, title.title, title.subtitle, title.posterUrl, positionSec * 1000L, 0, playing = true)
        return true
    }

    override fun releaseLocal() { calls += "releaseLocal" }
    override fun seekTo(absoluteMs: Long) { calls += "seek $absoluteMs" }
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun stop(api: KobblerApi) {
        calls += "stop"
        now = now.copy(mediaId = null, mediaType = null, playing = false)
    }
    override fun selectSubtitle(trackId: Int?) { calls += "subtitle $trackId" }
    override suspend fun selectAudio(api: KobblerApi, streamIndex: Int) { calls += "audio $streamIndex" }
}
