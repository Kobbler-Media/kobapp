package dev.kobbler.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserHdrTest {

    private data class Case(
        val decodesHdr: Boolean,
        val decodesDolbyVision: Boolean,
        val hdrDisplay: Boolean,
        val firefox: Boolean,
        val expected: List<String>,
    )

    @Test
    fun `HDR is claimed only where the browser can decode it and show or tone-map it`() {
        listOf(
            Case(decodesHdr = false, decodesDolbyVision = true, hdrDisplay = true, firefox = false, expected = listOf("dolby_vision")),
            Case(decodesHdr = true, decodesDolbyVision = false, hdrDisplay = false, firefox = false, expected = listOf("hdr10", "hlg")),
            Case(decodesHdr = true, decodesDolbyVision = false, hdrDisplay = false, firefox = true, expected = emptyList()),
            Case(decodesHdr = true, decodesDolbyVision = false, hdrDisplay = true, firefox = true, expected = listOf("hdr10", "hlg")),
            Case(decodesHdr = true, decodesDolbyVision = true, hdrDisplay = false, firefox = false, expected = listOf("hdr10", "hlg")),
            Case(decodesHdr = true, decodesDolbyVision = true, hdrDisplay = true, firefox = false, expected = listOf("hdr10", "hlg", "dolby_vision")),
            Case(decodesHdr = false, decodesDolbyVision = false, hdrDisplay = true, firefox = false, expected = emptyList()),
        ).forEach { case ->
            assertEquals(
                case.expected,
                browserHdrFormats(case.decodesHdr, case.decodesDolbyVision, case.hdrDisplay, case.firefox),
                case.toString(),
            )
        }
    }
}
