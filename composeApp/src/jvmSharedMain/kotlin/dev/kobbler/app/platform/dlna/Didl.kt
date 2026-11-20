package dev.kobbler.app.platform.dlna

/** The DIDL-Lite item a renderer shows while it plays: title, poster and subtitle sidecar. */
internal fun didlLite(
    title: String,
    mediaUrl: String,
    protocolInfo: String,
    posterUrl: String?,
    subtitleUrl: String?,
): String = buildString {
    append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
    append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
    append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
    append("""xmlns:sec="http://www.sec.co.kr/">""")
    append("""<item id="0" parentID="-1" restricted="1">""")
    append("<dc:title>${title.xmlEscaped()}</dc:title>")
    append("<upnp:class>object.item.videoItem</upnp:class>")
    posterUrl?.let { append("<upnp:albumArtURI>${it.xmlEscaped()}</upnp:albumArtURI>") }
    // Samsung reads the sidecar from its own element; most others from a second res.
    subtitleUrl?.let { append("""<sec:CaptionInfoEx sec:type="srt">${it.xmlEscaped()}</sec:CaptionInfoEx>""") }
    append("""<res protocolInfo="$protocolInfo">${mediaUrl.xmlEscaped()}</res>""")
    subtitleUrl?.let { append("""<res protocolInfo="http-get:*:text/srt:*">${it.xmlEscaped()}</res>""") }
    append("</item></DIDL-Lite>")
}
