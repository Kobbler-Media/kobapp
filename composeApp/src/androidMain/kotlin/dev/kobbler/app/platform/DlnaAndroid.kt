package dev.kobbler.app.platform

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
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

/**
 * The phone runs a DLNA session the renderer cannot run for itself, every second, so while
 * one runs Wi-Fi stays up, the CPU awake and the playback service in front.
 */
object DlnaKeepAlive {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    internal fun update(context: Context, rendererName: String?) {
        if (rendererName == null) {
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock?.takeIf { it.isHeld }?.release()
        } else {
            @Suppress("DEPRECATION")
            wifiLock = wifiLock ?: context.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kobbler:dlna")
                ?.apply { setReferenceCounted(false) }
            wakeLock = wakeLock ?: context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kobbler:dlna")
                ?.apply { setReferenceCounted(false) }
            wifiLock?.acquire()
            wakeLock?.acquire()
        }
        PlaybackBridge.driveRemote(rendererName)
    }
}
