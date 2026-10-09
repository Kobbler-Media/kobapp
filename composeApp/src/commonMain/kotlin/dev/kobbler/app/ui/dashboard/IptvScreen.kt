package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.*
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.bleedHorizontally
import dev.kobbler.app.ui.components.rowEdgeBleed
import dev.kobbler.app.ui.navigation.InterceptBack
import dev.kobbler.app.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import dev.kobbler.app.ui.imePaddingUnlessTv

private const val PAGE_SIZE = 50

private enum class IptvTab(val label: String) {
    Guide("Guide"), Channels("Channels"), Recordings("Recordings"),
}

@OptIn(FlowPreview::class)
@Composable
fun IptvScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    onChannelClick: (IptvChannel) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val focusScope = LocalFocusScope.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var tabIdx by remember { mutableIntStateOf(0) }

    val (headerState, headerRetry) = rememberCached("iptv-header") {
        coroutineScope {
            val favsDeferred = async { try { api.getIptvEpgFavourites() } catch (_: Exception) { IptvEpgFavouritesResponse() } }
            val groupsDeferred = async { api.getIptvGroups() }
            val matchesDeferred = async { try { api.getIptvWatchMatches() } catch (_: Exception) { emptyList() } }

            val favResponse = favsDeferred.await()
            val groups = groupsDeferred.await()
            val matches = matchesDeferred.await()

            val favouriteChannels = favResponse.grid.map { fav ->
                val nowProgram = fav.programs.firstOrNull()
                val nextProgram = fav.programs.drop(1).firstOrNull()
                IptvChannel(
                    id = fav.channel.id,
                    name = fav.channel.name,
                    iconUrl = fav.channel.iconUrl,
                    category = fav.channel.category,
                    language = null, country = null,
                    isFavourite = true,
                    nowPlaying = nowProgram?.let { IptvEpgEntry(it.id, it.title, it.description, it.startTime, it.stopTime, it.categories) },
                    nextUp = nextProgram?.let { IptvEpgEntry(it.id, it.title, it.description, it.startTime, it.stopTime, it.categories) },
                )
            }

            val watchMatches = matches.map {
                IptvWatchMatch(
                    watchId = it.watchId, channelId = it.channelId,
                    channelName = it.channelName, channelIconUrl = it.channelIconUrl,
                    epgTitle = it.epgTitle, startTime = it.startTime, stopTime = it.stopTime,
                    isLive = it.isLive,
                )
            }

            IptvHeaderData(favourites = favouriteChannels, groups = groups, watchMatches = watchMatches)
        }
    }

    var allChannels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var allChannelsTotal by remember { mutableIntStateOf(0) }
    var channelsLoading by remember { mutableStateOf(false) }
    var channelsInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(headerState) {
        if (headerState is Async.Ready && !channelsInitialized) {
            channelsInitialized = true
            channelsLoading = true
            try {
                val page = api.getIptvChannels(category = selectedGroup, limit = PAGE_SIZE, offset = 0)
                allChannels = page.channels
                allChannelsTotal = page.total
            } catch (_: Exception) {}
            channelsLoading = false
        }
    }

    fun loadMoreChannels() {
        if (channelsLoading || allChannels.size >= allChannelsTotal) return
        scope.launch {
            channelsLoading = true
            try {
                val page = api.getIptvChannels(
                    category = selectedGroup, limit = PAGE_SIZE, offset = allChannels.size,
                )
                allChannels = allChannels + page.channels
                allChannelsTotal = page.total
            } catch (_: Exception) {}
            channelsLoading = false
        }
    }

    LaunchedEffect(selectedGroup) {
        if (!channelsInitialized) return@LaunchedEffect
        channelsLoading = true
        allChannels = emptyList()
        allChannelsTotal = 0
        try {
            val page = api.getIptvChannels(category = selectedGroup, limit = PAGE_SIZE, offset = 0)
            allChannels = page.channels
            allChannelsTotal = page.total
        } catch (_: Exception) {}
        channelsLoading = false
    }

    var searchResults by remember { mutableStateOf<List<IptvChannel>?>(null) }
    var searchLoading by remember { mutableStateOf(false) }
    var wantGuideFocus by remember { mutableStateOf(false) }
    // Query and group share one pipeline so only the newest input ever has a request in
    // flight — two collectors racing here let a stale response overwrite a newer one.
    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery to selectedGroup }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest { (query, group) ->
                if (query.isBlank()) {
                    searchResults = null
                    searchLoading = false
                    return@collectLatest
                }
                searchLoading = true
                try {
                    searchResults = api.getIptvChannels(
                        search = query, category = group, limit = PAGE_SIZE,
                    ).channels
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    searchResults = emptyList()
                }
                searchLoading = false
            }
    }

    AsyncContent(headerState, onRetry = headerRetry, modifier = modifier) { data ->
        val colors = KobappTheme.colors
        val dimens = KobappTheme.dimens
        val accent = colors.accIptv
        val compact = formFactor == FormFactor.Phone
        val gutter = when (formFactor) {
            FormFactor.TV -> dimens.pageGutterTv
            FormFactor.Phone -> dimens.pageGutterPhone
            else -> dimens.pageGutter
        }
        val isSearching = searchQuery.isNotBlank()
        val listState = rememberLazyListState()

        val clearSearch = {
            searchQuery = ""
            searchResults = null
            wantGuideFocus = true
        }
        InterceptBack(enabled = isSearching, onBack = clearSearch)

        val shouldLoadMore by remember {
            derivedStateOf {
                if (isSearching) return@derivedStateOf false
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = listState.layoutInfo.totalItemsCount
                lastVisible >= totalItems - 5 && allChannels.size < allChannelsTotal
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) loadMoreChannels()
        }

        val countLine = buildString {
            append(allChannelsTotal.coerceAtLeast(data.favourites.size))
            append(" channels")
            if (data.favourites.isNotEmpty()) {
                append(" · ")
                append(data.favourites.size)
                append(" favourites")
            }
        }

        val uniqueMatches = remember(data.watchMatches) {
            data.watchMatches.distinctBy { Triple(it.watchId, it.channelId, it.startTime) }
        }

        // Whatever the guide puts in its first row. Pinning entry focus to a fixed list
        // (allChannels[0]) lands miles down the page once matches or favourites push it
        // out of view, and the library runs to ~26k channels.
        val guideEntryKey = when {
            tabIdx != 0 -> null
            isSearching -> searchResults?.firstOrNull()?.let { "search.channel.${it.id}" }
            uniqueMatches.isNotEmpty() -> uniqueMatches.first()
                .let { "match.${it.watchId}.${it.channelId}.${it.startTime}" }
            data.favourites.isNotEmpty() -> "fav.channel.${data.favourites.first().id}"
            allChannels.isNotEmpty() -> "channel.${allChannels.first().id}"
            else -> null
        }

        // Two ways to end up with nothing focused: FocusScopeHost's entry pass gives up
        // after ~16 frames while a cold open's channel page lands well after that, and
        // dismissing the search strands focus on rows that are about to be disposed.
        // Both wait here for the guide to have a row again. The save() guard keeps this
        // from yanking focus back to the top every time results change.
        LaunchedEffect(guideEntryKey, wantGuideFocus) {
            if (guideEntryKey == null) return@LaunchedEffect
            if (!wantGuideFocus && focusScope.save() != null) return@LaunchedEffect
            focusScope.focusFirst()
            // After the call: clearing it first re-keys this effect and cancels it.
            wantGuideFocus = false
        }

        Column(Modifier.fillMaxSize().background(colors.bg).imePaddingUnlessTv()) {
            Column(Modifier.padding(horizontal = gutter, vertical = dimens.s4)) {
                BrowseHeader(
                    title = "IPTV",
                    accent = accent,
                    countLine = countLine,
                    compact = compact,
                    trailing = {
                        SegmentedFilter(
                            options = IptvTab.entries.map { it.label },
                            selectedIndex = tabIdx,
                            accent = accent,
                            onSelected = { tabIdx = it },
                        )
                    },
                )

                if (tabIdx == 0) {
                    Spacer(Modifier.height(dimens.s3))

                    IptvSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onCommit = { wantGuideFocus = true },
                        onClear = clearSearch,
                        accent = accent,
                    )

                    if (data.groups.isNotEmpty()) {
                        Spacer(Modifier.height(dimens.s2))
                        val groupsBleed = rowEdgeBleed()
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = groupsBleed),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .bleedHorizontally(groupsBleed)
                                .fillMaxWidth()
                                .focusKeyGroup("groups"),
                        ) {
                            item {
                                KobChip(
                                    text = "All",
                                    style = if (selectedGroup == null) KobChipStyle.AccentTint else KobChipStyle.Default,
                                    accent = accent,
                                    onClick = { selectedGroup = null },
                                    modifier = Modifier.focusKey("all"),
                                )
                            }
                            items(data.groups) { group ->
                                val selected = selectedGroup == group.name
                                KobChip(
                                    text = "${group.name} (${group.channelCount})",
                                    style = if (selected) KobChipStyle.AccentTint else KobChipStyle.Default,
                                    accent = accent,
                                    onClick = { selectedGroup = if (selected) null else group.name },
                                    modifier = Modifier.focusKey("group.${group.name}"),
                                )
                            }
                        }
                    }
                }
            }

            when (tabIdx) {
                1 -> {
                    IptvChannelsDirectoryScreen(
                        api = api,
                        formFactor = formFactor,
                        onChannelClick = { id ->
                            onChannelClick(IptvChannel(id = id, name = "", iconUrl = null, category = null, language = null, country = null))
                        },
                        showHeader = false,
                    )
                    return@Column
                }
                2 -> {
                    IptvRecordingsScreen(api = api, formFactor = formFactor, showHeader = false)
                    return@Column
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = gutter, end = gutter,
                    top = dimens.s2, bottom = dimens.s6,
                ),
                verticalArrangement = Arrangement.spacedBy(dimens.s1),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (isSearching) {
                    val results = searchResults ?: emptyList()
                    if (results.isNotEmpty()) {
                        item("search-heading") {
                            Box(Modifier.padding(vertical = dimens.s2)) {
                                SectionHeading(
                                    title = "Results (${results.size})",
                                    accent = accent,
                                )
                            }
                        }
                    }
                    items(results, key = { "search-${it.id}" }) { channel ->
                        val rowKey = "search.channel.${channel.id}"
                        ChannelRow(
                            channel = channel,
                            accent = accent,
                            key = rowKey,
                            preferredEntry = rowKey == guideEntryKey,
                            onClick = { onChannelClick(channel) },
                            onToggleFavourite = {
                                scope.launch {
                                    toggleFavourite(api, channel)
                                    headerRetry()
                                }
                            },
                        )
                    }
                    if (searchLoading) {
                        item("search-loading") { LoadingRow(accent) }
                    } else if (results.isEmpty()) {
                        item("search-empty") {
                            EmptyState(
                                icon = Icons.Outlined.LiveTv,
                                title = "No channels found",
                                subtitle = "Try a different search",
                            )
                        }
                    }
                } else {
                    if (uniqueMatches.isNotEmpty()) {
                        item("matches-heading") {
                            Box(Modifier.padding(vertical = dimens.s2)) {
                                SectionHeading(
                                    title = "Upcoming Matches",
                                    accent = accent,
                                )
                            }
                        }
                        items(
                            uniqueMatches,
                            key = { "match-${it.watchId}-${it.channelId}-${it.startTime}" },
                        ) { match ->
                            val rowKey = "match.${match.watchId}.${match.channelId}.${match.startTime}"
                            WatchMatchRow(
                                match = match,
                                accent = accent,
                                key = rowKey,
                                preferredEntry = rowKey == guideEntryKey,
                                onClick = {
                                    onChannelClick(
                                        IptvChannel(
                                            id = match.channelId, name = match.channelName,
                                            iconUrl = match.channelIconUrl, category = null,
                                            language = null, country = null,
                                        ),
                                    )
                                },
                            )
                        }
                    }

                    if (data.favourites.isNotEmpty()) {
                        item("fav-heading") {
                            Box(Modifier.padding(top = dimens.s3, bottom = dimens.s2)) {
                                SectionHeading(
                                    title = "Favourites",
                                    accent = accent,
                                )
                            }
                        }
                        items(data.favourites, key = { "fav-${it.id}" }) { channel ->
                            val rowKey = "fav.channel.${channel.id}"
                            ChannelRow(
                                channel = channel,
                                accent = accent,
                                key = rowKey,
                                preferredEntry = rowKey == guideEntryKey,
                                onClick = { onChannelClick(channel) },
                                onToggleFavourite = {
                                    scope.launch {
                                        toggleFavourite(api, channel)
                                        headerRetry()
                                    }
                                },
                            )
                        }
                    }

                    if (allChannels.isNotEmpty() || channelsLoading) {
                        item("all-heading") {
                            Box(Modifier.padding(top = dimens.s3, bottom = dimens.s2)) {
                                SectionHeading(
                                    title = "All Channels",
                                    accent = accent,
                                    trailing = {
                                        Text(
                                            "$allChannelsTotal total",
                                            color = colors.textLo,
                                            style = KobappTheme.type.bodySm,
                                        )
                                    },
                                )
                            }
                        }
                        items(allChannels, key = { it.id }) { channel ->
                            val rowKey = "channel.${channel.id}"
                            ChannelRow(
                                channel = channel,
                                accent = accent,
                                key = rowKey,
                                preferredEntry = rowKey == guideEntryKey,
                                onClick = { onChannelClick(channel) },
                                onToggleFavourite = {
                                    scope.launch {
                                        toggleFavourite(api, channel)
                                        headerRetry()
                                    }
                                },
                            )
                        }
                        if (channelsLoading) {
                            item("all-loading") { LoadingRow(accent) }
                        }
                    }

                    if (data.favourites.isEmpty() && allChannels.isEmpty() && !channelsLoading) {
                        item("empty") {
                            EmptyState(
                                icon = Icons.Outlined.LiveTv,
                                title = "No channels",
                                subtitle = "No IPTV channels configured",
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun toggleFavourite(api: KobblerApi, channel: IptvChannel) {
    try {
        if (channel.isFavourite && channel.favouriteId != null) {
            api.removeIptvFavourite(channel.favouriteId)
        } else {
            api.addIptvFavourite(channel.id)
        }
    } catch (_: Exception) {}
}

@Composable
private fun IptvSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onCommit: () -> Unit,
    onClear: () -> Unit,
    accent: Color,
) {
    val colors = KobappTheme.colors
    val keyboardController = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                "Search channels…",
                color = colors.textLo,
                style = KobappTheme.type.body,
            )
        },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.textLo)
        },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                val clearFocus = rememberTvFocusState()
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .focusKey("search.clear")
                        .tvFocusGlow(clearFocus, cornerRadiusDp = 999f, scale = 1.08f)
                        .clip(RoundedCornerShape(999.dp))
                        .tvClickable(clearFocus, onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Clear channel search",
                        tint = colors.textLo,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // The TV IME's tick only dismisses if something actually handles the action.
        keyboardActions = KeyboardActions(
            onAny = {
                keyboardController?.hide()
                onCommit()
            },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent,
            unfocusedBorderColor = colors.line,
            focusedTextColor = colors.textHi,
            unfocusedTextColor = colors.textHi,
            cursorColor = accent,
            focusedContainerColor = colors.elev1,
            unfocusedContainerColor = colors.elev1,
        ),
        // The TV IME opens the moment this field takes focus and nothing else ever
        // closes it, so it has to close itself the moment focus leaves.
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) keyboardController?.hide() },
    )
}

@Composable
private fun LoadingRow(accent: Color) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp))
    }
}

private data class IptvHeaderData(
    val favourites: List<IptvChannel>,
    val groups: List<IptvGroup>,
    val watchMatches: List<IptvWatchMatch>,
)

@Composable
private fun ChannelIcon(
    iconUrl: String?,
    name: String,
    accent: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(
                Brush.linearGradient(listOf(lerp(colors.elev2, accent, 0.18f), colors.elev3)),
            )
            .border(1.dp, colors.lineSoft, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            Icon(
                Icons.Outlined.LiveTv,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

@Composable
private fun WatchMatchRow(
    match: IptvWatchMatch,
    accent: Color,
    key: String,
    preferredEntry: Boolean,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val focusState = rememberTvFocusState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(key, preferredEntry = preferredEntry)
            .tvFocusGlow(focusState, cornerRadiusDp = 10f, scale = 1.02f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (match.isLive) lerp(colors.elev1, accent, 0.06f) else colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .tvClickable(focusState, onClick)
            .padding(horizontal = dimens.s3, vertical = dimens.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelIcon(match.channelIconUrl, match.channelName, accent, 44.dp)
        Spacer(Modifier.width(dimens.s3))
        Column(Modifier.weight(1f)) {
            Text(
                match.epgTitle,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                match.channelName,
                style = KobappTheme.type.caption,
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (match.isLive) {
            Spacer(Modifier.width(dimens.s2))
            LivePill(accent)
        }
    }
}

@Composable
private fun LivePill(accent: Color) {
    Box(
        modifier = Modifier
            .clip(KobappTheme.shapes.pill)
            .background(accent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            "LIVE",
            color = KobappTheme.colors.bg,
            style = KobappTheme.type.labelSm,
        )
    }
}

@Composable
private fun ChannelRow(
    channel: IptvChannel,
    accent: Color,
    key: String,
    preferredEntry: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val focusState = rememberTvFocusState()
    // Favourite and play are siblings of the row body, not children of it: a focusable
    // ancestor swallows a D-pad press aimed at its own descendants, so nesting them
    // left both reachable only with a mouse. See EpisodeRowFocusTest.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKeyGroup(key)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .focusGroup()
            .padding(horizontal = dimens.s3, vertical = dimens.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .focusKey("row", preferredEntry = preferredEntry)
                .tvFocusGlow(focusState, cornerRadiusDp = 10f, scale = 1.01f)
                .clip(RoundedCornerShape(10.dp))
                .tvClickable(focusState, onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelIcon(channel.iconUrl, channel.name, accent, 44.dp)
            Spacer(Modifier.width(dimens.s3))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val now = channel.nowPlaying?.title
                if (!now.isNullOrBlank()) {
                    Text(
                        now,
                        style = KobappTheme.type.caption,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val next = channel.nextUp?.title
                if (!next.isNullOrBlank()) {
                    Text(
                        "Next · $next",
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(dimens.s2))
        FavouriteButton(
            isFavourite = channel.isFavourite,
            accent = accent,
            onClick = onToggleFavourite,
        )
        Spacer(Modifier.width(dimens.s1))
        PlayIconButton(accent = accent, onClick = onClick)
    }
}

@Composable
private fun FavouriteButton(isFavourite: Boolean, accent: Color, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    val tint = if (isFavourite) accent else colors.textLo
    Box(
        modifier = Modifier
            .size(36.dp)
            .focusKey("favourite")
            .tvAutoGlow(cornerRadiusDp = 8f, scale = 1.08f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isFavourite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (isFavourite) "Remove favourite" else "Add favourite",
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun PlayIconButton(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .focusKey("play")
            .tvAutoGlow(cornerRadiusDp = 999f, scale = 1.08f)
            .clip(RoundedCornerShape(999.dp))
            .background(lerp(KobappTheme.colors.elev2, accent, 0.18f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.PlayArrow,
            contentDescription = "Watch",
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
    }
}
