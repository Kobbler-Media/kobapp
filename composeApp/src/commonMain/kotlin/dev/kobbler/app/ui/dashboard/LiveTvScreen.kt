package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import dev.kobbler.app.ui.PinnedHeader
import dev.kobbler.app.ui.components.Async
import dev.kobbler.app.ui.components.AsyncContent
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.ListSkeleton
import dev.kobbler.app.ui.components.SegmentedFilter
import dev.kobbler.app.ui.components.rememberAsync
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.dashboard.livetv.GuideTimeChip
import dev.kobbler.app.ui.dashboard.livetv.GuideTimePicker
import dev.kobbler.app.ui.dashboard.livetv.LastLiveChannelKey
import dev.kobbler.app.ui.dashboard.livetv.LiveChannelsList
import dev.kobbler.app.ui.dashboard.livetv.LiveFacetChips
import dev.kobbler.app.ui.dashboard.livetv.LiveGuideList
import dev.kobbler.app.ui.dashboard.livetv.TunerMeter
import dev.kobbler.app.ui.dashboard.livetv.forYouPicks
import dev.kobbler.app.ui.dashboard.livetv.guideRowKey
import dev.kobbler.app.ui.dashboard.livetv.initialGuideSelection
import dev.kobbler.app.ui.dashboard.livetv.nowInstant
import dev.kobbler.app.ui.dashboard.livetv.rememberGuideClock
import dev.kobbler.app.ui.imePaddingUnlessTv
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.kobfocus.LocalFocusScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val PAGE_SIZE = 50
// A 1080p TV is 540dp tall; below this the header drops to its compact title.
private val DenseLiveTvMaxHeight = 620.dp
// How much guide is loaded behind the time on screen: enough that every row has what is on
// and the couple of programmes after it.
private const val GuideWindowMinutes = 240
private const val FavouriteFacet = "favourite"

internal enum class LiveTvTab(val label: String) {
    OnNow("On now"), Guide("Guide"), Channels("Channels"), Recordings("Recordings"),
}

internal const val LiveTvTabKey = "livetv.tab"

/**
 * On now is the better front door, but only with suggestions in it; while they are still
 * loading it is held rather than opening the guide and jumping away from it.
 */
internal fun liveTvStartTab(remembered: LiveTvTab?, onNowFilled: Boolean?): LiveTvTab = when {
    remembered != null && remembered != LiveTvTab.OnNow -> remembered
    onNowFilled == false -> LiveTvTab.Guide
    else -> LiveTvTab.OnNow
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

@Composable
fun LiveTvScreen(
    api: KobblerApi,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
    isActive: Boolean = true,
    gps: GlobalPlayerState? = null,
    onChannelClick: (String) -> Unit = {},
    onWatch: (LiveChannel) -> Unit = {},
    onRecordingClick: (String) -> Unit = {},
    onSeriesRulesClick: () -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val focusScope = LocalFocusScope.current
    val rememberedTab = remember { LiveTvTab.entries.firstOrNull { it.name == UserSettings.getString(LiveTvTabKey) } }
    var pickedTab by remember { mutableStateOf<LiveTvTab?>(null) }

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
    val (onNowState, onNowRetry) = rememberCached(LiveOnNowCacheKey) { api.getLiveOnNow() }
    val onNowFilled = when (onNowState) {
        is Async.Ready -> onNowState.value.groups.any { it.items.isNotEmpty() }
        is Async.Loading -> null
        is Async.Error -> false
    }
    // A pick made here is kept even when On now turns out empty: the viewer asked for it.
    val tabIdx = (pickedTab ?: liveTvStartTab(rememberedTab, onNowFilled)).ordinal

    // A tab the viewer has stepped away from and come back to is the one moment worth
    // re-reading the tuner, and what is usually on has moved with the clock.
    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (isActive && wasActive) {
            headerRetry()
            onNowRetry()
        }
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
        var lastChannelId by remember { mutableStateOf(UserSettings.getString(LastLiveChannelKey)) }
        var pickedAt by remember { mutableStateOf<Instant?>(null) }
        var pickingTime by remember { mutableStateOf(false) }
        var liveWindowStart by remember { mutableStateOf(nowInstant()) }
        // Coming back to the tab is coming back to what is on: a window left behind by
        // however long the viewer was away opens on programmes that have finished.
        LaunchedEffect(isActive) {
            if (isActive && liveWindowStart < nowInstant()) liveWindowStart = nowInstant()
        }
        // The live edge walks off the end of the window it was handed four hours in.
        LaunchedEffect(Unit) {
            snapshotFlow { clock.now }.collect { now ->
                if (now >= liveWindowStart + GuideWindowMinutes.minutes) liveWindowStart = now
            }
        }
        val windowStart = pickedAt ?: liveWindowStart

        val (guideState, _) = rememberAsync(windowStart) {
            api.getLiveGuide(from = windowStart.toString(), to = (windowStart + GuideWindowMinutes.minutes).toString())
        }
        val guideAll = (guideState as? Async.Ready)?.value ?: emptyList()
        val guideLoading = guideState is Async.Loading
        val guide = remember(guideAll, guideFilter) { filterGuide(guideAll, guideFilter) }
        var recorded by remember { mutableStateOf(emptySet<String>()) }

        var channelFacet by remember { mutableStateOf<String?>(null) }
        var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
        var channelTotal by remember { mutableIntStateOf(0) }
        var channelsLoading by remember { mutableStateOf(false) }

        // One pipeline so only the newest facet ever has a request in flight — two racing
        // here let a stale response overwrite a newer one.
        LaunchedEffect(tabIdx) {
            if (tabIdx != LiveTvTab.Channels.ordinal) return@LaunchedEffect
            snapshotFlow { channelFacet }
                .distinctUntilChanged()
                .collectLatest { network ->
                    channelsLoading = true
                    try {
                        val page = api.getLiveChannels(
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

        // Whatever the visible list actually puts first, preferring the channel the viewer
        // left on. Pinning entry focus to a fixed row lands miles down the page once a
        // filter changes what is on top.
        val entryKey = when (tabIdx) {
            LiveTvTab.Guide.ordinal -> initialGuideSelection(guide, lastChannelId)?.let { guideRowKey(it.channel.id) }
            LiveTvTab.Channels.ordinal -> (channels.firstOrNull { it.id == lastChannelId } ?: channels.firstOrNull())?.let { "lch.${it.id}" }
            else -> null
        }
        LaunchedEffect(entryKey, wantEntryFocus) {
            if (entryKey == null) return@LaunchedEffect
            if (!wantEntryFocus && focusScope.save() != null) return@LaunchedEffect
            // The rows are lazy and register when they are measured, a frame after the list
            // they belong to is composed; asked any sooner, entry falls through to the tabs.
            withFrameNanos { }
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
            wantEntryFocus = true
        }

        val tuner = header.tuners.firstOrNull()
        val tunerSlots = header.leases?.total ?: tuner?.tunerCount ?: 0
        val tunerInUse = header.leases?.inUse ?: tuner?.leasesInUse ?: 0
        // Only when it is the answer to "why can I not watch this". A count of free tuners
        // is a fact about the box, not about what is on tonight.
        val tunersFull = tunerSlots > 0 && tunerInUse >= tunerSlots

        val facetStrip: @Composable () -> Unit = {
            val onGuide = tabIdx == LiveTvTab.Guide.ordinal
            LiveFacetChips(
                groupKey = if (onGuide) "guidefacets" else "chanfacets",
                facets = header.facets.network,
                selected = if (onGuide) guideFilter else channelFacet,
                accent = accent,
                leading = listOf(
                    "Favourites ${header.favouriteCount}" to FavouriteFacet,
                    "All ${header.totalChannels}" to null,
                ),
                prefix = if (onGuide) {
                    { GuideTimeChip(pickedAt, accent) { pickingTime = true } }
                } else {
                    null
                },
                onSelect = { value -> if (onGuide) guideFilter = value else channelFacet = value },
            )
        }

        if (pickingTime) {
            GuideTimePicker(
                at = pickedAt,
                accent = accent,
                onPick = { pickedAt = it; pickingTime = false },
                onDismiss = { pickingTime = false },
            )
        }

        val tabs: @Composable (Modifier) -> Unit = { tabsModifier ->
            SegmentedFilter(
                options = LiveTvTabLabels,
                selectedIndex = tabIdx,
                accent = accent,
                fillWidth = compact,
                modifier = tabsModifier,
                onSelected = {
                    val tab = LiveTvTab.entries[it]
                    pickedTab = tab
                    UserSettings.putString(LiveTvTabKey, tab.name)
                },
            )
        }

        PinnedHeader(
            header = {
                // Above the content: a focus ring is drawn outside the control it belongs to,
                // and the list is the next sibling — it painted over the top half of the ring.
                Column(
                    Modifier
                        .zIndex(1f)
                        .padding(horizontal = gutter)
                        .padding(top = if (dense || compact) dimens.s2 else dimens.s4),
                ) {
                    if (compact) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.s2)) {
                            tabs(Modifier.weight(1f))
                            if (tunersFull) TunerMeter(tunerInUse, tunerSlots, accent)
                        }
                    } else {
                        BrowseHeader(
                            title = "Live TV",
                            accent = accent,
                            compact = dense,
                            onSearch = onSearchClick,
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(dimens.s3),
                                ) {
                                    if (tunersFull) TunerMeter(tunerInUse, tunerSlots, accent)
                                    tabs(Modifier)
                                }
                            },
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize().imePaddingUnlessTv(),
        ) {
            when (LiveTvTab.entries[tabIdx]) {
                LiveTvTab.OnNow -> LiveOnNowTab(api = api, formFactor = formFactor, gutter = gutter, onWatch = onWatch)
                LiveTvTab.Guide -> LiveGuideList(
                    guide = guide,
                    at = pickedAt,
                    clock = clock,
                    loading = guideLoading,
                    wide = !compact,
                    accent = accent,
                    gutter = gutter,
                    entryKey = entryKey,
                    forYou = forYouPicks((onNowState as? Async.Ready)?.value),
                    recorded = recorded,
                    chips = facetStrip,
                    onWatch = onWatch,
                    onOpenChannel = { onChannelClick(it.id) },
                    onRecord = { entry ->
                        scope.launch {
                            runCatching { api.recordLiveGuideEntry(entry.id) }.onSuccess {
                                recorded = recorded + entry.id
                                headerRetry()
                            }
                        }
                    },
                )
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
                    onWatch = onWatch,
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
