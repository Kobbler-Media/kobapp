package dev.kobbler.app.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

private const val PREFS_NAME = "kobbler_cast"
private const val PREF_APP_ID = "receiver_app_id"

actual fun castManager(): CastSender = CastManager()

class CastManager : CastSender {
    private var castContext: CastContext? = null
    private var session: CastSession? = null

    private val _device = MutableStateFlow<CastDevice?>(null)
    override val device: StateFlow<CastDevice?> = _device.asStateFlow()

    // Replay one: a position that lands between the handover and the collector starting
    // is the only news the mini controller has until the next broadcast, five seconds on.
    private val _events = MutableSharedFlow<CastEvent>(replay = 1, extraBufferCapacity = 16)
    override val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    override fun start() {
        if (castContext != null) return
        val context = appContext ?: return
        // Nothing to attach to until the server has named a receiver app; the config it
        // comes with arrives after the first screen, so this is retried rather than done
        // once at launch.
        if (getSavedAppId().isNullOrBlank()) return
        castContext = try {
            CastContext.getSharedInstance(context)
        } catch (_: Exception) {
            return
        }
        val manager = castContext?.sessionManager ?: return
        manager.currentCastSession?.let { adopt(it) }
        manager.addSessionManagerListener(
            object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(s: CastSession, sessionId: String) = adopt(s)
                override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = adopt(s)
                override fun onSessionResuming(s: CastSession, sessionId: String) {}
                override fun onSessionStarting(s: CastSession) {}
                override fun onSessionEnding(s: CastSession) {}
                override fun onSessionSuspended(s: CastSession, reason: Int) {}
                override fun onSessionEnded(s: CastSession, error: Int) = release()
                override fun onSessionResumeFailed(s: CastSession, error: Int) = release()
                override fun onSessionStartFailed(s: CastSession, error: Int) = release()
            },
            CastSession::class.java,
        )
    }

    private fun adopt(s: CastSession) {
        session = s
        _device.value = CastDevice(s.castDevice?.friendlyName ?: "Cast device")
        try {
            s.setMessageReceivedCallbacks(CAST_NAMESPACE) { _, _, message ->
                parseCastEvent(message)?.let { _events.tryEmit(it) }
            }
        } catch (_: Exception) {
        }
    }

    private fun release() {
        session = null
        _device.value = null
    }

    override suspend fun load(load: CastLoad) {
        val client = session?.remoteMediaClient ?: return
        val media = MediaInfo.Builder(load.contentUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(load.contentType)
            .setContentUrl(load.contentUrl)
            // The receiver reads the session off the load itself — its LOAD interceptor
            // takes `kobblerSession` and is running before any namespace message can be
            // delivered, which is why the same payload goes both ways.
            .setCustomData(JSONObject().put("kobblerSession", JSONObject(load.payloadJson)))
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(media)
            .setAutoplay(true)
            .setCurrentTime(load.startPositionSec * 1000L)
            .build()
        send("INIT_SESSION", load.payloadJson)
        // The SDK asserts the main thread for anything that touches a session.
        withContext(Dispatchers.Main) { client.load(request) }
    }

    override suspend fun send(type: String, payloadJson: String) {
        val message = buildJsonObject {
            put("type", type)
            if (payloadJson.isNotBlank()) {
                put("payload", Json.parseToJsonElement(payloadJson))
            }
        }.toString()
        val s = session ?: return
        withContext(Dispatchers.Main) {
            try {
                s.sendMessage(CAST_NAMESPACE, message)
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun seekTo(positionSeconds: Int) {
        val client = session?.remoteMediaClient ?: return
        withContext(Dispatchers.Main) {
            client.seek(MediaSeekOptions.Builder().setPosition(positionSeconds * 1000L).build())
        }
    }

    override suspend fun togglePlayPause() {
        val client = session?.remoteMediaClient ?: return
        withContext(Dispatchers.Main) {
            if (client.isPlaying) client.pause() else client.play()
        }
    }

    override suspend fun stop() {
        val manager = castContext?.sessionManager ?: return
        withContext(Dispatchers.Main) {
            try {
                manager.endCurrentSession(true)
            } catch (_: Exception) {
            }
        }
        release()
    }

    companion object {
        internal var appContext: Context? = null

        /** Call from MainActivity.onCreate to provide context for cast operations. */
        fun initContext(context: Context) {
            appContext = context.applicationContext
        }

        fun saveAppId(appId: String?) {
            val ctx = appContext ?: return
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    if (appId.isNullOrBlank()) remove(PREF_APP_ID)
                    else putString(PREF_APP_ID, appId)
                }
                .apply()
        }

        fun getSavedAppId(): String? {
            val ctx = appContext ?: return null
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_APP_ID, null)
        }
    }
}

actual fun saveCastAppId(appId: String?) {
    CastManager.saveAppId(appId)
}

/**
 * Cast route button — only shown when a valid cast receiver app ID is configured
 * and the Cast SDK is available on this device.
 */
@Composable
actual fun CastButton() {
    val context = LocalContext.current

    // Only show if we have a saved app ID (set after fetching server config)
    val appId = remember { CastManager.getSavedAppId() }
    if (appId.isNullOrBlank()) return

    var available by remember {
        mutableStateOf(
            try { CastContext.getSharedInstance(context); true }
            catch (_: Exception) { false }
        )
    }
    if (!available) return

    AndroidView(
        factory = { ctx ->
            try {
                MediaRouteButton(ctx).also {
                    CastButtonFactory.setUpMediaRouteButton(ctx, it)
                }
            } catch (_: Exception) {
                available = false
                android.view.View(ctx)
            }
        },
    )
}
