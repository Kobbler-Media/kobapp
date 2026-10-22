package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.ui.components.ListSkeleton
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.LiveFacets
import dev.kobbler.app.data.LiveGuideChannel
import dev.kobbler.app.data.LiveLeases
import dev.kobbler.app.data.LiveRecording
import dev.kobbler.app.data.LiveTuner
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.Async
import dev.kobbler.app.ui.components.AsyncContent
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.SegmentedFilter
import dev.kobbler.app.ui.components.rememberAsync
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.dashboard.livetv.LiveChannelsList
import dev.kobbler.app.ui.dashboard.livetv.LiveFacetChips
import dev.kobbler.app.ui.dashboard.livetv.LiveGuideGrid
import dev.kobbler.app.ui.dashboard.livetv.LiveGuideSearchResults
import dev.kobbler.app.ui.dashboard.livetv.LiveGuideSelectionHolder
import dev.kobbler.app.ui.dashboard.livetv.LivePhoneGuide
import dev.kobbler.app.ui.dashboard.livetv.TunerMeter
import dev.kobbler.app.ui.dashboard.livetv.GuideFutureHours
import dev.kobbler.app.ui.dashboard.livetv.LastLiveChannelKey
import dev.kobbler.app.ui.dashboard.livetv.initialGuideSelection
import dev.kobbler.app.ui.dashboard.livetv.shiftGuideWindow
import dev.kobbler.app.ui.dashboard.livetv.localHhMm
import dev.kobbler.app.ui.dashboard.livetv.nowInstant
import dev.kobbler.app.ui.dashboard.livetv.rememberGuideClock
import dev.kobbler.app.ui.navigation.InterceptBack
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.startLiveWatch
import dev.kobbler.kobplayer.Player
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import dev.kobbler.app.ui.imePaddingUnlessTv

private const val PAGE_SIZE = 50
// A 1080p TV is 540dp tall. Below this the page drops its subtitle line and hides the
// search field behind a chip — an always-present text field is both 66dp the guide grid
// needs and the only thing D-pad Down can reach, which opens the TV IME over the page.
private val DenseLiveTvMaxHeight = 620.dp
// How much guide is loaded, not how much is on screen. The grid scrolls at a fixed
// minutes-per-dp, so this is the length of the axis the viewer can reach without waiting
// on the network — four hours of "what else is on tonight" — while what a programme
// looks like is set by GuideMinuteWidth instead.
private const val GuideWindowMinutes = 240
private const val FavouriteFacet = "favourite"

private enum class LiveTvTab(val label: String) {
    Guide("Guide"), Channels("Channels"), Recordings("Recordings"),
}

private val LiveTvTabLabels = LiveTvTab.entries.map { it.label }

internal data class LiveTvHeader(
    val tuners: List<LiveTuner>,
    val facets: LiveFacets,
    val totalChannels: Int,
    val favouriteCount: Int,
    val leases: LiveLeases?,
    val activeRecordings: List<LiveRecording>,
)

@OptIn(FlowPreview::class)
@Composable
fun LiveTvScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    isActive: Boolean = true,
    player: Player? = null,
    gps: GlobalPlayerState? = null,
    onChannelClick: (String) -> Unit = {},
    onRecordingClick: (String) -> Unit = {},
    onSeriesRulesClick: () -> Unit = {},
    onSeedSeriesRule: (String) -> Unit = {},
    onOnNowClick: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val focusScope = LocalFocusScope.current
    var tabIdx by remember { mutableIntStateOf(0) }

    val (headerState, headerRetry) = rememberCached("livetv-header") {
        coroutineScope {
            val tuners = async { api.getLiveTuners() }
            val lineup = async { api.getLiveChannels(limit = 1) }
            val leases = async { runCatching { api.getLiveLeases() }.getOrNull() }
            val recording = async {
                runCatching { api.getLiveRecordings(status = "recording", limit = 20).recordings }
                    .getOrDefault(emptyList())
            }
            val page = lineup.await()
            LiveTvHeader(
                tuners = tuners.await(),
                facets = page.facets,
                totalChannels = page.total,
                favouriteCount = page.facets.status.firstOrNull { it.value == "favourite" }?.count ?: 0,
                leases = leases.await(),
                activeRecordings = recording.await(),
            )
        }
    }

    // A tab the viewer has stepped away from and come back to is the one moment worth
    // re-reading the tuner for; the rail's own debounce is what keeps a scrub from
    // firing this in the first place.
    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (isActive && wasActive) headerRetry()
        wasActive = isActive
    }

    AsyncContent(headerState, skeleton = { ListSkeleton() }, onRetry = headerRetry, modifier = modifier) { header ->
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val dense = maxHeight < DenseLiveTvMaxHeight
        val colors = KobappTheme.colors
        val dimens = KobappTheme.dimens
        val accent = colors.accLive
        val compact = formFactor == FormFactor.Phone
        val gutter = when (formFactor) {
            FormFactor.TV -> dimens.pageGutterTv
            FormFactor.Phone -> dimens.pageGutterPhone
            else -> dimens.pageGutter
        }

        // On air is read off this, not off the feed's stamp, so a guide that sits open
        // keeps answering OK on whatever is actually airing.
        val clock = rememberGuideClock(active = isActive && tabIdx == LiveTvTab.Guide.ordinal)
        var guideFilter by remember { mutableStateOf<String?>(null) }
        val selection = remember { LiveGuideSelectionHolder() }
        var lastChannelId by remember { mutableStateOf(UserSettings.getString(LastLiveChannelKey)) }
        var windowStart by remember { mutableStateOf(nowInstant()) }
        // Coming back to the tab is coming back to what is on: a window left behind by
        // however long the viewer was away opens on programmes that have finished.
        LaunchedEffect(isActive) {
            if (isActive && windowStart < nowInstant()) windowStart = nowInstant()
        }
        // The grid follows the live edge across the window it was handed; four hours in,
        // the edge walks off the end of that window and there is nothing left to follow.
        // Fetching the next one is the same gesture the stepper's way home makes.
        LaunchedEffect(Unit) {
            snapshotFlow { clock.now }.collect { now ->
                if (now >= windowStart + GuideWindowMinutes.minutes) windowStart = now
            }
        }

        // A query has nothing to do with the window: what is being asked is "when is this
        // on", so it runs against everything the guide holds ahead of now and answers with
        // a flat result list instead of the grid.
        var guideQuery by remember { mutableStateOf("") }
        var guideSearch by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            snapshotFlow { guideQuery }
                .debounce(300)
                .distinctUntilChanged()
                .collect { guideSearch = it.trim().ifBlank { null } }
        }

        val (guideState, guideRetry) = rememberAsync(windowStart, guideSearch) {
            val searching = guideSearch != null
            val from = if (searching) nowInstant() else windowStart
            val to = if (searching) from + GuideFutureHours.hours else from + GuideWindowMinutes.minutes
            api.getLiveGuide(from = from.toString(), to = to.toString(), search = guideSearch)
        }
        val guideAll = (guideState as? Async.Ready)?.value ?: emptyList()
        val guideLoading = guideState is Async.Loading
        val guide = remember(guideAll, guideFilter) { filterGuide(guideAll, guideFilter) }
        // Seeded while composing, not from an effect: the grid's cells exist from this same
        // frame and the Watch button that entry focus wants only once something is selected,
        // so on a TV the first cell took focus first and selected its channel instead.
        if (selection.value == null) initialGuideSelection(guide, lastChannelId)?.let { selection.value = it }

        var searchQuery by remember { mutableStateOf("") }
        var channelFacet by remember { mutableStateOf<String?>(null) }
        var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
        var channelTotal by remember { mutableIntStateOf(0) }
        var channelsLoading by remember { mutableStateOf(false) }

        // Query and facet share one pipeline so only the newest input ever has a request
        // in flight — two collectors racing here let a stale response overwrite a newer one.
        LaunchedEffect(tabIdx) {
            if (tabIdx != LiveTvTab.Channels.ordinal) return@LaunchedEffect
            snapshotFlow { searchQuery to channelFacet }
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { (query, network) ->
                    channelsLoading = true
                    try {
                        val page = api.getLiveChannels(
                            search = query.ifBlank { null },
                            network = network.takeUnless { it == FavouriteFacet },
                            enabled = null,
                            favourite = true.takeIf { network == FavouriteFacet },
                            withNow = true,
                            limit = PAGE_SIZE,
                        )
                        channels = page.channels
                        channelTotal = page.total
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        channels = emptyList()
                        channelTotal = 0
                    }
                    channelsLoading = false
                }
        }

        fun loadMoreChannels() {
            if (channelsLoading || channels.size >= channelTotal) return
            scope.launch {
                channelsLoading = true
                try {
                    val page = api.getLiveChannels(
                        search = searchQuery.ifBlank { null },
                        network = channelFacet.takeUnless { it == FavouriteFacet },
                        enabled = null,
                        favourite = true.takeIf { channelFacet == FavouriteFacet },
                        withNow = true,
                        limit = PAGE_SIZE,
                        offset = channels.size,
                    )
                    channels = channels + page.channels
                    channelTotal = page.total
                } catch (_: Exception) {}
                channelsLoading = false
            }
        }

        var wantEntryFocus by remember { mutableStateOf(false) }
        LaunchedEffect(tabIdx) { wantEntryFocus = true }
        // Dense keeps the field out of the layout until it is asked for; everywhere else
        // it is permanent, so "expanded" is simply always true there.
        var searchExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(tabIdx) { searchExpanded = false }
        // Only the remote suffers from a permanent field — it is the one thing D-pad Down
        // can reach and it opens the TV IME over the page. Touch keeps its visible field
        // even in landscape, where the height test alone would have hidden it.
        val collapsibleSearch = dense && formFactor == FormFactor.TV
        val fieldVisible = !collapsibleSearch || searchExpanded
        val clearSearch = {
            if (tabIdx == LiveTvTab.Guide.ordinal) guideQuery = "" else searchQuery = ""
            searchExpanded = false
            wantEntryFocus = true
        }
        // Derived so typing recomposes the field, not the whole page around it.
        val searchOpen by remember {
            derivedStateOf {
                searchExpanded || when (tabIdx) {
                    LiveTvTab.Guide.ordinal -> guideQuery.isNotBlank()
                    LiveTvTab.Channels.ordinal -> searchQuery.isNotBlank()
                    else -> false
                }
            }
        }
        InterceptBack(enabled = searchOpen, onBack = clearSearch)

        // Whatever the visible list actually puts first. Pinning entry focus to a fixed
        // row lands miles down the page once a filter changes what is on top.
        val entryKey = when (tabIdx) {
            // On a phone, focus lands on the channel the viewer left on. The 10-foot
            // guide describes it in the preview pane instead, so entry focus goes to the
            // Watch button underneath — see LiveGuidePreview.
            LiveTvTab.Guide.ordinal -> if (guideSearch != null) null else {
                initialGuideSelection(guide, lastChannelId)
                    ?.let { seed -> "pe.${seed.entry.id}".takeIf { compact } ?: "watch" }
            }
            LiveTvTab.Channels.ordinal -> (channels.firstOrNull { it.id == lastChannelId } ?: channels.firstOrNull())?.let { "lch.${it.id}" }
            else -> null
        }
        LaunchedEffect(entryKey, wantEntryFocus) {
            if (entryKey == null) return@LaunchedEffect
            if (!wantEntryFocus && focusScope.save() != null) return@LaunchedEffect
            focusScope.focusFirst()
            // After the call: clearing it first re-keys this effect and cancels it.
            wantEntryFocus = false
        }

        // The player covers this tab rather than replacing it, and channel switches inside
        // it only reach the setting, so closing it is the moment to catch up with them.
        val playerUp = gps?.isFullscreen == true
        LaunchedEffect(playerUp) {
            if (playerUp) return@LaunchedEffect
            val last = UserSettings.getString(LastLiveChannelKey)
            if (last == lastChannelId) return@LaunchedEffect
            lastChannelId = last
            initialGuideSelection(guide, last)?.let { selection.value = it }
            wantEntryFocus = true
        }

        LaunchedEffect(guide) {
            // Reseed whenever the selected programme falls out of the window or the filter,
            // otherwise the preview pane describes something the grid no longer shows.
            val current = selection.value
            if (current != null && guide.any { row -> row.entries.any { it.id == current.entry.id } }) {
                return@LaunchedEffect
            }
            selection.value = initialGuideSelection(guide, lastChannelId) ?: return@LaunchedEffect
        }

        val watch: (LiveChannel) -> Unit = { channel ->
            if (gps == null) onChannelClick(channel.id) else startLiveWatch(channel, player, gps, api, scope)
        }

        val tuner = header.tuners.firstOrNull()
        val tunerSlots = header.leases?.total ?: tuner?.tunerCount ?: 0
        val tunerInUse = header.leases?.inUse ?: tuner?.leasesInUse ?: 0
        val ownChannelId = gps?.takeIf { it.mediaType == "livetv" }?.mediaId
        val watching = remember(header, guideAll, ownChannelId) {
            val channelId = ownChannelId
                ?: header.leases?.items?.firstOrNull { it.holderKind != "recording" }?.channelId
            guideAll.firstOrNull { it.channel.id == channelId }?.channel
        }

        val facetStrip: @Composable () -> Unit = {
            LiveFacetChips(
                groupKey = if (tabIdx == LiveTvTab.Guide.ordinal) "guidefacets" else "chanfacets",
                facets = header.facets.network,
                selected = if (tabIdx == LiveTvTab.Guide.ordinal) guideFilter else channelFacet,
                accent = accent,
                leading = listOf(
                    "Favourites ${header.favouriteCount}" to FavouriteFacet,
                    "All ${header.totalChannels}" to null,
                ),
                onSelect = { value ->
                    if (tabIdx == LiveTvTab.Guide.ordinal) guideFilter = value else channelFacet = value
                },
            )
        }

        Column(Modifier.fillMaxSize().imePaddingUnlessTv()) {
            // Above the content: a focus ring is drawn outside the control it belongs to,
            // and the preview pane is the next sibling — it painted over the top half of
            // the ring on every chip in this row.
            Column(
                Modifier
                    .zIndex(1f)
                    .padding(horizontal = gutter)
                    .padding(top = if (dense) dimens.s2 else dimens.s4),
            ) {
                BrowseHeader(
                    title = "Live TV",
                    accent = accent,
                    compact = compact || dense,
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(if (dense) dimens.s2 else dimens.s3),
                        ) {
                            if (collapsibleSearch && tabIdx != LiveTvTab.Recordings.ordinal) {
                                KobButton(
                                    text = "Search",
                                    onClick = { searchExpanded = true },
                                    style = KobButtonStyle.Default,
                                    cornerRadiusDp = 999f,
                                    leading = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp)) },
                                    modifier = Modifier.focusKey("livetv.search", entryEligible = false),
                                )
                            }
                            KobButton(
                                text = "On now",
                                onClick = onOnNowClick,
                                style = KobButtonStyle.Accent,
                                accent = accent,
                                cornerRadiusDp = 999f,
                                leading = { Icon(Icons.Outlined.Bolt, null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.focusKey("livetv.onnow", entryEligible = false),
                            )
                            // Only when it is the answer to "why can I not watch this".
                            // A count of free tuners is a fact about the box, not about
                            // what is on tonight.
                            if (tunerSlots > 0 && tunerInUse >= tunerSlots) {
                                TunerMeter(tunerInUse, tunerSlots, accent)
                            }
                            SegmentedFilter(
                                options = LiveTvTabLabels,
                                selectedIndex = tabIdx,
                                accent = accent,
                                onSelected = { tabIdx = it },
                            )
                        }
                    },
                )
                if (fieldVisible) {
                    when (LiveTvTab.entries[tabIdx]) {
                        LiveTvTab.Guide -> {
                            Spacer(Modifier.height(dimens.s3))
                            LiveSearchField(
                                query = { guideQuery },
                                placeholder = "Search the guide…",
                                accent = accent,
                                autoFocus = collapsibleSearch,
                                onQueryChange = { guideQuery = it },
                                onClear = clearSearch,
                            )
                        }
                        LiveTvTab.Channels -> {
                            Spacer(Modifier.height(dimens.s3))
                            LiveSearchField(
                                query = { searchQuery },
                                placeholder = "Search channels…",
                                accent = accent,
                                autoFocus = collapsibleSearch,
                                onQueryChange = { searchQuery = it },
                                onClear = clearSearch,
                            )
                        }
                        LiveTvTab.Recordings -> {}
                    }
                }
            }

            when (LiveTvTab.entries[tabIdx]) {
                LiveTvTab.Guide -> {
                    val query = guideSearch
                    if (query != null) {
                        LiveGuideSearchResults(
                            results = guide,
                            query = query,
                            loading = guideLoading,
                            clock = clock,
                            accent = accent,
                            gutter = gutter,
                            onSelect = { onChannelClick(it.channel.id) },
                            onWatch = watch,
                        )
                    } else if (compact) {
                        LivePhoneGuide(
                            guide = guide,
                            clock = clock,
                            watching = watching,
                            loading = guideLoading,
                            accent = accent,
                            gutter = gutter,
                            entryKey = entryKey,
                            windowStart = windowStart,
                            windowMinutes = GuideWindowMinutes,
                            facetStrip = facetStrip,
                            onSelect = { onChannelClick(it.channel.id) },
                            onWatch = watch,
                            onShiftWindow = { delta -> windowStart = shiftGuideWindow(windowStart, delta, GuideWindowMinutes) },
                        )
                    } else {
                        LiveGuideGrid(
                            guide = guide,
                            clock = clock,
                            windowStart = windowStart,
                            windowMinutes = GuideWindowMinutes,
                            selection = selection,
                            activeRecordings = header.activeRecordings,
                            loading = guideLoading,
                            accent = accent,
                            canWant = api.canWant,
                            gutter = gutter,
                            facetStrip = facetStrip,
                            onSelect = { selection.value = it },
                            onWatch = watch,
                            onRecord = { entry ->
                                scope.launch {
                                    runCatching { api.recordLiveGuideEntry(entry.id) }
                                    guideRetry()
                                    headerRetry()
                                }
                            },
                            onAddToWanted = { onSeedSeriesRule(it.entry.id) },
                            onShiftWindow = { delta -> windowStart = shiftGuideWindow(windowStart, delta, GuideWindowMinutes) },
                        )
                    }
                }
                LiveTvTab.Channels -> LiveChannelsList(
                    channels = channels,
                    total = channelTotal,
                    loading = channelsLoading,
                    accent = accent,
                    gutter = gutter,
                    entryKey = entryKey,
                    facetStrip = facetStrip,
                    onLoadMore = ::loadMoreChannels,
                    onChannelClick = { onChannelClick(it.id) },
                    onWatch = watch,
                    onToggleFavourite = { channel ->
                        scope.launch {
                            runCatching { api.setLiveChannelFavourite(channel.id, !channel.favourite) }
                            channels = channels.map {
                                if (it.id == channel.id) it.copy(favourite = !channel.favourite) else it
                            }
                            headerRetry()
                        }
                    },
                )
                LiveTvTab.Recordings -> LiveRecordingsScreen(
                    api = api,
                    formFactor = formFactor,
                    showHeader = false,
                    onRecordingClick = onRecordingClick,
                    onSeriesRulesClick = onSeriesRulesClick,
                )
            }
        }
      }
    }
}

private fun filterGuide(guide: List<LiveGuideChannel>, filter: String?): List<LiveGuideChannel> = when (filter) {
    null -> guide
    FavouriteFacet -> guide.filter { it.channel.favourite }
    else -> guide.filter { it.channel.network == filter }
}

@Composable
private fun LiveSearchField(
    query: () -> String,
    placeholder: String,
    accent: Color,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    autoFocus: Boolean = false,
) {
    val colors = KobappTheme.colors
    val keyboardController = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    // The chip that revealed this field is gone from the traversal order by the time the
    // field lands, so nothing else would hand it focus.
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { fieldFocus.requestFocus() } }
    val text = query()
    OutlinedTextField(
        value = text,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, color = colors.textLo, style = KobappTheme.type.body) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.textLo) },
        trailingIcon = if (text.isEmpty()) {
            null
        } else {
            {
                val clearFocus = rememberTvFocusState()
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .focusKey("livesearch.clear", entryEligible = false)
                        .tvFocusGlow(clearFocus, cornerRadiusDp = 999f, scale = 1.08f)
                        .clip(RoundedCornerShape(999.dp))
                        .tvClickable(clearFocus, onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, "Clear search", tint = colors.textLo, modifier = Modifier.size(18.dp))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onAny = { keyboardController?.hide() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent,
            unfocusedBorderColor = colors.line,
            focusedTextColor = colors.textHi,
            unfocusedTextColor = colors.textHi,
            cursorColor = accent,
            focusedContainerColor = colors.elev1,
            unfocusedContainerColor = colors.elev1,
        ),
        // The TV IME opens the moment this field takes focus and nothing else closes it.
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fieldFocus)
            .onFocusChanged { if (!it.isFocused) keyboardController?.hide() },
    )
}
