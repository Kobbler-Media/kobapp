package dev.kobbler.app.platform.dlna

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class Seen(val renderer: DlnaRenderer, val expires: TimeMark)

/**
 * The MediaRenderers on the LAN, while collected.
 *
 * Searches at once and every [searchEvery], and whenever [searchNow] fires; between
 * searches it listens for renderers announcing themselves or saying goodbye.
 */
internal fun dlnaRenderers(
    open: () -> SsdpTransport?,
    http: HttpClient,
    searchNow: Flow<Unit>,
    searchEvery: Duration = 60.seconds,
): Flow<List<DlnaRenderer>> = channelFlow {
    val transport = open()
    if (transport == null) {
        send(emptyList())
        awaitClose()
        return@channelFlow
    }
    val lock = Mutex()
    val seen = LinkedHashMap<String, Seen>()
    val fetching = HashSet<String>()

    suspend fun publish() = send(lock.withLock { seen.values.map { it.renderer } })

    suspend fun prune() {
        val gone = lock.withLock { seen.entries.removeAll { it.value.expires.hasPassedNow() } }
        if (gone) publish()
    }

    launch {
        transport.datagrams.collect { datagram ->
            val heard = parseSsdp(datagram) ?: return@collect
            val key = heard.usn.substringBefore("::")
            if (!heard.alive) {
                if (lock.withLock { seen.remove(key) } != null) publish()
                return@collect
            }
            val expires = TimeSource.Monotonic.markNow() + heard.maxAgeSec.seconds
            val location = heard.location ?: return@collect
            val known = lock.withLock {
                seen[key]?.let { seen[key] = Seen(it.renderer, expires) } != null || !fetching.add(key)
            }
            if (known) return@collect
            launch {
                val renderer = describe(http, location)
                lock.withLock {
                    fetching.remove(key)
                    if (renderer != null) seen[key] = Seen(renderer, expires)
                }
                if (renderer != null) publish()
            }
        }
    }
    launch { searchNow.collect { transport.search(mSearch()) } }
    launch {
        while (true) {
            transport.search(mSearch())
            delay(searchEvery)
            prune()
        }
    }
    awaitClose { transport.close() }
}

private suspend fun describe(http: HttpClient, location: String): DlnaRenderer? = runCatching {
    val response = http.get(location)
    if (!response.status.isSuccess()) null else parseDeviceDescription(response.bodyAsText(), location)
}.getOrNull()
