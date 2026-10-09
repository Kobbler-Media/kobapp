package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.Column
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
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.dashboard.home.HomePhone
import dev.kobbler.app.ui.dashboard.home.HomeWide
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    api: KobblerApi,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    onContinueClick: (ContinueItem) -> Unit = {},
    onDetailsClick: (ContinueItem) -> Unit = {},
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

    // The loaded page draws the bar itself, over its hero; until then nothing else would.
    Column(modifier.fillMaxSize()) {
        if (topBar != null && homeState !is Async.Ready) topBar(false)
        AsyncContent(homeState, onRetry = homeRetry, modifier = Modifier.weight(1f)) { homeData ->
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
                val continueClick: (Int) -> Unit = { idx ->
                    data.watchNext.getOrNull(idx)?.let { onContinueClick(it) }
                }
                val detailsClick: (Int) -> Unit = { idx ->
                    data.watchNext.getOrNull(idx)?.let { onDetailsClick(it) }
                }
                when (formFactor) {
                    FormFactor.TV, FormFactor.Desktop -> HomeWide(
                        homeData = data,
                        api = api,
                        onContinueClick = continueClick,
                        onRecentClick = onRecentClick,
                        onDiscoverItemClick = onDiscoverItemClick,
                        onDetailsClick = detailsClick.takeIf { formFactor == FormFactor.Desktop },
                        topBar = topBar,
                    )
                    FormFactor.Phone -> HomePhone(
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
