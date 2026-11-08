package dev.kobbler.kobplayer

import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
import android.view.Display.HdrCapabilities.HDR_TYPE_HDR10
import android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
import android.view.Display.HdrCapabilities.HDR_TYPE_HLG
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LibVLC hands Dolby Vision to MediaCodec as plain HEVC without its RPU, so the display
 * showing DV must never make the device claim it.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayHdrTest {

    private val cases = listOf(
        intArrayOf() to emptyList(),
        intArrayOf(HDR_TYPE_HDR10) to listOf("hdr10"),
        intArrayOf(HDR_TYPE_HDR10_PLUS) to listOf("hdr10"),
        intArrayOf(HDR_TYPE_HLG) to listOf("hlg"),
        intArrayOf(HDR_TYPE_DOLBY_VISION) to emptyList(),
        intArrayOf(HDR_TYPE_DOLBY_VISION, HDR_TYPE_HLG, HDR_TYPE_HDR10_PLUS, HDR_TYPE_HDR10) to listOf("hdr10", "hlg"),
    )

    @Test
    @Config(sdk = [33, 35])
    fun `reports the HDR formats the display shows that the player can render`() {
        val app = RuntimeEnvironment.getApplication()
        DisplayHdr.attach(app)
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        for ((types, expected) in cases) {
            shadowOf(display).setDisplayHdrCapabilities(Display.DEFAULT_DISPLAY, 1000f, 500f, 0.1f, *types)
            assertEquals(expected, deviceHdrFormats(), types.joinToString())
        }
    }
}
