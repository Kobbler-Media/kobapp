package dev.kobbler.app.platform

import android.content.Context
import android.net.Uri
import dev.kobbler.app.data.AppForeground
import dev.kobbler.app.data.SharedHttp
import dev.kobbler.app.platform.dlna.DlnaBackend
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

private const val PREFS_NAME = "kobbler_cast"
private const val PREF_APP_ID = "receiver_app_id"

// A TV is a target, not a sender: initContext only runs off-TV, so there both stay inert.
private val router by lazy {
    CastRouter(
        listOf(
            CastManager.shared,
            DlnaBackend(
                http = SharedHttp.apiClient(),
                openTransport = { CastManager.appContext?.let(::androidSsdpTransport) },
                foreground = AppForeground.state,
                keepAlive = { name -> CastManager.appContext?.let { DlnaKeepAlive.update(it, name) } },
            ),
        ),
    )
}

actual fun castManager(): CastRouter = router

class CastManager private constructor() : CastBackend {
    override val protocol = PlaybackProtocol.GoogleCast
    override val features = setOf(CastFeature.ReceiverReportsProgress, CastFeature.Hls, CastFeature.Audiobook, CastFeature.Tts)
    private var castContext: CastContext? = null
    private var session: CastSession? = null

    private val _device = MutableStateFlow<CastDevice?>(null)
    override val device: StateFlow<CastDevice?> = _device.asStateFlow()

    // No replay: this outlives compositions, and a stale MEDIA_FINISHED replayed to the next
    // one would end whatever cast it starts. It is collecting before any session connects.
    private val _events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val targets: Flow<List<PlaybackTarget>> = savedAppId.flatMapLatest { appId ->
        val context = appContext
        if (appId.isNullOrBlank() || context == null) flowOf(emptyList()) else mediaRoutes(context)
    }

    override suspend fun scanActively() {
        val context = appContext ?: return
        if (savedAppId.value.isNullOrBlank()) return
        withContext(Dispatchers.Main) {
            val selector = runCatching { CastContext.getSharedInstance(context).mergedSelector }.getOrNull()
                ?: return@withContext
            val router = MediaRouter.getInstance(context)
            val callback = object : MediaRouter.Callback() {}
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            try {
                awaitCancellation()
            } finally {
                router.removeCallback(callback)
            }
        }
    }

    override suspend fun connect(target: PlaybackTarget) {
        val context = appContext ?: return
        start()
        withContext(Dispatchers.Main) {
            MediaRouter.getInstance(context).routes.firstOrNull { it.id == target.id }?.select()
        }
    }

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
                override fun onSessionResuming(s: CastSession, sessionId: String) = opening(s)
                override fun onSessionStarting(s: CastSession) = opening(s)
                override fun onSessionEnding(s: CastSession) {}
                override fun onSessionSuspended(s: CastSession, reason: Int) {}
                override fun onSessionEnded(s: CastSession, error: Int) = release()
                override fun onSessionResumeFailed(s: CastSession, error: Int) = release()
                override fun onSessionStartFailed(s: CastSession, error: Int) = release()
            },
            CastSession::class.java,
        )
    }

    private fun opening(s: CastSession) {
        _device.value = CastDevice(s.name(), connecting = true)
    }

    private fun CastSession.name() = castDevice?.friendlyName ?: "Cast device"

    private fun adopt(s: CastSession) {
        session = s
        _device.value = CastDevice(s.name())
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
            .setMetadata(
                MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(MediaMetadata.KEY_TITLE, load.title)
                    load.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
                    load.imageUrl?.let { addImage(WebImage(Uri.parse(it))) }
                },
            )
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

        // One per process: the SDK's session outlives any one composition of the app, and a
        // listener per composition kept answering for screens that were long gone.
        val shared = CastManager()

        /** Call from MainActivity.onCreate; a receiver id saved by an earlier run starts the SDK at once. */
        fun initContext(context: Context) {
            appContext = context.applicationContext
            _savedAppId.value = getSavedAppId()
            shared.start()
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

private fun mediaRoutes(context: Context): Flow<List<PlaybackTarget>> = callbackFlow {
    val castContext = runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    val selector = castContext?.mergedSelector
    if (selector == null) {
        send(emptyList())
        awaitClose()
        return@callbackFlow
    }
    val router = MediaRouter.getInstance(context)
    fun publish() = trySend(router.castRoutes(selector).map { it.target() })
    val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) { publish() }
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) { publish() }
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) { publish() }
    }
    router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
    publish()
    awaitClose { router.removeCallback(callback) }
}.flowOn(Dispatchers.Main)

// The same routes, in the same order, as mediarouter's own chooser offers.
private fun MediaRouter.castRoutes(selector: MediaRouteSelector) =
    routes.filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(selector) }
        .sortedBy { it.name.lowercase() }

private fun MediaRouter.RouteInfo.target() = PlaybackTarget(
    id = id,
    name = name,
    kind = when (deviceType) {
        MediaRouter.RouteInfo.DEVICE_TYPE_TV -> PlaybackTargetKind.Tv
        MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> PlaybackTargetKind.Group
        MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER, MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER -> PlaybackTargetKind.Speaker
        else -> PlaybackTargetKind.Other
    },
    protocol = PlaybackProtocol.GoogleCast,
)
