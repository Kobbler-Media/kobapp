package dev.kobbler.app.ui.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamLabelsTest {

    @Test
    fun `the codec and audio ids kobload sends read as people name them`() {
        val cases = mapOf(
            "avc" to "H.264", "h264" to "H.264", "x264" to "x264", "hevc" to "HEVC", "h265" to "HEVC",
            "x265" to "x265", "av1" to "AV1", "ddp" to "DD+", "eac3" to "DD+", "ac3" to "DD", "dd" to "DD",
            "truehd" to "TrueHD", "dts" to "DTS", "aac" to "AAC", "DD+ 5.1" to "DD+ 5.1", "flac" to "FLAC",
        )
        assertEquals(cases, cases.mapValues { (raw, _) -> streamSpecLabel(raw) })
    }

    @Test
    fun `a debrid service is named, not shown by its id`() {
        val cases = mapOf(
            "real_debrid" to "Real-Debrid", "all_debrid" to "AllDebrid", "premiumize" to "Premiumize",
            "torbox" to "TorBox", "debrid_link" to "Debrid-Link", "Real-Debrid" to "Real-Debrid", "easy_news" to "Easy News",
        )
        assertEquals(cases, cases.mapValues { (raw, _) -> debridServiceName(raw) })
    }
}
