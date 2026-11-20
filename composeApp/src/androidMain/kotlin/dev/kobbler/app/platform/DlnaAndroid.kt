package dev.kobbler.app.platform

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dev.kobbler.app.platform.dlna.SsdpTransport
import dev.kobbler.app.platform.dlna.UdpSsdpTransport

/** Wi-Fi drops multicast to save power unless something holds this lock. */
internal fun androidSsdpTransport(context: Context): SsdpTransport? {
    val lock = context.getSystemService(WifiManager::class.java)
        ?.createMulticastLock("kobbler:ssdp")
        ?.apply { setReferenceCounted(false); acquire() }
    val transport = UdpSsdpTransport.open()
    if (transport == null) {
        lock?.release()
        return null
    }
    return object : SsdpTransport by transport {
        override fun close() {
            transport.close()
            lock?.takeIf { it.isHeld }?.release()
        }
    }
}

object DlnaKeepAlive {
    const val EXTRA_NAME = "renderer"
    private const val SERVICE = "dev.kobbler.app.DlnaCastService"

    internal fun update(context: Context, rendererName: String?) {
        val intent = Intent().setClassName(context.packageName, SERVICE)
        if (rendererName == null) {
            context.stopService(intent)
        } else {
            runCatching { ContextCompat.startForegroundService(context, intent.putExtra(EXTRA_NAME, rendererName)) }
        }
    }
}
