package dev.kobbler.app.data

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One frame off a server event stream.
 *
 * The two streams name their events differently: the library stream sets the SSE
 * `event:` field and repeats it as `kind` inside the payload, while the per-session
 * debrid stream sends nameless frames carrying a `type` field. [kind] is whichever of
 * those the frame actually had, so callers match on one thing.
 */
data class LibraryEventFrame(val kind: String, val data: JsonObject)

internal enum class StreamOutcome { Connected, Dropped, Rejected }

private val RejectedStatuses = setOf(401, 403, 404, 410)

internal class ReconnectPolicy(
    val baseMs: Long = 5_000L,
    val maxMs: Long = 60_000L,
    val rejectedMs: Long = 300_000L,
) {
    // A refusal (revoked token, a server without the endpoint) will not fix itself in a
    // few seconds, and a dead network should not be polled at the pace of a blip.
    fun next(previousMs: Long, outcome: StreamOutcome): Long = when (outcome) {
        StreamOutcome.Connected -> baseMs
        StreamOutcome.Rejected -> rejectedMs
        StreamOutcome.Dropped -> if (previousMs <= 0) baseMs else (previousMs * 2).coerceAtMost(maxMs)
    }
}

/**
 * One connection for every listener, and none at all while the app is in the background:
 * each screen and player collecting its own stream held several sockets open per page, and
 * kept them — and the radio — up behind a locked phone.
 *
 * A listener that joins an already-open stream is handed its own [STREAM_CONNECTED], since
 * the real one went by before it subscribed and that frame is how listeners resync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SharedEventStream(
    upstream: Flow<LibraryEventFrame>,
    foreground: StateFlow<Boolean>,
    scope: CoroutineScope,
    stopTimeoutMs: Long = 5_000L,
) {
    private val connected = MutableStateFlow(false)

    private val shared = foreground
        .flatMapLatest { inFront ->
            if (!inFront) {
                emptyFlow()
            } else {
                upstream
                    .onEach { if (it.kind == STREAM_CONNECTED) connected.value = true }
                    .onCompletion { connected.value = false }
            }
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMs))

    val events: Flow<LibraryEventFrame> = shared.onSubscription {
        if (connected.value) emit(LibraryEventFrame(STREAM_CONNECTED, JsonObject(emptyMap())))
    }
}

/**
 * A frame the client makes up, not the server: emitted each time a stream (re)connects.
 * Events sent while it was down are gone, so a listener that cannot afford to miss one
 * asks for current state on this instead.
 */
const val STREAM_CONNECTED = "_stream_connected"

/** A string field of the payload, or null when absent or not a string. */
fun LibraryEventFrame.idField(name: String): String? =
    (data[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

/**
 * A server-sent-event endpoint, as a cold flow: it connects when collected and hangs up
 * when the collector goes away.
 *
 * Hand-rolled rather than via ktor-client-sse — the parsing is a dozen lines and this
 * way the four engines (OkHttp, Java, JS, Darwin) all behave the same. A dropped
 * connection on a phone that changed networks is the normal case rather than an error
 * worth surfacing, so it reconnects instead of completing.
 *
 * [maxReconnects] bounds that: null for a stream the server never closes on its own,
 * a small number for one that closes when it has said everything it has to say —
 * reconnecting forever there is just a slow poll.
 *
 * Collect inside a scope that ends, such as a `LaunchedEffect` keyed on whatever made
 * the events relevant.
 */
private fun sseFrames(
    httpClient: HttpClient,
    url: String,
    token: String,
    json: kotlinx.serialization.json.Json,
    maxReconnects: Int?,
    policy: ReconnectPolicy = ReconnectPolicy(),
): Flow<LibraryEventFrame> = flow {
    var reconnects = 0
    var delayMs = 0L
    while (true) {
        var outcome = StreamOutcome.Dropped
        try {
            httpClient.prepareGet(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, "text/event-stream")
            }.execute { response ->
                // No expectSuccess on this client, so a 401 arrives as a short body and
                // an immediate EOF. Without this it would reconnect forever, silently.
                if (!response.status.isSuccess()) {
                    if (response.status.value in RejectedStatuses) outcome = StreamOutcome.Rejected
                    return@execute
                }
                outcome = StreamOutcome.Connected
                emit(LibraryEventFrame(STREAM_CONNECTED, JsonObject(emptyMap())))
                val channel = response.bodyAsChannel()
                var eventName: String? = null
                val payload = StringBuilder()
                while (true) {
                    val line = channel.readLine() ?: break
                    when {
                        // A blank line terminates a frame.
                        line.isEmpty() -> {
                            // A nameless frame is still a frame: the debrid stream sends
                            // only `data:` and discriminates on a `type` field inside it.
                            if (payload.isNotEmpty()) {
                                parseFrame(json, eventName, payload.toString())?.let { emit(it) }
                            }
                            eventName = null
                            payload.clear()
                        }
                        line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                        // A frame may carry several data lines; the spec joins them with
                        // newlines. Nothing the server sends does today, but axum splits
                        // any value containing a newline into exactly this shape.
                        line.startsWith("data:") -> {
                            if (payload.isNotEmpty()) payload.append('\n')
                            payload.append(line.removePrefix("data:").trim())
                        }
                        // ":" comments are keep-alives; "id:"/"retry:" are unused here.
                        else -> Unit
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fall through to the delay and try again.
        }
        if (maxReconnects != null && ++reconnects > maxReconnects) return@flow
        delayMs = policy.next(delayMs, outcome)
        delay(delayMs)
    }
}.flowOn(Dispatchers.Default)

/** Library-wide events. The server holds this one open indefinitely. */
fun libraryEventStream(
    httpClient: HttpClient,
    apiUrl: String,
    token: String,
    json: kotlinx.serialization.json.Json,
): Flow<LibraryEventFrame> =
    sseFrames(httpClient, "$apiUrl/api/v1/admin/events", token, json, maxReconnects = null)

/**
 * Events for one debrid playback session — probe, skip segments, thumbnails, download
 * completion. Everything it reports arrives late by nature: a debrid stream is analysed
 * while it plays, so a session that started with no segments and no thumbnails is the
 * normal case rather than the exception.
 *
 * The server says `done` once analysis has finished and it has sent everything; the
 * reconnect cap only rides out network blips before that.
 */
fun debridSessionEventStream(
    httpClient: HttpClient,
    apiUrl: String,
    sessionId: String,
    token: String,
    json: kotlinx.serialization.json.Json,
): Flow<LibraryEventFrame> =
    sseFrames(httpClient, "$apiUrl/api/v1/admin/stream/$sessionId/status", token, json, maxReconnects = 3)
        .takeWhile { it.kind != "done" }

private fun parseFrame(
    json: kotlinx.serialization.json.Json,
    name: String?,
    data: String,
): LibraryEventFrame? = runCatching {
    val obj = json.parseToJsonElement(data) as? JsonObject ?: return null
    // `kind` on the library stream, `type` on the debrid one, and the SSE event name for
    // the job and ingest frames that share the library stream and carry neither.
    val kind = obj["kind"]?.jsonPrimitive?.content
        ?: obj["type"]?.jsonPrimitive?.content
        ?: name
        ?: return null
    LibraryEventFrame(kind = kind, data = obj)
}.getOrNull()

internal fun sseFramesForTest(
    httpClient: HttpClient,
    url: String,
    token: String,
    json: kotlinx.serialization.json.Json,
    policy: ReconnectPolicy,
): Flow<LibraryEventFrame> = sseFrames(httpClient, url, token, json, maxReconnects = null, policy = policy)

/** Test seam for [parseFrame], which is the only part of the stream worth unit testing. */
internal fun parseFrameForTest(
    json: kotlinx.serialization.json.Json,
    name: String?,
    data: String,
): LibraryEventFrame? = parseFrame(json, name, data)
