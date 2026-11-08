package dev.kobbler.kobplayer

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Display.HdrCapabilities.HDR_TYPE_HDR10
import android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
import android.view.Display.HdrCapabilities.HDR_TYPE_HLG

object DisplayHdr {
    @Volatile
    private var context: Context? = null

    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    // Asked per session rather than cached: a TV box's display is whatever is on the end of
    // its HDMI cable today. Dolby Vision is never claimed: LibVLC hands it to MediaCodec as
    // plain HEVC without applying the RPU, so Profile 5 comes out green and purple.
    internal fun formats(): List<String> {
        val display = context?.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return emptyList()
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        }
        return buildList {
            if (HDR_TYPE_HDR10 in types || HDR_TYPE_HDR10_PLUS in types) add("hdr10")
            if (HDR_TYPE_HLG in types) add("hlg")
        }
    }
}
