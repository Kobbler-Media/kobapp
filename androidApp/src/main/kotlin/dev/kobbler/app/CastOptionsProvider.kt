package dev.kobbler.app

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions

/**
 * Provides Cast SDK options including the receiver app ID.
 * The receiver app ID is stored in SharedPreferences by CastManager.init().
 * If no custom ID is configured, falls back to the default media receiver.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val prefs = context.getSharedPreferences("kobbler_cast", Context.MODE_PRIVATE)
        val appId = prefs.getString("receiver_app_id", null)
            ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

        // Our own notification and media session, so the phone's controls carry the title
        // and its artwork rather than Play services' generic card naming only the app.
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(
                NotificationOptions.Builder()
                    .setTargetActivityClassName(MainActivity::class.java.name)
                    .build(),
            )
            .setMediaSessionEnabled(true)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setCastMediaOptions(media)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
