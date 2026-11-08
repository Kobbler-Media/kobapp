package dev.kobbler.app.data

// Chromium and Safari tone-map HDR onto an SDR display themselves; Firefox shows it washed
// out. Kept in step with kobbler's web client, which applies the same rule.
fun browserHdrFormats(decodesHdr: Boolean, decodesDolbyVision: Boolean, hdrDisplay: Boolean, firefox: Boolean): List<String> =
    buildList {
        if (decodesHdr && (hdrDisplay || !firefox)) addAll(listOf("hdr10", "hlg"))
        if (decodesDolbyVision && hdrDisplay) add("dolby_vision")
    }
