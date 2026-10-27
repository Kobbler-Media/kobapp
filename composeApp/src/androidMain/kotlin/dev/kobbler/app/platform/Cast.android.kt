package dev.kobbler.app.platform

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteControllerDialog
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
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

        private val _savedAppId = MutableStateFlow<String?>(null)
        val savedAppId: StateFlow<String?> = _savedAppId.asStateFlow()

        /** Call from MainActivity.onCreate to provide context for cast operations. */
        fun initContext(context: Context) {
            appContext = context.applicationContext
            _savedAppId.value = getSavedAppId()
        }

        fun saveAppId(appId: String?) {
            _savedAppId.value = appId
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

// MainActivity is a ComponentActivity, and MediaRouteButton can only open its picker from a
// FragmentActivity — so the dialogs are opened here directly.
@Composable
actual fun CastButton(modifier: Modifier, tint: Color) {
    val context = LocalContext.current
    val appId by CastManager.savedAppId.collectAsState()
    val castContext = remember(appId) {
        if (appId.isNullOrBlank()) null
        else runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    } ?: return
    var connected by remember(castContext) { mutableStateOf(castContext.castState == CastState.CONNECTED) }
    DisposableEffect(castContext) {
        val listener = CastStateListener { connected = it == CastState.CONNECTED }
        castContext.addCastStateListener(listener)
        onDispose { castContext.removeCastStateListener(listener) }
    }
    IconButton(
        onClick = {
            // The dialogs colour themselves from AppCompat's colorPrimary, which the app's
            // platform theme does not define — read as transparent, they refuse to open.
            val themed = ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
            val dialog = if (connected) {
                MediaRouteControllerDialog(themed)
            } else {
                val selector = castContext.mergedSelector ?: return@IconButton
                MediaRouteChooserDialog(themed).apply { routeSelector = selector }
            }
            dialog.show()
        },
        modifier = modifier,
    ) {
        Icon(
            if (connected) Icons.Outlined.CastConnected else Icons.Outlined.Cast,
            contentDescription = "Cast",
            tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
        )
    }
}
