package dev.kobbler.app.castconnect

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.support.v4.media.session.MediaSessionCompat
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.RequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.SenderInfo
import com.google.android.gms.cast.tv.media.MediaCommandCallback
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.SeekRequestData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import dev.kobbler.app.castreceiver.CastReceiver
import dev.kobbler.app.castreceiver.parseReceiverLoad
import dev.kobbler.app.platform.CAST_NAMESPACE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object CastConnect {
    private var context: CastReceiverContext? = null

    fun init(app: Context) {
        if (context != null || !app.isTelevision()) return
        CastReceiverContext.initInstance(app)
        val receiver = CastReceiverContext.getInstance()
        context = receiver
        receiver.registerEventCallback(
            object : CastReceiverContext.EventCallback() {
                override fun onSenderConnected(sender: SenderInfo) = CastReceiver.senderConnected()
                override fun onStopApplication() = CastReceiver.stop()
            },
        )
        receiver.setMessageReceivedListener(CAST_NAMESPACE) { _, _, message -> CastReceiver.message(message) }
        CastReceiver.outbox = { json -> receiver.sendMessage(CAST_NAMESPACE, null, json) }
        val media = receiver.mediaManager
        media.setMediaLoadCommandCallback(LoadCallback)
        media.setMediaCommandCallback(CommandCallback)
        val status = MediaStatusSync(media)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { CastReceiver.now.collect(status::apply) }
    }

    fun handle(intent: Intent?): Boolean = intent != null && context?.mediaManager?.onNewIntent(intent) == true

    fun onForeground() { context?.start() }

    fun onBackground() { context?.stop() }

    fun attachSession(token: android.media.session.MediaSession.Token?) {
        context?.mediaManager?.setSessionCompatToken(token?.let { MediaSessionCompat.Token.fromToken(it) })
    }

    private object LoadCallback : MediaLoadCommandCallback() {
        override fun onLoad(senderId: String?, request: MediaLoadRequestData): Task<MediaLoadRequestData> {
            val load = parseReceiverLoad(request.mediaInfo?.customData?.toString(), request.currentTime / 1000.0)
                ?: return Tasks.forException(failure(MediaError.ERROR_REASON_INVALID_PARAMS))
            val done = TaskCompletionSource<MediaLoadRequestData>()
            CastReceiver.load(load) { opened ->
                if (!opened) return@load done.setException(failure(MediaError.ERROR_REASON_INVALID_PARAMS))
                context?.mediaManager?.apply {
                    setDataFromLoad(request)
                    broadcastMediaStatus()
                }
                done.setResult(request)
            }
            return done.task
        }

        private fun failure(reason: String) =
            MediaException(MediaError.Builder().setType(MediaError.ERROR_TYPE_LOAD_FAILED).setReason(reason).build())
    }

    private object CommandCallback : MediaCommandCallback() {
        override fun onPlay(senderId: String?, data: RequestData): Task<Void?> = done { CastReceiver.play() }
        override fun onPause(senderId: String?, data: RequestData): Task<Void?> = done { CastReceiver.pause() }
        override fun onStop(senderId: String?, data: RequestData): Task<Void?> = done { CastReceiver.stop() }

        override fun onSeek(senderId: String?, data: SeekRequestData): Task<Void?> = done {
            val now = CastReceiver.now.value
            val target = data.currentTime ?: data.relativeTime?.let { (now?.positionMs ?: 0) + it }
            target?.let { CastReceiver.seek(it.coerceAtLeast(0)) }
            when (data.resumeState) {
                MediaSeekOptions.RESUME_STATE_PLAY -> CastReceiver.play()
                MediaSeekOptions.RESUME_STATE_PAUSE -> CastReceiver.pause()
            }
        }

        private fun done(block: () -> Unit): Task<Void?> {
            block()
            return Tasks.forResult(null)
        }
    }
}

private fun Context.isTelevision() =
    getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
