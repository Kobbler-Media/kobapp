package dev.kobbler.app.platform.dlna

import dev.kobbler.app.data.ClientCapabilities

/** What every DLNA video renderer is required to take. */
internal val DlnaBaseline = ClientCapabilities(
    videoCodecs = listOf("h264"),
    audioCodecs = listOf("aac"),
    containers = listOf("mp4", "ts"),
    maxHeight = 1080,
    // DLNA's AVC profiles stop at the HD ones, level 4.x; a Samsung Frame refuses High@5.0.
    maxH264Level = 41,
)

/**
 * What a renderer decodes, read off the Sink list ConnectionManager reports.
 *
 * Each entry is `protocol:network:mime:extra`, where extra may name a DLNA profile
 * (`DLNA.ORG_PN=AVC_TS_HD_AC3`) that says more about the codecs than the MIME type does.
 * Anything not named is left to the baseline: a renderer that lists nothing still plays it.
 */
internal fun capabilitiesFromSink(sink: String): ClientCapabilities {
    val entries = sink.split(',').mapNotNull { entry ->
        val parts = entry.trim().split(':', limit = 4)
        if (parts.size < 3) null else parts[2].lowercase() to parts.getOrElse(3) { "" }.uppercase()
    }
    val mimes = entries.map { it.first }.toSet()
    val profiles = entries.mapNotNull { (_, extra) ->
        extra.split(';').firstOrNull { it.startsWith("DLNA.ORG_PN=") }?.substringAfter('=')
    }
    fun mime(vararg names: String) = names.any { it in mimes }
    fun profile(vararg marks: String) = profiles.any { p -> marks.any { it in p } }

    val video = buildList {
        add("h264")
        if (mime("video/hevc", "video/h265") || profile("HEVC", "H265")) add("hevc")
        if (mime("video/mpeg", "video/mpeg2") || profile("MPEG_PS", "MPEG_TS_SD", "MPEG_TS_HD")) add("mpeg2video")
        if (mime("video/webm") || profile("VP9")) add("vp9")
        if (profile("AV1")) add("av1")
    }
    val audio = buildList {
        add("aac")
        if (mime("audio/ac3", "audio/vnd.dolby.dd-raw", "audio/x-ac3") || profile("AC3")) add("ac3")
        if (mime("audio/eac3", "audio/vnd.dolby.dd-plus", "audio/x-eac3") || profile("EAC3", "DDPLUS")) add("eac3")
        if (mime("audio/mpeg", "audio/mp3")) add("mp3")
        if (mime("audio/flac", "audio/x-flac")) add("flac")
        if (mime("audio/vnd.dts", "audio/x-dts") || profile("DTS")) add("dts")
    }
    val containers = buildList {
        add("mp4")
        add("ts")
        if (mime("video/x-matroska", "video/x-mkv", "video/mkv")) {
            add("mkv")
            add("matroska")
        }
        if (mime("video/webm")) add("webm")
        if (mime("video/x-msvideo", "video/avi")) add("avi")
        if (mime("video/quicktime")) add("mov")
    }
    return DlnaBaseline.copy(videoCodecs = video, audioCodecs = audio, containers = containers)
}
