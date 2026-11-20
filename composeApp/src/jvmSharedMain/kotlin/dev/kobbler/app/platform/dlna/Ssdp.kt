package dev.kobbler.app.platform.dlna

internal const val SsdpGroup = "239.255.255.250"
internal const val SsdpPort = 1900
internal const val MediaRendererType = "urn:schemas-upnp-org:device:MediaRenderer:1"

internal fun mSearch(searchTarget: String = MediaRendererType, maxWaitSec: Int = 2): String =
    "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SsdpGroup:$SsdpPort\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $maxWaitSec\r\n" +
        "ST: $searchTarget\r\n" +
        "\r\n"

/** A renderer saying it is there, or that it is leaving. */
internal data class SsdpAnnouncement(
    val usn: String,
    val location: String?,
    val alive: Boolean,
    val maxAgeSec: Int,
)

private const val DefaultMaxAgeSec = 1800

/** A search reply or a NOTIFY about a MediaRenderer; null for anything else on the group. */
internal fun parseSsdp(datagram: String): SsdpAnnouncement? {
    val lines = datagram.split("\r\n", "\n")
    val start = lines.firstOrNull()?.trim()?.uppercase() ?: return null
    val isReply = start.startsWith("HTTP/1.1 200")
    val isNotify = start.startsWith("NOTIFY ")
    if (!isReply && !isNotify) return null
    val headers = lines.drop(1).mapNotNull { line ->
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
        line.substring(0, colon).trim().uppercase() to line.substring(colon + 1).trim()
    }.toMap()
    val type = (if (isReply) headers["ST"] else headers["NT"]) ?: return null
    if (!type.equals(MediaRendererType, ignoreCase = true)) return null
    val usn = headers["USN"] ?: return null
    val alive = !isNotify || headers["NTS"].equals("ssdp:alive", ignoreCase = true)
    val maxAge = headers["CACHE-CONTROL"]
        ?.split(',')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("max-age", ignoreCase = true) }
        ?.substringAfter('=')?.trim()?.toIntOrNull()
        ?: DefaultMaxAgeSec
    return SsdpAnnouncement(usn = usn, location = headers["LOCATION"], alive = alive, maxAgeSec = maxAge)
}
