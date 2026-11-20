package dev.kobbler.app.castconnect

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import com.google.android.gms.tasks.Tasks
import dev.kobbler.app.castreceiver.castConnectCanServe
import dev.kobbler.app.platform.CAST_NAMESPACE
import dev.kobbler.app.platform.Settings

class CastConnectOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions =
        CastReceiverOptions.Builder(context)
            .setCustomNamespaces(listOf(CAST_NAMESPACE))
            // Declining hands the launch to the web receiver, which needs nothing of this TV.
            .setLaunchRequestChecker {
                Settings.init(context.applicationContext)
                Tasks.forResult(castConnectCanServe())
            }
            .build()
}
