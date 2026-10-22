package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Represents the state of an async operation.
 */
sealed interface Async<out T> {
    data object Loading : Async<Nothing>
    data class Error(val message: String, val cause: Throwable? = null) : Async<Nothing>
    data class Ready<T>(val value: T) : Async<T>
}

/**
 * Launch an async load keyed on [key], storing results in an [Async] state.
 * Returns the state and a retry callback.
 */
@Composable
fun <T> rememberAsync(
    vararg key: Any?,
    load: suspend () -> T,
): Pair<Async<T>, () -> Unit> {
    var state by remember(*key) { mutableStateOf<Async<T>>(Async.Loading) }
    var retryTrigger by remember(*key) { mutableIntStateOf(0) }

    LaunchedEffect(*key, retryTrigger) {
        state = Async.Loading
        state = try {
            Async.Ready(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e::class.simpleName ?: "Unknown error"
            println("[Async] $msg")
            Async.Error(msg, e)
        }
    }

    return state to { retryTrigger++ }
}

/**
 * Full-screen loading. [skeleton] is required, not defaulted: a skeleton is a promise of
 * the layout about to arrive, and one borrowed from another screen breaks it.
 */
@Composable
fun LoadingScreen(skeleton: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(KobappTheme.colors.bg)) {
        skeleton()
    }
}

/**
 * Full-screen error display with retry button.
 */
@Composable
fun ErrorScreen(
    message: String,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    Box(modifier.fillMaxSize().background(colors.bg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Outlined.Warning, "Error",
                tint = colors.err,
                modifier = Modifier.size(48.dp),
            )
            Text(
                message,
                style = KobappTheme.type.body,
                color = colors.textHi,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onBack != null) {
                    KobButton(text = "Go Back", onClick = onBack, style = KobButtonStyle.Default)
                }
                if (onRetry != null) {
                    KobButton(
                        onClick = onRetry,
                        style = KobButtonStyle.Primary,
                    ) {
                        Icon(Icons.Outlined.Refresh, "Retry", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry", style = KobappTheme.type.body)
                    }
                }
            }
        }
    }
}

/**
 * Render an [Async] value: the screen's own [skeleton], an error screen, or content.
 */
@Composable
fun <T> AsyncContent(
    state: Async<T>,
    skeleton: @Composable () -> Unit,
    onRetry: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is Async.Loading -> LoadingScreen(skeleton, modifier)
        is Async.Error -> ErrorScreen(state.message, onRetry, onBack, modifier)
        is Async.Ready -> content(state.value)
    }
}

// -- Stale-while-revalidate cache --

/**
 * A cursor-paginated page, cached whole.
 *
 * Browse screens keep their rows in a caller-owned [androidx.compose.runtime.snapshots.SnapshotStateList]
 * so paging can append to it, but the loader that fills that list is not guaranteed to
 * run: a warm TTL entry skips it, and a consumer that loses the in-flight race never has
 * one. Caching the cursors alongside the rows lets those screens rebuild their state from
 * the resolved value instead of from the loader's side effects.
 */
@Immutable
data class CachedPage<T>(
    val items: List<T>,
    val next: String? = null,
    val prev: String? = null,
    val total: Long? = null,
)

private const val CACHE_CAPACITY = 30
private val DEFAULT_TTL = 45.seconds

/** Longer TTLs for keys whose backing data is near-static. Matched by prefix, first hit wins. */
private val TTL_OVERRIDES = listOf(
    "letters" to 10.minutes,
    "authors" to 5.minutes,
    "series" to 5.minutes,
)

/**
 * In-memory stale-while-revalidate cache: shows cached data immediately, refetches in
 * the background.
 *
 * Every mutable field is an [AtomicReference] over an immutable map updated by CAS — the
 * cache is written from arbitrary coroutine contexts and read from composition, and
 * commonMain has no lock primitive that covers all five targets without a new dependency.
 *
 * Versioning is per-key: each key owns its own [MutableIntState], so invalidating one key
 * recomposes only that key's consumers. A snapshot state *map* would not — reading any
 * entry records a read of the whole map.
 */
@OptIn(ExperimentalAtomicApi::class)
object DataCache {
    private class Entry(val value: Any?, val storedAt: TimeMark?)

    private val entries = AtomicReference<Map<String, Entry>>(emptyMap())
    private val versions = AtomicReference<Map<String, MutableIntState>>(emptyMap())
    private val inFlight = AtomicReference<Map<String, CompletableDeferred<Any?>>>(emptyMap())

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = entries.load()[key]?.value as? T

    fun <T> put(key: String, value: T) {
        versionState(key)
        while (true) {
            val current = entries.load()
            val next = LinkedHashMap<String, Entry>(current)
            next.remove(key)
            next[key] = Entry(value, TimeSource.Monotonic.markNow())
            while (next.size > CACHE_CAPACITY) next.remove(next.keys.first())
            if (entries.compareAndSet(current, next)) return
        }
    }

    /** True while [key]'s entry is younger than its TTL, i.e. revalidation can be skipped. */
    fun isFresh(key: String): Boolean {
        val storedAt = entries.load()[key]?.storedAt ?: return false
        return storedAt.elapsedNow() < ttlFor(key)
    }

    /** Compose-observable version for [key]. Read by [rememberCached]. */
    fun versionOf(key: String): Int = versionState(key).intValue

    /** Bump versions for [keys] and expire their entries; consumers of those keys refetch. */
    fun invalidate(vararg keys: String) = keys.forEach(::bump)

    fun invalidateWatchProgress() {
        invalidate("home", "dashboard")
        invalidatePrefix("show-")
        invalidatePrefix("movie-")
    }

    /**
     * Bump versions for every known key starting with [prefix]. Useful for invalidating a
     * family of dynamically-keyed entries (e.g. `show-*`) without enumerating each one.
     */
    fun invalidatePrefix(prefix: String) {
        (entries.load().keys + versions.load().keys)
            .filter { it.startsWith(prefix) }
            .forEach(::bump)
    }

    fun clear() {
        entries.store(emptyMap())
        versions.store(emptyMap())
        inFlight.store(emptyMap())
    }

    /**
     * Runs [loader] at most once per [key] across concurrent callers; losers await the
     * winner's result. The winner runs [loader] in its own coroutine, so it still cancels
     * with the caller that started it — a waiter then takes the work over rather than
     * inheriting a dead request.
     */
    suspend fun <T> load(key: String, loader: suspend () -> T): T {
        while (true) {
            val current = inFlight.load()
            val running = current[key]
            if (running != null) {
                val outcome = runCatching { running.await() }
                coroutineContext.ensureActive()
                if (outcome.isSuccess) {
                    @Suppress("UNCHECKED_CAST")
                    return outcome.getOrThrow() as T
                }
                dropInFlight(key, running)
                continue
            }
            val slot = CompletableDeferred<Any?>()
            if (!inFlight.compareAndSet(current, current + (key to slot))) continue
            try {
                val value = loader()
                put(key, value)
                slot.complete(value)
                return value
            } catch (t: Throwable) {
                slot.completeExceptionally(t)
                throw t
            } finally {
                dropInFlight(key, slot)
            }
        }
    }

    internal fun ttlFor(key: String): Duration =
        TTL_OVERRIDES.firstOrNull { key.startsWith(it.first) }?.second ?: DEFAULT_TTL

    private fun bump(key: String) {
        expire(key)
        versionState(key).intValue++
    }

    /** Keeps the value (so stale-while-revalidate still has something to show) but drops its TTL. */
    private fun expire(key: String) {
        while (true) {
            val current = entries.load()
            val existing = current[key] ?: return
            if (existing.storedAt == null) return
            if (entries.compareAndSet(current, current + (key to Entry(existing.value, null)))) return
        }
    }

    private fun versionState(key: String): MutableIntState {
        while (true) {
            val current = versions.load()
            current[key]?.let { return it }
            val fresh = mutableIntStateOf(0)
            if (versions.compareAndSet(current, current + (key to fresh))) return fresh
        }
    }

    private fun dropInFlight(key: String, slot: CompletableDeferred<Any?>) {
        while (true) {
            val current = inFlight.load()
            if (current[key] !== slot) return
            if (inFlight.compareAndSet(current, current - key)) return
        }
    }
}

/**
 * Like [rememberAsync] but with stale-while-revalidate semantics:
 * - If cached data exists for [cacheKey], shows it immediately (no loading spinner)
 * - Refetches only once the entry is past its TTL; concurrent consumers of the same key
 *   share a single request
 * - On error during revalidation, keeps showing stale data
 *
 * The returned callback forces a refetch for every consumer of [cacheKey], not just this one.
 */
@Composable
fun <T> rememberCached(
    cacheKey: String,
    vararg key: Any?,
    load: suspend () -> T,
): Pair<Async<T>, () -> Unit> {
    val cached = remember(cacheKey) { DataCache.get<T>(cacheKey) }
    var state by remember(cacheKey, *key) {
        mutableStateOf<Async<T>>(if (cached != null) Async.Ready(cached) else Async.Loading)
    }
    val dataVersion = DataCache.versionOf(cacheKey)

    LaunchedEffect(cacheKey, *key, dataVersion) {
        val hasStale = state is Async.Ready
        if (hasStale && DataCache.isFresh(cacheKey)) return@LaunchedEffect
        if (!hasStale) state = Async.Loading
        state = try {
            Async.Ready(DataCache.load(cacheKey, load))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (hasStale) return@LaunchedEffect
            val msg = e.message ?: e::class.simpleName ?: "Unknown error"
            println("[Cached] $msg")
            Async.Error(msg, e)
        }
    }

    return state to { DataCache.invalidate(cacheKey) }
}

/**
 * Silently revalidate a [rememberCached] entry when the server says its subject changed.
 *
 * [onRefresh] is the invalidate callback `rememberCached` hands back. Because that call
 * keeps the stale value on screen while it refetches, an enrichment landing behind an
 * open detail screen swaps the content in underneath the reader — no spinner, no jump
 * back to a loading state.
 *
 * Emissions are conflated and spaced: several of these events are batchable server-side
 * and arrive up to once a second during a scan, which is far more often than a page
 * needs to be refetched.
 */
@Composable
fun RefreshOnEvents(
    api: dev.kobbler.app.data.KobblerApi,
    key: Any?,
    matches: (dev.kobbler.app.data.LibraryEventFrame) -> Boolean,
    onRefresh: () -> Unit,
    coalesceMs: Long = RefreshCoalesceMs,
) {
    val currentMatches by rememberUpdatedState(matches)
    val currentRefresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(key) {
        api.libraryEvents()
            .filter { frame -> runCatching { currentMatches(frame) }.getOrDefault(false) }
            .conflate()
            .collect {
                currentRefresh()
                // Anything arriving inside this window collapses into the next single
                // refresh rather than queueing a refetch each.
                delay(coalesceMs)
            }
    }
}

private const val RefreshCoalesceMs = 2_000L

/**
 * Refetch watch state when the app comes back to the front. Progress saved on another
 * device while this one sat in the background otherwise stays hidden behind the cache
 * until something else happens to invalidate it.
 */
@Composable
fun RefreshWatchStateOnFocus(focused: Boolean, timeSource: TimeSource = TimeSource.Monotonic) {
    var leftAt by remember { mutableStateOf<TimeMark?>(null) }
    LaunchedEffect(focused) {
        if (!focused) {
            leftAt = timeSource.markNow()
            return@LaunchedEffect
        }
        // A glance at a notification or an alt-tab is not long enough for another device
        // to have moved anything, and every refetch redraws the page under the viewer.
        if (leftAt?.let { it.elapsedNow() >= RefocusRefreshAfter } == true) DataCache.invalidateWatchProgress()
        leftAt = null
    }
}

private val RefocusRefreshAfter = 30.seconds
