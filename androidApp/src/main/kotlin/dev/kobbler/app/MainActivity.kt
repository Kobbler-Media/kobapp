package dev.kobbler.app

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.kobbler.app.platform.CastManager
import dev.kobbler.app.platform.FileCache
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.PlaybackBridge
import dev.kobbler.app.platform.Settings

class MainActivity : ComponentActivity() {
    private var isFullscreen by mutableStateOf(false)
    private var isInPip by mutableStateOf(false)
    private var isVideoPlaying = false
    /** Set true on PiP exit; cleared on resume. If onStop fires while true, the user dismissed PiP via X. */
    private var pipJustExited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app has no light theme, so the default auto() style leaves a light-mode
        // device drawing dark status icons onto dark chrome, invisible.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // enableEdgeToEdge() stops at the system bars. Without this the platform
        // letterboxes the window away from a long-edge cutout, so landscape loses a
        // black strip down one side and no amount of inset handling can reclaim it.
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        Settings.init(this)
        FileCache.init(this)
        requestNotificationPermission()

        // Provide context for Cast SDK (actual init happens lazily after server config is fetched)
        if (!isTv()) {
            CastManager.initContext(this)
        }

        // Wire up PlaybackBridge host for Activity-level operations
        PlaybackBridge.host = object : PlaybackBridge.PlaybackHost {
            override fun setKeepScreenOn(on: Boolean) {
                runOnUiThread {
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun setFullscreenMode(enabled: Boolean) {
                runOnUiThread {
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    if (enabled) {
                        insetsController.hide(WindowInsetsCompat.Type.systemBars())
                        insetsController.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        insetsController.show(WindowInsetsCompat.Type.systemBars())
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }

            override fun setVideoPlaying(playing: Boolean) {
                isVideoPlaying = playing
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        val params = PictureInPictureParams.Builder()
                            .setAspectRatio(videoRational())
                            .setAutoEnterEnabled(playing)
                        if (playing) {
                            params.setActions(buildPipActions(PlaybackBridge.nowPlaying.value.isPlaying))
                        }
                        setPictureInPictureParams(params.build())
                    } catch (_: Exception) {}
                }
            }

            override fun setBrightnessLock(level: Float) {
                runOnUiThread {
                    val params = window.attributes
                    params.screenBrightness = if (level < 0f) {
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    } else {
                        level.coerceIn(0f, 1f)
                    }
                    window.attributes = params
                }
            }

            override fun updatePipActions(isPlaying: Boolean) {
                if (Build.VERSION.SDK_INT < 26) return
                if (!isInPip && !(Build.VERSION.SDK_INT >= 31 && isVideoPlaying)) return
                try {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(videoRational())
                        .setActions(buildPipActions(isPlaying))
                    if (Build.VERSION.SDK_INT >= 31) {
                        params.setAutoEnterEnabled(isVideoPlaying)
                    }
                    setPictureInPictureParams(params.build())
                } catch (_: Exception) {}
            }
        }

        val formFactor = if (isTv()) FormFactor.TV else FormFactor.Phone

        setContent {
            App(
                formFactor = formFactor,
                onToggleFullscreen = { toggleFullscreen() },
                isFullscreen = isFullscreen,
            )
        }
        // App paints its own background; the window's would be one more full-screen fill
        // under every frame.
        window.setBackgroundDrawable(null)
    }

    override fun onDestroy() {
        PlaybackBridge.host = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter PiP when user presses home during video playback (pre-API 31)
        if (isVideoPlaying && Build.VERSION.SDK_INT < 31) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
        PlaybackBridge.isInPipMode.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            // Could be either: user maximised PiP (→ onResume next) or dismissed
            // via X (→ onStop next without onResume). Set the flag and let
            // whichever fires first decide.
            pipJustExited = true
        }
    }

    override fun onResume() {
        super.onResume()
        pipJustExited = false
    }

    override fun onStop() {
        super.onStop()
        // The PiP close button does not route a close to the app — it tears the window
        // down and takes the activity with it. Three signals for the one gesture,
        // because no one of them is reliable: the mode change may arrive first
        // (pipJustExited), or not at all, leaving us still nominally in PiP; and a
        // dismissal that finishes the activity outright shows up only as isFinishing.
        // Missing it left the player loaded over a session nobody would ever close, which
        // is what the app came back to.
        val dismissed = pipJustExited || isFinishing ||
            (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)
        if (dismissed) {
            pipJustExited = false
            // While the activity is STOPPED Compose disposals do not fire, so the
            // service's usual cleanup path (PersistentVideoSurface's onDispose) will not
            // run — tear it down explicitly.
            PlaybackBridge.onStop?.invoke()
            PlaybackBridge.clearNowPlaying()
            PlaybackBridge.stopService()
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(videoRational())
                .setActions(buildPipActions(PlaybackBridge.nowPlaying.value.isPlaying))
                .build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) {}
    }

    /** Get the video's native aspect ratio for PiP, clamped to Android's allowed range. */
    private fun videoRational(): Rational {
        val info = PlaybackBridge.nowPlaying.value
        if (info.videoWidth > 0 && info.videoHeight > 0) {
            val ratio = info.videoWidth.toFloat() / info.videoHeight
            // Android requires PiP ratio between 1:2.39 and 2.39:1
            val clamped = ratio.coerceIn(1f / 2.39f, 2.39f)
            return Rational((clamped * 1000).toInt(), 1000)
        }
        return Rational(16, 9)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipActions(isPlaying: Boolean): List<RemoteAction> {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_PAUSE
        }
        val pi = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return listOf(
            RemoteAction(
                Icon.createWithResource(
                    this,
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                ),
                if (isPlaying) "Pause" else "Play",
                if (isPlaying) "Pause playback" else "Resume playback",
                pi,
            )
        )
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }
    }

    private fun isTv(): Boolean {
        val uiModeManager = getSystemService(UiModeManager::class.java)
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
