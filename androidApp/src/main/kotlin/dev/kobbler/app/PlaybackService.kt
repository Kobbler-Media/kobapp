package dev.kobbler.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import dev.kobbler.app.android.R
import androidx.media3.session.MediaSession
import dev.kobbler.app.platform.NowPlayingInfo
import dev.kobbler.app.platform.PlaybackBridge
import dev.kobbler.kobplayer.Media3PlayerAdapter
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
class PlaybackService : Service() {
    companion object {
        private const val CHANNEL_ID = "kobbler_playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "dev.kobbler.app.PLAY_PAUSE"
        const val ACTION_SKIP_FORWARD = "dev.kobbler.app.SKIP_FORWARD"
        const val ACTION_SKIP_BACK = "dev.kobbler.app.SKIP_BACK"
        const val ACTION_STOP = "dev.kobbler.app.STOP"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var media3Session: MediaSession? = null
    private var adapter: Media3PlayerAdapter? = null
    // Separate MediaSession for the ebook-reader TTS path. Distinct from
    // media3Session because TTS doesn't have a backing kobplayer.Player — its
    // state lives in PlaybackBridge.nowPlaying + transport callbacks.
    private var ttsSession: MediaSession? = null
    private var ttsPlayer: TtsMediaPlayer? = null
    private var cachedCoverUrl: String? = null
    private var cachedCoverBitmap: Bitmap? = null

    // Pause on headphone disconnect
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (PlaybackBridge.nowPlaying.value.isPlaying) {
                    PlaybackBridge.onPlayPause?.invoke()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Register for headphone disconnect (RECEIVER_NOT_EXPORTED required for API 33+)
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Show initial notification immediately to satisfy foreground service requirement
        val notification = buildNotification(NowPlayingInfo())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Create/recreate Media3 session when player changes (e.g. movie → audiobook).
        // When a kobplayer.Player is active, tear down any TTS-mode session — they're
        // mutually exclusive (only one active media source at a time).
        scope.launch {
            PlaybackBridge.playerFlow.collect { player ->
                media3Session?.release()
                adapter?.cleanup()

                if (player != null) {
                    tearDownTtsSession()
                    adapter = Media3PlayerAdapter(player).also { a ->
                        a.onStopRequested = {
                            PlaybackBridge.onStop?.invoke()
                            stopSelf()
                        }
                        media3Session = MediaSession.Builder(this@PlaybackService, a).build()
                    }
                } else {
                    adapter = null
                    media3Session = null
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(PlaybackBridge.nowPlaying.value))
            }
        }

        // Observe state changes for notification updates + adapter metadata.
        // When nowPlaying is reset to its default empty state (clearNowPlaying
        // on player exit), don't post a "blank" notification — that's how the
        // notification ended up sticking around with no media context.
        scope.launch {
            PlaybackBridge.nowPlaying.collect { info ->
                adapter?.updateMetadata(
                    info.title,
                    info.subtitle,
                    info.coverUrl?.let { Uri.parse(it) },
                )
                if (info.title.isEmpty()) {
                    tearDownTtsSession()
                    return@collect
                }
                // TTS path: if no kobplayer.Player is active but the bridge has
                // controls + a title, ensure the TTS-mode MediaSession exists so
                // the notification surfaces as a proper system media notification
                // (lockscreen, Bluetooth metadata, media tile).
                if (media3Session == null && PlaybackBridge.onPlayPause != null) {
                    ensureTtsSession()
                }
                val notification = buildNotification(info)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, notification)
            }
        }

        // Auto-stop service when player is cleared (screen/activity navigated away from player)
        scope.launch {
            PlaybackBridge.playerFlow.collect { player ->
                if (player == null && PlaybackBridge.onPlayPause == null) {
                    // No active player and no controls — clean up
                    delay(2000) // grace period for player transitions
                    if (PlaybackBridge.playerFlow.value == null && PlaybackBridge.onPlayPause == null) {
                        shutdown()
                    }
                }
            }
        }
    }

    private fun ensureTtsSession() {
        if (ttsSession != null) return
        val player = TtsMediaPlayer()
        ttsPlayer = player
        ttsSession = MediaSession.Builder(this, player).build()
    }

    private fun tearDownTtsSession() {
        ttsSession?.release()
        ttsSession = null
        ttsPlayer?.cleanup()
        ttsPlayer = null
    }

    /** Tear down the foreground service AND remove the notification. Just
     *  calling stopSelf() can leave the notification visible on some Android
     *  versions because the system doesn't always remove it automatically. */
    private fun shutdown() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> PlaybackBridge.onPlayPause?.invoke()
            ACTION_SKIP_FORWARD -> PlaybackBridge.onSkipForward?.invoke()
            ACTION_SKIP_BACK -> PlaybackBridge.onSkipBack?.invoke()
            ACTION_STOP -> {
                PlaybackBridge.onStop?.invoke()
                shutdown()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        scope.cancel()
        adapter?.cleanup()
        adapter = null
        media3Session?.release()
        media3Session = null
        tearDownTtsSession()
        // Belt-and-braces: even if shutdown() didn't run (e.g. service killed),
        // make sure the notification doesn't outlive the process.
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        // Channels arrived in O; below that a notification simply carries its own settings.
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Media playback controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(info: NowPlayingInfo): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Load cover art async if URL changed
        if (info.coverUrl != null && info.coverUrl != cachedCoverUrl) {
            cachedCoverUrl = info.coverUrl
            scope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeStream(java.net.URL(info.coverUrl).openStream())
                    }
                    cachedCoverBitmap = bitmap
                    // buildNotification reads media3Session, whose getters are looper-confined
                    // since media3 1.11 — must stay on Main.
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification(PlaybackBridge.nowPlaying.value))
                } catch (_: Exception) {}
            }
        }

        // Build actions conditionally — TTS only wires playPause + skipForward + stop,
        // so omit unwired ones rather than rendering dead lockscreen buttons.
        val actions = mutableListOf<NotificationCompat.Action>()
        val compactIndices = mutableListOf<Int>()

        if (PlaybackBridge.onSkipBack != null) {
            compactIndices.add(actions.size)
            actions.add(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification_replay_10, "Back 10s",
                    actionIntent(ACTION_SKIP_BACK)
                ).build()
            )
        }

        compactIndices.add(actions.size)
        actions.add(
            NotificationCompat.Action.Builder(
                if (info.isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play,
                if (info.isPlaying) "Pause" else "Play",
                actionIntent(ACTION_PLAY_PAUSE)
            ).build()
        )

        if (PlaybackBridge.onSkipForward != null) {
            compactIndices.add(actions.size)
            actions.add(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification_forward_10, "Forward 10s",
                    actionIntent(ACTION_SKIP_FORWARD)
                ).build()
            )
        }

        actions.add(
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification_close, "Stop",
                actionIntent(ACTION_STOP)
            ).build()
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(info.title.ifEmpty { "Kobbler" })
            .setContentText(info.subtitle ?: "")
            .setSmallIcon(R.drawable.ic_notification_app)
            .setLargeIcon(cachedCoverBitmap)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(info.isPlaying)
        for (action in actions) builder.addAction(action)

        // Use media3's MediaStyleNotificationHelper which properly integrates
        // with the Media3 session — the compat bridge (MediaSessionCompat.Token.fromToken)
        // doesn't reliably produce standard media notifications on modern Android.
        // kobplayer media3Session wins when present (movies/audiobooks); otherwise
        // the TTS session lights up the read-along's lockscreen controls.
        (media3Session ?: ttsSession)?.let { session ->
            builder.setStyle(
                androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(*compactIndices.toIntArray())
            )
        }

        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
