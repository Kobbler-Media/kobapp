package dev.kobbler.app.data

/**
 * How deep a player should buffer a source before starting it, in ms.
 *
 * The figure is about the path to the bytes, not the file. A server on the same LAN
 * answers a range request in a millisecond or two, so anything beyond a few hundred ms of
 * prebuffer is dead time — and it is paid again on every seek, which is what made a resume
 * feel slow. A debrid CDN over the internet genuinely needs the deeper buffer, and so does
 * the viewer's own server reached from outside the house.
 *
 * Which of those a host is cannot be read off its name. A self-hosted server is usually
 * reached through a public hostname — `kobbler.example.com` resolving to a LAN address at
 * home and to a public one away from it — so the name says "internet" in both cases while
 * the connection behind it is a different animal each time. The name is therefore only a
 * fast path for hosts that can only be local; the general answer comes from measuring the
 * connection, which is the thing the buffer is actually sized against.
 */
internal fun networkCacheMsFor(streamUrl: String, apiUrl: String, originIsNear: Boolean): Int = when {
    isLanHost(hostOf(streamUrl)) -> LanNetworkCacheMs
    // Only the API's own origin has been measured. A debrid CDN is a host this client has
    // never timed, and guessing it is near because the library server is would be wrong in
    // the direction that stutters.
    originIsNear && sameOrigin(streamUrl, apiUrl) -> LanNetworkCacheMs
    else -> RemoteNetworkCacheMs
}

const val LanNetworkCacheMs = 300
const val RemoteNetworkCacheMs = 1500

/**
 * Round-trip time at or under which the server counts as near.
 *
 * Comfortably above a LAN's couple of milliseconds and below anything crossing a
 * consumer internet connection, so the two do not meet in the middle. A link that really
 * does answer this fast can carry the shallow buffer whatever it is made of.
 */
const val NearOriginRoundTripMs = 25L

/**
 * The recent round-trip times to one origin, kept so the buffer can be sized against the
 * connection rather than against a guess about the hostname.
 *
 * The **minimum** of the recent samples is what classifies: any individual request can be
 * slow because the server was busy or the body was large, but a request cannot come back
 * faster than the network allows. A ring of the last [Samples] means walking out of the
 * house reclassifies within that many requests rather than staying wrong for the session.
 *
 * Lock-free on purpose. Requests land from many coroutines and this is a hint, not a
 * ledger: a race loses or overwrites a sample, which the minimum absorbs. No element is
 * ever torn, so there is nothing to protect.
 */
class OriginLatency(private val samples: Int = 16) {
    private val ring = LongArray(samples) { Long.MAX_VALUE }
    private var next = 0

    fun record(elapsedMs: Long) {
        if (elapsedMs < 0) return
        ring[next % samples] = elapsedMs
        next++
    }

    /** Null until anything has been timed, so callers can tell "far" from "not yet known". */
    fun roundTripMs(): Long? = ring.min().takeIf { it != Long.MAX_VALUE }

    fun isNear(): Boolean = (roundTripMs() ?: return false) <= NearOriginRoundTripMs
}

internal fun sameOrigin(url: String, other: String): Boolean {
    val host = hostOf(url) ?: return false
    return host == hostOf(other)
}

internal fun hostOf(url: String): String? {
    val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
    if (afterScheme.isEmpty()) return null
    val authority = afterScheme.substringBefore('/').substringBefore('?')
    // Strip credentials and port. IPv6 literals are bracketed, so the port colon is the
    // one after the closing bracket rather than any colon in the address.
    val hostPort = authority.substringAfterLast('@')
    val host = if (hostPort.startsWith("[")) {
        hostPort.substringAfter('[').substringBefore(']')
    } else {
        hostPort.substringBefore(':')
    }
    return host.lowercase().takeIf { it.isNotEmpty() }
}

/**
 * Hosts that cannot be anywhere but the local network, whatever the connection measures.
 *
 * A name that resolves publicly is deliberately *not* here — see [networkCacheMsFor].
 */
internal fun isLanHost(host: String?): Boolean {
    if (host == null) return false
    if (host == "localhost" || host == "::1") return true
    if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
    // A name with no dot in it cannot be resolved by public DNS, so it is something the
    // local network knows about — a NAS hostname, a container name, a hosts entry.
    if (!host.contains('.') && !host.contains(':')) return true
    val octets = host.split('.')
    if (octets.size != 4) return false
    val n = octets.map { it.toIntOrNull() ?: return false }
    if (n.any { it !in 0..255 }) return false
    return when (n[0]) {
        10, 127 -> true
        192 -> n[1] == 168
        172 -> n[1] in 16..31
        169 -> n[1] == 254
        else -> false
    }
}
