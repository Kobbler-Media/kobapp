package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.Column
import dev.kobbler.app.ui.dashboard.home.HomeSkeleton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.kobbler.app.data.*
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.ui.components.Async
import dev.kobbler.app.ui.components.AsyncContent
import dev.kobbler.app.ui.components.DataCache
import dev.kobbler.app.ui.components.RefreshOnEvents
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.dashboard.home.HomePhone
import dev.kobbler.app.ui.dashboard.home.HomeWide
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    api: KobblerApi,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    onContinueClick: (ContinueItem) -> Unit = {},
    onContinueDetails: (ContinueItem) -> Unit = {},
    onRecentClick: (RecentlyAddedItem) -> Unit = {},
    onDiscoverItemClick: (DiscoverItem) -> Unit = {},
    topBar: (@Composable (transparent: Boolean) -> Unit)? = null,
) {
    val (homeState, homeRetry) = rememberCached("home") { api.getDiscover() }

    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (isActive && wasActive) homeRetry()
        wasActive = isActive
    }

    if (isActive) {
        RefreshOnEvents(
            api = api,
            key = Unit,
            matches = ::refreshesHome,
            onRefresh = homeRetry,
            coalesceMs = HomeRefreshCoalesceMs,
        )
    }

    // The loaded page draws the bar itself, over its hero; until then nothing else would.
    Column(modifier.fillMaxSize()) {
        if (topBar != null && homeState !is Async.Ready) topBar(false)
        AsyncContent(homeState, skeleton = { HomeSkeleton() }, onRetry = homeRetry, modifier = Modifier.weight(1f)) { homeData ->
            val continueClick: (Int) -> Unit = { idx ->
                homeData.watchNext.getOrNull(idx)?.let { onContinueClick(it) }
            }
            when (formFactor) {
                FormFactor.TV, FormFactor.Desktop -> HomeWide(
                    homeData = homeData,
                    api = api,
                    onContinueClick = continueClick,
                    onContinueDetails = { idx -> homeData.watchNext.getOrNull(idx)?.let(onContinueDetails) },
                    onRecentClick = onRecentClick,
                    onDiscoverItemClick = onDiscoverItemClick,
                    topBar = topBar,
                )
                // A wheel or trackpad scrolling up at the top of the page pulls the
                // indicator down with nothing to release it, so only touch gets one.
                FormFactor.Phone -> {
                    val scope = rememberCoroutineScope()
                    var isRefreshing by remember { mutableStateOf(false) }
                    var data by remember { mutableStateOf(homeData) }
                    LaunchedEffect(homeData) { data = homeData }
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            scope.launch {
                                try { data = api.getDiscover(); DataCache.put("home", data) } catch (_: Exception) {}
                                isRefreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        HomePhone(
                            homeData = data,
                            api = api,
                            onContinueClick = continueClick,
                            onRecentClick = onRecentClick,
                            onDiscoverItemClick = onDiscoverItemClick,
                        )
                    }
                }
            }
        }
    }
}

// Everything the page is built from: watch state, library rows, and want status on the
// discover shelves. A scan fires these per item, hence the wide coalescing window — the
// first one still lands at once.
private val HomeEventKinds = setOf(
    "watch_progress",
    "movie_updated",
    "tv_show_updated",
    "tv_season_updated",
    "tv_episode_updated",
    "library_updated",
    "wanted_status_updated",
)

private const val HomeRefreshCoalesceMs = 10_000L

// This device's own playback saves every few seconds and the server echoes each one back;
// refetching the page for them redrew Home under a mini player all through a film. Only
// finishing the title changes what Home shows, and leaving the player refreshes it anyway.
internal fun refreshesHome(frame: LibraryEventFrame): Boolean {
    if (frame.kind !in HomeEventKinds) return false
    if (frame.kind != "watch_progress" || frame.idField("client_id") != PlaybackClient.id) return true
    return (frame.data["watched"] as? JsonPrimitive)?.booleanOrNull == true
}
