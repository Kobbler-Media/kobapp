package dev.kobbler.app.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.kobbler.app.platform.BackHandler
import dev.kobbler.app.data.*
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappColorsDefault
import dev.kobbler.app.theme.KobappDimens
import dev.kobbler.app.theme.KobappDimensDefault
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.focusInert
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalKobappDimens
import dev.kobbler.app.theme.LocalSection
import dev.kobbler.app.ui.kobChromeInsets
import dev.kobbler.app.theme.Section
import dev.kobbler.app.ui.components.LocalTvFocusDecor
import dev.kobbler.app.ui.components.AsyncContent
import dev.kobbler.app.ui.components.RefreshOnEvents
import dev.kobbler.app.ui.components.rememberCached
import dev.kobbler.app.ui.dashboard.HomeScreen
import dev.kobbler.app.ui.player.AudiobookPlayerScreen
import dev.kobbler.app.ui.player.GlobalPlayerState
import dev.kobbler.app.ui.player.PersistentVideoSurface
import dev.kobbler.app.ui.dashboard.BooksScreen
import dev.kobbler.app.ui.dashboard.MoviesScreen
import dev.kobbler.app.ui.dashboard.TvShowsScreen
import dev.kobbler.app.ui.detail.AuthorDetailScreen
import dev.kobbler.app.ui.detail.BookDetailScreen
import dev.kobbler.app.ui.detail.DiscoverDetailScreen
import dev.kobbler.app.ui.detail.SeriesDetailScreen
import dev.kobbler.app.ui.player.InlineMoviePlayer
import dev.kobbler.app.ui.player.InlineTvPlayer
import dev.kobbler.app.ui.reader.ComicReader
import dev.kobbler.app.ui.reader.ReaderScreen
import dev.kobbler.app.ui.player.PlaybackSessionHeartbeat
import dev.kobbler.app.ui.player.canRecoverSession
import dev.kobbler.app.ui.player.leaveFullscreen
import dev.kobbler.app.ui.player.retryPlayback
import dev.kobbler.app.ui.player.watchPlayback

// -- Navigation destinations --

sealed interface Destination {
    sealed class Tab(val title: String, val icon: ImageVector, val accentColor: Color) : Destination {
        data object Home : Tab("Home", Icons.Outlined.Home, KobappColorsDefault.accHome)
        data object Movies : Tab("Movies", Icons.Outlined.Movie, KobappColorsDefault.accMovies)
        data object TvShows : Tab("TV Shows", Icons.Outlined.Tv, KobappColorsDefault.accTv)
        data object Sports : Tab("Sports", Icons.Outlined.SportsScore, KobappColorsDefault.accSports)
        data object Books : Tab("Books", Icons.AutoMirrored.Outlined.MenuBook, KobappColorsDefault.accBooks)
        data object Audiobooks : Tab("Audiobooks", Icons.Outlined.Headphones, KobappColorsDefault.accAudio)
        data object LiveTv : Tab("Live TV", Icons.Outlined.Tv, KobappColorsDefault.accLive)
        data object Iptv : Tab("IPTV", Icons.Outlined.LiveTv, KobappColorsDefault.accIptv)
        data object Wanted : Tab("Wanted", Icons.Outlined.Bookmark, KobappColorsDefault.accCal)
        data object Downloads : Tab("Downloads", Icons.Outlined.Download, KobappColorsDefault.accMovies)
        data object Notifications : Tab("Notifications", Icons.Outlined.Notifications, KobappColorsDefault.accHome)
        data object Calendar : Tab("Calendar", Icons.Outlined.CalendarMonth, KobappColorsDefault.accCal)
        data object Settings : Tab("Settings", Icons.Outlined.Settings, KobappColorsDefault.accSet)
        data object Activity : Tab("Activity", Icons.Outlined.History, KobappColorsDefault.accNotif)
    }

    data object Profile : Destination

    data class MovieDetail(val movieId: String, val autoPlay: Boolean = false) : Destination
    data class TvShowDetail(val showId: String, val autoPlay: Boolean = false, val isEpisodeId: Boolean = false) : Destination
    data class BookDetail(val bookId: String) : Destination
    data class AuthorDetail(val authorId: String) : Destination
    data class SeriesDetail(val seriesId: String) : Destination
    data class AudiobookPlayer(val bookId: String) : Destination
    data class BookReader(val bookId: String) : Destination
    data class ComicViewer(val bookId: String) : Destination
    data class DiscoverItemDetail(val mediaType: String, val provider: String, val providerId: String) : Destination
    data class PersonDetail(val personId: String) : Destination
    data class IptvChannelDetail(val channelId: String) : Destination
    data class LiveChannelDetail(val channelId: String) : Destination
    data class LiveRecordingDetail(val recordingId: String) : Destination
    data class LiveSeriesRuleDetail(val ruleId: String?, val seedGuideId: String? = null) : Destination
    data object LiveOnNow : Destination
}

// -- Data-driven navigation config --

data class NavConfig(
    val bottomBarTabs: List<Destination.Tab>,
    val drawerTabs: List<Destination.Tab>,
    val sidebarTabs: List<Destination.Tab>,
)

val defaultNavConfig = NavConfig(
    // Phone bottom-bar: the 5 most-used tabs.
    bottomBarTabs = listOf(
        Destination.Tab.Home,
        Destination.Tab.Movies,
        Destination.Tab.TvShows,
        Destination.Tab.Audiobooks,
        Destination.Tab.Calendar,
    ),
    // Phone drawer: every tab, bottom-bar ones included — a menu that lists only the
    // leftovers reads as if the app lost half its sections. Settings is not a row here;
    // it hangs off the profile foot's cog.
    drawerTabs = listOf(
        Destination.Tab.Home,
        Destination.Tab.Movies,
        Destination.Tab.TvShows,
        Destination.Tab.Sports,
        Destination.Tab.LiveTv,
        Destination.Tab.Iptv,
        Destination.Tab.Audiobooks,
        Destination.Tab.Books,
        Destination.Tab.Wanted,
        Destination.Tab.Downloads,
        Destination.Tab.Notifications,
        Destination.Tab.Calendar,
        Destination.Tab.Activity,
    ),
    // Desktop/TV sidebar: full set (Settings is appended at the scaffold).
    // Downloads has no rail entry — it is the Wanted page's second tab.
    sidebarTabs = listOf(
        Destination.Tab.Home,
        Destination.Tab.Movies, Destination.Tab.TvShows,
        Destination.Tab.Sports, Destination.Tab.LiveTv, Destination.Tab.Iptv,
        Destination.Tab.Audiobooks, Destination.Tab.Books,
        Destination.Tab.Wanted, Destination.Tab.Notifications, Destination.Tab.Calendar,
        Destination.Tab.Activity,
    ),
)

// The server refuses live, IPTV, sports and activity routes for a Kids profile, so their tabs would only
// ever show an error.
private val kidsHiddenTabs =
    setOf(Destination.Tab.LiveTv, Destination.Tab.Iptv, Destination.Tab.Sports, Destination.Tab.Activity)

fun navConfigFor(isKids: Boolean): NavConfig =
    if (!isKids) defaultNavConfig
    else defaultNavConfig.copy(
        bottomBarTabs = defaultNavConfig.bottomBarTabs - kidsHiddenTabs,
        drawerTabs = defaultNavConfig.drawerTabs - kidsHiddenTabs,
        sidebarTabs = defaultNavConfig.sidebarTabs - kidsHiddenTabs,
    )

// -- Navigation state --

class NavigationState {
    private val _stack = mutableStateListOf<Destination>(Destination.Tab.Home)
    val current: Destination get() = _stack.last()
    val currentTab: Destination.Tab get() = _stack.filterIsInstance<Destination.Tab>().lastOrNull() ?: Destination.Tab.Home

    // Search is a page but not a Destination, so the stack alone would land BACK from a
    // result on whatever tab was underneath. The depth of the page it opened is kept instead.
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    private var searchResultDepth = -1

    fun navigate(dest: Destination) {
        if (dest is Destination.Tab) {
            _stack.clear()
            _stack.add(dest)
            searchResultDepth = -1
        } else {
            _stack.add(dest)
        }
    }

    fun navigateFromSearch(dest: Destination) {
        searchOpen = false
        navigate(dest)
        searchResultDepth = _stack.size
    }

    fun goBack(): Boolean {
        if (_stack.size <= 1) return false
        if (_stack.size == searchResultDepth) {
            searchResultDepth = -1
            searchOpen = true
        }
        _stack.removeAt(_stack.lastIndex)
        return true
    }

    fun backFromRail(): Boolean {
        if (current == Destination.Tab.Home) return false
        navigate(Destination.Tab.Home)
        return true
    }
}

// -- Adaptive scaffold --

@Composable
fun AdaptiveScaffold(auth: AuthState, formFactor: FormFactor, onToggleFullscreen: (() -> Unit)? = null, isFullscreen: Boolean = false) {
    val nav = remember { NavigationState() }
    val navConfig = remember(auth.isKids) { navConfigFor(auth.isKids) }
    val canWant = auth.canWant
    val isAdmin = auth.role == "admin"
    val api = remember(auth.apiUrl, auth.token, canWant, isAdmin) {
        val url = auth.apiUrl ?: return@remember null
        val tok = auth.token ?: return@remember null
        KobblerApi(auth.httpClient, url, tok, canWant = canWant, canManageKids = isAdmin)
    }

    // Global player — one instance for the entire app
    val player = if (dev.kobbler.app.platform.supportsVideoPlayback) dev.kobbler.kobplayer.rememberPlayerAsync() else null
    val gps = remember { GlobalPlayerState() }
    val scope = rememberCoroutineScope()
    if (api != null) dev.kobbler.app.ui.player.LiveSessionHeartbeat(gps, api, player)
    // One sender for the app: a cast session outlives the screen that started it, and
    // picking one up again on launch is the same handover as starting it.
    val cast = remember { dev.kobbler.app.platform.castManager() }
    if (api != null) dev.kobbler.app.ui.player.CastHandoff(cast, gps, api, player)

    val isNonVideoPlayerScreen = nav.current is Destination.AudiobookPlayer || nav.current is Destination.BookReader || nav.current is Destination.ComicViewer

    // A stream that never starts and a libVLC failure both used to reach nothing, so an
    // unopenable one showed as a spinner that never resolved. One collector for the app.
    if (player != null && api != null) {
        LaunchedEffect(player, api) { gps.watchPlayback(player, api) }
        PlaybackSessionHeartbeat(gps, player, api)
    }

    // Report it without being asked: by the time somebody thinks to press a
    // button the useful context is a screenshot at best.
    if (api != null) {
        LaunchedEffect(gps.loadingError) {
            gps.loadingError?.let { gps.submitReport(api, it) }
        }
    }

    // Sync Activity fullscreen state (orientation, system bars) with gps.isFullscreen
    LaunchedEffect(gps.isFullscreen) {
        if (onToggleFullscreen != null) {
            if (gps.isFullscreen && !isFullscreen) onToggleFullscreen()
            else if (!gps.isFullscreen && isFullscreen) onToggleFullscreen()
        }
    }

    // Back button: fullscreen video → mini player (if playing) or dismiss (if paused).
    // Not on TV: the mini player's controls are tap-only and it carries no focus target,
    // so shrinking into it strands playback somewhere the remote can never reach again.
    BackHandler(enabled = gps.isFullscreen) {
        if (player != null) gps.leaveFullscreen(player, api!!, formFactor, scope)
    }
    // Only plain Home leaves the app; any other tab steps back to it first.
    BackHandler(enabled = nav.current != Destination.Tab.Home && !gps.isFullscreen) {
        nav.goBack() || nav.backFromRail()
    }

    if (api == null) return

    // Check for app updates via kobup on-device app
    var availableUpdate by remember { mutableStateOf<dev.kobbler.app.data.AppUpdate?>(null) }
    LaunchedEffect(Unit) {
        availableUpdate = dev.kobbler.app.data.KobupUpdater.checkForUpdate()
    }

    Column(Modifier.fillMaxSize()) {
        dev.kobbler.app.ui.components.UpdateBanner(update = availableUpdate)

        Box(Modifier.fillMaxSize().weight(1f)) {
        // Reader-type destinations (audiobook, book, comic) take the whole screen
        // instead of overlaying the scaffold. Stacking the scaffold underneath
        // leaks taps and TV-remote focus into hidden screens; replacing it avoids
        // both. The `player` instance is hoisted above this branch so audio
        // continues uninterrupted across the swap.
        // Everything the scaffold is covered by, in paint order. A covered layer stays
        // composed (the player must keep running) so it has to leave focus traversal
        // explicitly, or the D-pad walks into controls nobody can see.
        val scaffoldCovered = gps.isFullscreen || gps.loadingError != null
        if (isNonVideoPlayerScreen) {
            FocusScopeHost(id = nav.current) {
                PlayerContent(nav, api, formFactor, onToggleFullscreen, isFullscreen)
            }
        } else {
            Box(Modifier.focusInert(scaffoldCovered)) {
                when (formFactor) {
                    FormFactor.Phone -> MobileScaffold(nav, navConfig, api, formFactor, player, gps, serverUrl = auth.apiUrl ?: "", onLogout = { auth.logout() })
                    FormFactor.TV, FormFactor.Desktop -> DesktopScaffold(nav, navConfig, api, formFactor, player, gps, onToggleFullscreen, isFullscreen, serverUrl = auth.apiUrl ?: "", onLogout = { auth.logout() })
                }
            }
        }

        // Single persistent video surface — stays in the tree, resized in place.
        // No movableContentOf, no re-parenting, no SurfaceView Z-order issues.
        // miniBottomInset reserves room above the phone bottom-nav so the mini
        // player snaps above it (and not over it).
        if (player != null && gps.isActive) {
            val bottomNavHeight = KobPhoneBottomBarHeight +
                kobChromeInsets.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
            val miniBottomInset = if (
                formFactor == FormFactor.Phone &&
                nav.current is Destination.Tab &&
                !gps.isFullscreen
            ) bottomNavHeight else 0.dp
            // Only the fullscreen player owns focus; the mini player is a passenger on
            // whatever screen is behind it and must not steal entry focus from it.
            FocusScopeHost(id = "video-surface", restoreEnabled = gps.isFullscreen) {
                Box(Modifier.focusInert(gps.loadingError != null)) {
                    PersistentVideoSurface(
                        player = player,
                        state = gps,
                        api = api,
                        formFactor = formFactor,
                        onToggleFullscreen = onToggleFullscreen,
                        isFullscreen = isFullscreen,
                        scope = scope,
                        miniBottomInset = miniBottomInset,
                        onOpenGuide = {
                            gps.stop(player, api, scope)
                            nav.navigate(Destination.Tab.LiveTv)
                        },
                    )
                }
            }
        }

        // Screenshot-friendly playback error dialog. Lives at scaffold level so
        // it shows even when no player surface is active (e.g. session creation
        // failed before the fullscreen player opened).
        val playbackError = gps.loadingError
        if (playbackError != null) {
            FocusScopeHost(id = "playback-error") {
                dev.kobbler.app.ui.player.PlayerErrorDialog(
                    message = playbackError,
                    onDismiss = {
                        gps.loadingError = null
                        if (player != null && gps.isActive) gps.stop(player, api, scope)
                    },
                    // A debrid stream has no session to mint again — its only way forward
                    // is a different source, so the same button opens the picker.
                    onRetry = if (player != null && api != null && gps.canRecoverSession) {
                        { scope.launch { gps.retryPlayback(player, api) } }
                    } else gps.onSwitchStream?.let { switchStream ->
                        {
                            gps.loadingError = null
                            switchStream()
                        }
                    },
                    reportStatus = gps.reportStatus,
                    onRetryReport = api?.let { client ->
                        { scope.launch { gps.submitReport(client, playbackError) } }
                    },
                )
            }
        }
    } // Box
    } // Column
}

// -- Section ↔ Tab mapping (Section enum is the new design system primitive;
// Destination.Tab is the navigation primitive). Both lists are static. --

internal val Destination.Tab.section: Section get() = when (this) {
    Destination.Tab.Home -> Section.Home
    Destination.Tab.Movies -> Section.Movies
    Destination.Tab.TvShows -> Section.Tv
    Destination.Tab.Sports -> Section.Sports
    Destination.Tab.Books -> Section.Books
    Destination.Tab.Audiobooks -> Section.Audio
    Destination.Tab.LiveTv -> Section.LiveTv
    Destination.Tab.Iptv -> Section.Iptv
    Destination.Tab.Wanted -> Section.Wanted
    Destination.Tab.Downloads -> Section.Downloads
    Destination.Tab.Notifications -> Section.Notifications
    Destination.Tab.Calendar -> Section.Calendar
    Destination.Tab.Settings -> Section.Settings
    Destination.Tab.Activity -> Section.Activity
}

internal fun tabForSection(section: Section): Destination.Tab = when (section) {
    Section.Home -> Destination.Tab.Home
    Section.Movies -> Destination.Tab.Movies
    Section.Tv -> Destination.Tab.TvShows
    Section.Sports -> Destination.Tab.Sports
    Section.Books -> Destination.Tab.Books
    Section.Audio -> Destination.Tab.Audiobooks
    Section.LiveTv -> Destination.Tab.LiveTv
    Section.Iptv -> Destination.Tab.Iptv
    Section.Wanted -> Destination.Tab.Wanted
    Section.Downloads -> Destination.Tab.Downloads
    Section.Notifications -> Destination.Tab.Notifications
    Section.Calendar -> Destination.Tab.Calendar
    Section.Settings -> Destination.Tab.Settings
    Section.Activity -> Destination.Tab.Activity
}

/**
 * Which rail row reads as selected.
 *
 * On the rail this is the row the D-pad is on, not the tab that is loaded: navigation is
 * debounced by [SidebarNavDebounceMs], and deriving the highlight from the committed tab
 * put that whole delay between pressing down and the row lighting up — the selection
 * looked like it arrived late rather than the screen behind it. Off the rail there is no
 * pending selection, so the committed tab is the answer.
 */
internal fun railSelection(
    onRail: Boolean,
    sidebarIdx: Int,
    tabs: List<Destination.Tab>,
    currentTab: Destination.Tab,
): Section = if (onRail) {
    tabs.getOrNull(sidebarIdx)?.section ?: currentTab.section
} else {
    currentTab.section
}

internal fun sidebarItemsFor(tabs: List<Destination.Tab>): List<SidebarItem> =
    tabs.map { tab -> SidebarItem(section = tab.section, label = tab.title, icon = tab.icon) }

// -- Drawer content (mobile) — re-uses KobSidebar in expanded form. --

@Composable
private fun DrawerContent(
    navConfig: NavConfig,
    selectedTab: Destination.Tab,
    onNavigate: (Destination.Tab) -> Unit,
) {
    val drawerItems = remember(navConfig) { sidebarItemsFor(navConfig.drawerTabs) }
    val drawerProfile = LocalAuthState.current.currentProfile
    val openSettings = { onNavigate(Destination.Tab.Settings) }
    KobSidebar(
        items = drawerItems,
        active = selectedTab.section,
        expanded = true,
        tvMode = false,
        // Start only: the sheet's other three edges are mid-screen, not screen edges.
        windowInsets = kobChromeInsets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
        profile = drawerProfile,
        onSelect = { section -> onNavigate(tabForSection(section)) },
        onProfileClick = openSettings,
        onSettingsClick = openSettings,
    )
}

// -- Mobile scaffold --

// Poster width a phone aims for before the row is split evenly; picking the column
// count by rounding keeps every phone in the 100-125dp band instead of letting one
// 360dp screen fall to a single 140dp column.
private val PhoneIdealPoster = 118.dp

internal fun phoneDimens(base: KobappDimens, contentWidth: Dp): KobappDimens {
    if (contentWidth <= 0.dp) return base
    val available = contentWidth - base.pageGutterPhone * 2
    val columns = (available / PhoneIdealPoster).roundToInt().coerceIn(2, 6)
    // GridCells.FixedSize floors the fit, so a card that exactly fills the row can round
    // down a column; the 1dp shave keeps the row strictly inside its measured width.
    val poster = ((available - base.s3 * (columns - 1)) / columns - 1.dp).coerceIn(88.dp, 200.dp)
    return base.copy(posterWidth = poster, wideCardWidth = poster * 1.8f)
}

@Composable
private fun MobileScaffold(nav: NavigationState, navConfig: NavConfig, api: KobblerApi, formFactor: FormFactor, player: dev.kobbler.kobplayer.Player?, gps: GlobalPlayerState, serverUrl: String = "", onLogout: () -> Unit = {}) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val isTabScreen = nav.current is Destination.Tab
    var showSearch by nav::searchOpen
    val currentSection = nav.currentTab.section
    val bottomBarItems = remember(navConfig) { sidebarItemsFor(navConfig.bottomBarTabs) }

    if (showSearch) {
        dev.kobbler.app.ui.dashboard.SearchOverlay(
            api = api,
            currentTab = nav.currentTab,
            query = nav.searchQuery,
            onQueryChange = { nav.searchQuery = it },
            onDismiss = { showSearch = false },
            onResultClick = { result -> navigateToSearchResult(nav, result) },
            onDiscoverItemClick = { item -> nav.navigateFromSearch(discoverItemDestination(item)) },
            formFactor = formFactor,
        )
        return
    }

    CompositionLocalProvider(LocalSection provides currentSection) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = isTabScreen,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Color.Transparent,
                    drawerShape = RoundedCornerShape(0.dp),
                    drawerTonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    DrawerContent(navConfig, nav.currentTab) { tab ->
                        nav.navigate(tab)
                        scope.launch { drawerState.close() }
                    }
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                // Consumed once here so every screen below — top bar included — clears a
                // long-edge cutout in landscape without each of them knowing about it.
                val density = androidx.compose.ui.platform.LocalDensity.current
                var contentWidthDp by remember { mutableStateOf(0.dp) }
                val adaptiveSpacing = remember(contentWidthDp) { phoneDimens(KobappDimensDefault, contentWidthDp) }
                Box(
                    Modifier
                        .weight(1f)
                        .windowInsetsPadding(kobChromeInsets.only(WindowInsetsSides.Horizontal))
                        .onSizeChanged { size -> contentWidthDp = with(density) { size.width.toDp() } },
                ) {
                    CompositionLocalProvider(LocalKobappDimens provides adaptiveSpacing) {
                        ScreenContent(
                            nav, api, formFactor, player, gps,
                            onMenuClick = { scope.launch { drawerState.open() } },
                            onSearchClick = { showSearch = true },
                            serverUrl = serverUrl, onLogout = onLogout,
                        )
                    }
                }

                if (isTabScreen) {
                    KobPhoneBottomBar(
                        items = bottomBarItems,
                        active = currentSection,
                        onSelect = { section -> nav.navigate(tabForSection(section)) },
                    )
                }
            }
        }
    }
}

// -- Desktop/TV scaffold --

private enum class Zone { Sidebar, Content }

private const val SIDEBAR_SEARCH_IDX = -1
private const val SidebarNavDebounceMs = 300L

@Composable
internal fun DesktopScaffold(
    nav: NavigationState,
    navConfig: NavConfig,
    api: KobblerApi,
    formFactor: FormFactor,
    player: dev.kobbler.kobplayer.Player?,
    gps: GlobalPlayerState,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    serverUrl: String = "",
    onLogout: () -> Unit = {},
) {
    var zone by remember { mutableStateOf(Zone.Content) }
    val backInterceptors = LocalBackInterceptors.current
    var sidebarIdx by remember { mutableStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var showSearch by nav::searchOpen

    val current = nav.current
    val isDetailScreen = remember(current) {
        current is Destination.MovieDetail || current is Destination.TvShowDetail || current is Destination.BookDetail ||
            current is Destination.AuthorDetail || current is Destination.SeriesDetail || current is Destination.DiscoverItemDetail ||
            current is Destination.PersonDetail || current is Destination.IptvChannelDetail || current is Destination.Profile ||
            current is Destination.LiveChannelDetail || current is Destination.LiveRecordingDetail ||
            current is Destination.LiveSeriesRuleDetail || current is Destination.LiveOnNow
    }
    val allSidebarTabs = remember(navConfig) { navConfig.sidebarTabs + Destination.Tab.Settings }
    val tvMode = formFactor == FormFactor.TV
    // The TV sidebar pins a Search row above the tabs. It is not a Destination.Tab, so
    // it sits at index -1 rather than in allSidebarTabs — every sidebarIdx → tab lookup
    // below stays a plain index into that list.
    val minSidebarIdx = if (tvMode) SIDEBAR_SEARCH_IDX else 0

    // On TV search is a page in the content pane rather than a modal over the whole app:
    // the rail stays visible, so what its Search row says is selected and what is on
    // screen cannot drift apart. Every other form factor opens it over the app as before.
    val searchInline = tvMode
    val closeSearch = {
        showSearch = false
        sidebarIdx = allSidebarTabs.indexOf(nav.currentTab).coerceAtLeast(0)
    }
    val openSearch = {
        showSearch = true
        if (tvMode) {
            sidebarIdx = SIDEBAR_SEARCH_IDX
            // The page holds its own focus like any other content; leaving the machine in
            // Sidebar would swallow every key before the search field saw it.
            zone = Zone.Content
        }
    }
    val searchUi: @Composable () -> Unit = {
        dev.kobbler.app.ui.dashboard.SearchOverlay(
            api = api,
            currentTab = nav.currentTab,
            query = nav.searchQuery,
            onQueryChange = { nav.searchQuery = it },
            onDismiss = closeSearch,
            onResultClick = { result ->
                navigateToSearchResult(nav, result)
                zone = Zone.Content
            },
            onDiscoverItemClick = { item ->
                nav.navigateFromSearch(discoverItemDestination(item))
                zone = Zone.Content
            },
            formFactor = formFactor,
            autoFocusQuery = !searchInline || zone == Zone.Content,
        )
    }
    if (showSearch && !searchInline) {
        searchUi()
        return
    }

    // One implementation for both delivery paths. Android sends BACK either as a key
    // event or through OnBackPressedDispatcher depending on the device's predictive-back
    // config, and the key path only arrives when something in Compose holds focus. A
    // screen that failed to place entry focus therefore reached neither handler and the
    // press fell through to the Activity, which finished — the Live TV tab did exactly
    // that whenever its guide had no focusable row.
    val handleBack: () -> Boolean = handleBack@{
        when {
            backInterceptors.consume() -> true
            showSearch -> {
                closeSearch()
                zone = if (tvMode) Zone.Sidebar else Zone.Content
                true
            }
            zone == Zone.Sidebar -> {
                if (nav.backFromRail()) {
                    sidebarIdx = allSidebarTabs.indexOf(Destination.Tab.Home).coerceAtLeast(0)
                    zone = Zone.Content
                    true
                } else {
                    false
                }
            }
            isDetailScreen -> {
                nav.goBack()
                if (nav.searchOpen) openSearch()
                true
            }
            else -> {
                sidebarIdx = allSidebarTabs.indexOf(nav.currentTab).coerceAtLeast(0)
                zone = Zone.Sidebar
                true
            }
        }
    }

    // Disabled exactly where [handleBack] would decline, so the last press still leaves
    // the app rather than trapping the viewer at the rail.
    BackHandler(
        enabled = !gps.isFullscreen &&
            !(zone == Zone.Sidebar && nav.current == Destination.Tab.Home && !showSearch),
    ) { handleBack() }

    // Navigating a Tab clears the stack, so every rail step tears the current screen down
    // and refetches the next one from scratch. Holding the D-pad across the rail did that
    // once per row; settle first, then navigate. The highlight itself stays immediate.
    val commitSidebarIdx = {
        val tab = allSidebarTabs.getOrNull(sidebarIdx)
        if (tab != null && tab != nav.currentTab) nav.navigate(tab)
    }
    // The `tab != currentTab` guard is what keeps this from re-navigating on first
    // composition and on the Zone.Content → Zone.Sidebar handoffs, which assign
    // sidebarIdx purely to sync the highlight to where the viewer already is.
    LaunchedEffect(sidebarIdx) {
        delay(SidebarNavDebounceMs)
        commitSidebarIdx()
    }

    val tabTopBar: (@Composable (Boolean) -> Unit)? = if (tvMode) null else { transparent ->
        KobTopBar(
            notificationCount = 0,
            dense = true,
            showCast = true,
            transparent = transparent,
            isFullscreen = isFullscreen,
            onSearchClick = openSearch,
            onToggleFullscreen = onToggleFullscreen?.let { toggle ->
                {
                    toggle()
                    rootFocus.requestFocus()
                }
            },
        )
    }

    val sidebarItems = remember(navConfig) { sidebarItemsFor(allSidebarTabs) }
    val sidebarProfile = LocalAuthState.current.currentProfile
    val currentSection = nav.currentTab.section
    val selectedSection = railSelection(zone == Zone.Sidebar, sidebarIdx, allSidebarTabs, nav.currentTab)

    CompositionLocalProvider(LocalSection provides currentSection) {
        // The rail slides the content sideways rather than sharing a Row with it.
        // Sharing a Row re-measured the content to ~85% width on expand, which squashed
        // the section selectors into tall thin columns. Translating keeps the layout
        // identical and pushes the right edge off-screen instead — the left of a row
        // (index, section title) is the part worth keeping visible.
        val railWidth = if (tvMode) KobappTheme.dimens.sidebarTv else KobappTheme.dimens.sidebarCollapsed
        val railPx = with(androidx.compose.ui.platform.LocalDensity.current) { railWidth.roundToPx() }
        // Measured rather than re-animated, so the shift tracks KobSidebar's own
        // expand animation exactly instead of a second copy of it drifting.
        // Read only from the offset lambda below: the size callback fires on every frame
        // of KobSidebar's width tween, and a composition-level read of it recomposed the
        // whole scaffold ~15 times per open.
        val sidebarPx = remember { mutableIntStateOf(railPx) }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(rootFocus)
                    .focusable()
                    .backInput(handleBack)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (event.key == Key.Search) {
                            openSearch()
                            return@onPreviewKeyEvent true
                        }
                        when (zone) {
                            Zone.Sidebar -> when (event.key) {
                                // Stepping onto a row shows it, Search included — the rail
                                // is a live selector, not a menu you commit to.
                                Key.DirectionUp -> {
                                    if (sidebarIdx > minSidebarIdx) {
                                        sidebarIdx--
                                        showSearch = sidebarIdx < 0
                                    }
                                    true
                                }
                                Key.DirectionDown -> {
                                    if (sidebarIdx < allSidebarTabs.size - 1) {
                                        sidebarIdx++
                                        showSearch = false
                                    }
                                    true
                                }
                                Key.DirectionRight -> {
                                    commitSidebarIdx()
                                    zone = Zone.Content
                                    true
                                }
                                // Right steps back into whatever page is open; selecting a row
                                // goes to that tab's root, even the tab the page was opened from.
                                Key.Enter, Key.DirectionCenter -> {
                                    allSidebarTabs.getOrNull(sidebarIdx)?.let { if (it != nav.current) nav.navigate(it) }
                                    zone = Zone.Content
                                    true
                                }
                                else -> false
                            }
                            Zone.Content -> {
                                val dir = when (event.key) {
                                    Key.DirectionLeft -> FocusDirection.Left
                                    Key.DirectionRight -> FocusDirection.Right
                                    Key.DirectionUp -> FocusDirection.Up
                                    Key.DirectionDown -> FocusDirection.Down
                                    else -> null
                                }
                                when {
                                    dir != null -> {
                                        val moved = focusManager.moveFocus(dir)
                                        when {
                                            moved -> true
                                            event.key == Key.DirectionLeft -> {
                                                sidebarIdx = allSidebarTabs.indexOf(nav.currentTab).coerceAtLeast(0)
                                                zone = Zone.Sidebar
                                                rootFocus.requestFocus()
                                                true
                                            }
                                            // Preview runs root-first, so consuming a key we
                                            // couldn't act on stopped the focused element from
                                            // ever handling it — which is how a pinned top bar
                                            // became a dead end.
                                            else -> false
                                        }
                                    }
                                    else -> false
                                }
                            }
                        }
                    },
            ) {
                // Sidebar: KobSidebar handles its own collapsed/expanded animation.
                // Block Compose focus so Tab key / moveFocus skips it — sidebar
                // navigation is driven entirely by the Zone state machine above.
                // canFocus=false alone only deactivates this Box; its children stayed
                // reachable, which is how D-pad on a detail screen ended up walking
                // down the sidebar instead of the content.
                Box(
                    Modifier
                        .zIndex(1f)
                        .onSizeChanged { sidebarPx.intValue = it.width }
                        .focusInert(true),
                ) {
                    KobSidebar(
                        items = sidebarItems,
                        active = selectedSection,
                        expanded = zone == Zone.Sidebar,
                        tvMode = tvMode,
                        profile = sidebarProfile,
                        searchSelected = showSearch || (zone == Zone.Sidebar && sidebarIdx == SIDEBAR_SEARCH_IDX),
                        onSelect = { section ->
                            val tab = tabForSection(section)
                            showSearch = false
                            sidebarIdx = allSidebarTabs.indexOf(tab).coerceAtLeast(0)
                            nav.navigate(tab)
                            zone = Zone.Content
                        },
                        onSearchClick = if (tvMode) openSearch else null,
                        // The rail keeps its own Settings row (it is a D-pad target), so the
                        // foot only needs the tap — no second cog beside the avatar.
                        onProfileClick = {
                            showSearch = false
                            sidebarIdx = allSidebarTabs.indexOf(Destination.Tab.Settings).coerceAtLeast(0)
                            nav.navigate(Destination.Tab.Settings)
                            zone = Zone.Content
                        },
                    )
                }

                // Adaptive card sizing: measure content width, compute card sizes.
                // No BoxWithConstraints (subcomposition breaks focus traversal).
                val baseSpacing = KobappDimensDefault
                val density = androidx.compose.ui.platform.LocalDensity.current
                var contentWidthDp by remember { mutableStateOf(0.dp) }
                val adaptiveSpacing = remember(contentWidthDp, baseSpacing, formFactor) {
                    if (contentWidthDp <= 0.dp) baseSpacing
                    else {
                        val target = if (formFactor == FormFactor.TV) 7f else 5.5f
                        val cardW = ((contentWidthDp - baseSpacing.s4 * 2) / target - baseSpacing.s2)
                            .coerceIn(100.dp, 220.dp)
                        baseSpacing.copy(posterWidth = cardW, wideCardWidth = (cardW * 1.8f).coerceIn(180.dp, 400.dp))
                    }
                }

                CompositionLocalProvider(LocalKobappDimens provides adaptiveSpacing) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(start = railWidth)
                            .offset { IntOffset((sidebarPx.intValue - railPx).coerceAtLeast(0), 0) }
                            .onSizeChanged { size -> contentWidthDp = with(density) { size.width.toDp() } },
                    ) {
                        // Home draws the bar itself, over its hero.
                        if (!isDetailScreen && tabTopBar != null && current != Destination.Tab.Home) {
                            tabTopBar(false)
                        }
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            // Content keeps its focus while the rail is open — screens
                            // claim entry focus whenever their data lands — but it must
                            // not advertise it behind a menu the viewer is still in.
                            CompositionLocalProvider(LocalTvFocusDecor provides (zone == Zone.Content)) {
                                if (showSearch) {
                                    searchUi()
                                } else {
                                    ScreenContent(
                                        nav, api, formFactor, player, gps,
                                        contentActive = zone == Zone.Content,
                                        onSearchClick = openSearch,
                                        homeTopBar = tabTopBar,
                                        serverUrl = serverUrl,
                                        onLogout = onLogout,
                                    )
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    // Root only takes focus for the sidebar, which the Zone machine drives directly.
    // Content screens hold their own focus — re-grabbing it here is what used to leave
    // them with nothing focused. Key events still reach the machine either way:
    // onPreviewKeyEvent on the root sees a focused descendant's keys before it does.
    LaunchedEffect(nav.current, zone, showSearch) {
        if (zone == Zone.Sidebar) rootFocus.requestFocus()
    }
}

// -- Search result navigation --

private fun discoverItemDestination(item: dev.kobbler.app.data.DiscoverItem): Destination =
    item.libraryId?.let { libraryDestination(item.mediaType, it) }
        ?: Destination.DiscoverItemDetail(item.mediaType, item.provider, item.providerId)

private fun libraryDestination(mediaType: String, libraryId: String): Destination? = when (mediaType) {
    "movie" -> Destination.MovieDetail(libraryId)
    "tv" -> Destination.TvShowDetail(libraryId)
    "book" -> Destination.BookDetail(libraryId)
    else -> null
}

private fun navigateContinueItem(
    nav: NavigationState,
    gps: GlobalPlayerState,
    item: dev.kobbler.app.data.ContinueItem,
) {
    val link = item.link ?: return
    // Raise the player before the detail page it plays from. The detail screen still
    // resolves the episode and creates the session, but that work now happens behind a
    // player that is already up rather than in front of one that arrives late.
    val isVideo = link.startsWith("/movies/") || link.startsWith("/tv/") || link.startsWith("/sports/")
    if (isVideo) {
        gps.beginPreparing(
            id = link,
            newTitle = item.title,
            newSubtitle = item.subtitle,
            newPosterUrl = item.imageUrl,
        )
    }
    when {
        link.startsWith("/movies/") -> nav.navigate(Destination.MovieDetail(link.removePrefix("/movies/"), autoPlay = true))
        link.startsWith("/tv/episodes/") -> {
            nav.navigate(Destination.TvShowDetail(link.removePrefix("/tv/episodes/"), autoPlay = true, isEpisodeId = true))
        }
        link.startsWith("/tv/") -> nav.navigate(Destination.TvShowDetail(link.removePrefix("/tv/"), autoPlay = true))
        link.startsWith("/sports/events/") -> {
            nav.navigate(Destination.TvShowDetail(link.removePrefix("/sports/events/"), autoPlay = true, isEpisodeId = true))
        }
        link.startsWith("/sports/") -> nav.navigate(Destination.TvShowDetail(link.removePrefix("/sports/"), autoPlay = true))
        link.startsWith("/library/books/") -> {
            val bookId = link.removePrefix("/library/books/")
            when (item.mediaType) {
                dev.kobbler.app.data.MediaType.Audiobook -> nav.navigate(Destination.AudiobookPlayer(bookId))
                else -> nav.navigate(Destination.BookReader(bookId))
            }
        }
    }
}

private fun navigateToSearchResult(nav: NavigationState, result: dev.kobbler.app.data.SearchResult) {
    when (result.type) {
        "movie" -> nav.navigateFromSearch(Destination.MovieDetail(result.id))
        "tv_show", "tv_season" -> nav.navigateFromSearch(Destination.TvShowDetail(result.id))
        "tv_episode" -> nav.navigateFromSearch(Destination.TvShowDetail(result.id, isEpisodeId = true))
        "book" -> nav.navigateFromSearch(Destination.BookDetail(result.id))
        "author" -> nav.navigateFromSearch(Destination.AuthorDetail(result.id))
        "series" -> nav.navigateFromSearch(Destination.SeriesDetail(result.id))
        "person" -> nav.navigateFromSearch(Destination.PersonDetail(result.id))
        else -> {}
    }
}

// -- Screen content router --
//
// Tab screens stay composed while detail screens overlay on top, preserving
// scroll positions when navigating to a detail and back.

@Composable
private fun ScreenContent(
    nav: NavigationState,
    api: KobblerApi,
    formFactor: FormFactor,
    player: dev.kobbler.kobplayer.Player?,
    gps: GlobalPlayerState,
    modifier: Modifier = Modifier,
    contentActive: Boolean = true,
    onMenuClick: (() -> Unit)? = null,
    onSearchClick: (() -> Unit)? = null,
    serverUrl: String = "",
    onLogout: () -> Unit = {},
    homeTopBar: (@Composable (transparent: Boolean) -> Unit)? = null,
) {
    val isOnDetail = nav.current !is Destination.Tab
    // The player paints over the whole scaffold and takes it out of focus traversal
    // while it is up. Nothing hands focus back on the way out, so the page the viewer
    // returns to has none — track it as a restore trigger instead.
    val covered = gps.isFullscreen || gps.loadingError != null
    // State holder lives here so it survives the tab↔detail toggle
    val stateHolder = rememberSaveableStateHolder()

    Box(Modifier.fillMaxSize()) {
        // Only the visible layer is composed. Keeping the tab alive under a detail screen
        // left every card behind it a live D-pad target, and neither containment option
        // works there: canFocus=false doesn't cover descendants, while cancelling entry
        // aborts the whole focus search (the covered tab is a candidate in most
        // directions, so moveFocus just returned false and the D-pad went dead).
        // SaveableStateProvider is what preserves scroll/selection across the visit.
        if (!isOnDetail) {
            stateHolder.SaveableStateProvider(nav.currentTab.title) {
                FocusScopeHost(id = nav.currentTab.title, restoreEnabled = contentActive && !covered) {
                    SingleTabContent(nav.currentTab, nav, api, player, gps, formFactor, modifier, onMenuClick, onSearchClick, serverUrl, onLogout, homeTopBar, isActive = true)
                }
            }
        }
        if (isOnDetail) {
            Box(Modifier.fillMaxSize()) {
                FocusScopeHost(id = nav.current, restoreEnabled = contentActive && !covered) {
                    DetailContent(nav, api, formFactor, player, gps, modifier)
                }
            }
        }
    }
}

@Composable
private fun SingleTabContent(
    tab: Destination.Tab,
    nav: NavigationState,
    api: KobblerApi,
    player: dev.kobbler.kobplayer.Player?,
    gps: GlobalPlayerState,
    formFactor: FormFactor,
    modifier: Modifier,
    onMenuClick: (() -> Unit)?,
    onSearchClick: (() -> Unit)?,
    serverUrl: String,
    onLogout: () -> Unit,
    homeTopBar: (@Composable (transparent: Boolean) -> Unit)?,
    isActive: Boolean = true,
) {
    when (tab) {
        is Destination.Tab.Home -> TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
            HomeScreen(
                api = api,
                formFactor = formFactor,
                modifier = modifier,
                isActive = isActive,
                onDiscoverItemClick = { item -> nav.navigate(discoverItemDestination(item)) },
                onRecentClick = { item ->
                    val link = item.link
                    when {
                        link?.startsWith("/movies/") == true -> nav.navigate(Destination.MovieDetail(link.removePrefix("/movies/")))
                        link?.startsWith("/tv/") == true -> nav.navigate(Destination.TvShowDetail(link.removePrefix("/tv/")))
                        link?.startsWith("/sports/") == true -> nav.navigate(Destination.TvShowDetail(link.removePrefix("/sports/")))
                        link?.startsWith("/library/books/") == true -> nav.navigate(Destination.BookDetail(link.removePrefix("/library/books/")))
                        item.itemType == "movie" -> nav.navigate(Destination.MovieDetail(item.id))
                        item.itemType == "episode" || item.itemType == "event" -> nav.navigate(Destination.TvShowDetail(item.id))
                        item.itemType == "book" -> nav.navigate(Destination.BookDetail(item.id))
                    }
                },
                onContinueClick = { item -> navigateContinueItem(nav, gps, item) },
                topBar = homeTopBar,
            )
        }
        is Destination.Tab.Movies -> {
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                MoviesScreen(
                    api = api,
                    modifier = modifier,
                    formFactor = formFactor,
                    onMovieClick = { movie -> nav.navigate(Destination.MovieDetail(movie.id)) },
                    onContinueClick = { item -> navigateContinueItem(nav, gps, item) },
                )
            }
        }
        is Destination.Tab.TvShows, is Destination.Tab.Sports -> {
            val isSports = tab is Destination.Tab.Sports
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                TvShowsScreen(
                    api = api,
                    sportsOnly = isSports,
                    modifier = modifier,
                    formFactor = formFactor,
                    onShowClick = { show -> nav.navigate(Destination.TvShowDetail(show.id)) },
                    onContinueClick = { item -> navigateContinueItem(nav, gps, item) },
                )
            }
        }
        is Destination.Tab.Books, is Destination.Tab.Audiobooks -> {
            val showAudiobooks = tab is Destination.Tab.Audiobooks
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                BooksScreen(
                    api = api,
                    formFactor = formFactor,
                    audiobooksOnly = showAudiobooks,
                    modifier = modifier,
                    onBookClick = { book -> nav.navigate(Destination.BookDetail(book.id)) },
                    onContinueClick = { item -> navigateContinueItem(nav, gps, item) },
                    onAuthorClick = { authorId -> nav.navigate(Destination.AuthorDetail(authorId)) },
                    onSeriesClick = { seriesId -> nav.navigate(Destination.SeriesDetail(seriesId)) },
                )
            }
        }
        // Discover tab removed — merged into Home
        is Destination.Tab.Iptv -> {
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                dev.kobbler.app.ui.dashboard.IptvScreen(
                    api = api,
                    modifier = modifier,
                    formFactor = formFactor,
                    onChannelClick = { channel -> nav.navigate(Destination.IptvChannelDetail(channel.id)) },
                )
            }
        }
        is Destination.Tab.LiveTv -> {
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                dev.kobbler.app.ui.dashboard.LiveTvScreen(
                    api = api,
                    modifier = modifier,
                    formFactor = formFactor,
                    isActive = isActive,
                    player = player,
                    gps = gps,
                    onChannelClick = { channelId -> nav.navigate(Destination.LiveChannelDetail(channelId)) },
                    onRecordingClick = { recordingId -> nav.navigate(Destination.LiveRecordingDetail(recordingId)) },
                    onSeriesRulesClick = { nav.navigate(Destination.LiveSeriesRuleDetail(null)) },
                    onSeedSeriesRule = { guideId -> nav.navigate(Destination.LiveSeriesRuleDetail(null, guideId)) },
                    onOnNowClick = { nav.navigate(Destination.LiveOnNow) },
                )
            }
        }
        is Destination.Tab.Wanted, is Destination.Tab.Downloads -> {
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                dev.kobbler.app.ui.dashboard.WantedScreen(
                    api = api,
                    modifier = modifier,
                    formFactor = formFactor,
                    // Phone keeps both rail entries, so each opens its own half. Wide form
                    // factors fold Downloads into this screen and only list "Wanted" in the
                    // rail, where downloads are the half worth landing on.
                    initialTab = when {
                        tab is Destination.Tab.Downloads -> dev.kobbler.app.ui.dashboard.WantedTab.Downloads
                        formFactor != FormFactor.Phone -> dev.kobbler.app.ui.dashboard.WantedTab.Downloads
                        else -> dev.kobbler.app.ui.dashboard.WantedTab.Wanted
                    },
                    onItemClick = { item ->
                        when (item.mediaType) {
                            "movie" -> nav.navigate(Destination.MovieDetail(item.id))
                            "tv_show" -> nav.navigate(Destination.TvShowDetail(item.id))
                            else -> nav.navigate(Destination.BookDetail(item.id))
                        }
                    },
                    onDownloadClick = { target ->
                        when (target.mediaType) {
                            "movie" -> nav.navigate(Destination.MovieDetail(target.id))
                            "tv_show" -> nav.navigate(Destination.TvShowDetail(target.id))
                            else -> nav.navigate(Destination.BookDetail(target.id))
                        }
                    },
                )
            }
        }
        is Destination.Tab.Notifications -> {
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                dev.kobbler.app.ui.dashboard.NotificationsScreen(
                    api = api,
                    modifier = modifier,
                    formFactor = formFactor,
                )
            }
        }
        is Destination.Tab.Calendar -> {
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                dev.kobbler.app.ui.dashboard.CalendarScreen(
                    api = api,
                    modifier = modifier,
                    formFactor = formFactor,
                    onItemClick = { item ->
                        when {
                            item.link.startsWith("/movies/") -> nav.navigate(Destination.MovieDetail(item.link.removePrefix("/movies/")))
                            item.link.startsWith("/tv/episodes/") -> nav.navigate(Destination.TvShowDetail(item.link.removePrefix("/tv/episodes/"), isEpisodeId = true))
                            item.link.startsWith("/sports/events/") -> nav.navigate(Destination.TvShowDetail(item.link.removePrefix("/sports/events/"), isEpisodeId = true))
                            item.link.startsWith("/library/") -> nav.navigate(Destination.BookDetail(item.link.removePrefix("/library/")))
                        }
                    },
                )
            }
        }
        is Destination.Tab.Settings -> {
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                dev.kobbler.app.ui.dashboard.SettingsScreen(
                    serverUrl = serverUrl,
                    onLogout = onLogout,
                    modifier = modifier,
                    formFactor = formFactor,
                    onOpenProfile = { nav.navigate(Destination.Profile) },
                )
            }
        }
        is Destination.Tab.Activity -> {
            TabScreenWithTopBar(onMenuClick, onSearchClick, formFactor) {
                dev.kobbler.app.ui.dashboard.ActivityScreen(
                    api = api,
                    modifier = modifier,
                    formFactor = formFactor,
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    nav: NavigationState,
    api: KobblerApi,
    formFactor: FormFactor,
    player: dev.kobbler.kobplayer.Player?,
    gps: GlobalPlayerState,
    modifier: Modifier,
) {
    val playerScope = rememberCoroutineScope()
    when (val dest = nav.current) {
        is Destination.MovieDetail -> {
            val (state, retry) = rememberCached("movie-${dest.movieId}", dest.movieId) {
                api.getMovie(dest.movieId)
            }
            RefreshOnEvents(
                api = api,
                key = dest.movieId,
                matches = { it.kind == "movie_updated" && it.idField("movie_id") == dest.movieId },
                onRefresh = retry,
            )
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { m ->
                InlineMoviePlayer(
                    movie = m,
                    api = api,
                    formFactor = formFactor,
                    player = player,
                    gps = gps,
                    autoPlay = dest.autoPlay,
                    onBack = { nav.goBack() },
                    onPersonClick = { personId -> nav.navigate(Destination.PersonDetail(personId)) },
                )
            }
        }
        is Destination.TvShowDetail -> {
            val cacheKey = if (dest.isEpisodeId) "show-via-ep-${dest.showId}" else "show-${dest.showId}"
            val (state, retry) = rememberCached(cacheKey, dest.showId, dest.isEpisodeId) {
                if (dest.isEpisodeId) api.getShow(api.getShowIdForEpisode(dest.showId))
                else api.getShow(dest.showId)
            }
            RefreshOnEvents(
                api = api,
                key = cacheKey,
                // Unfiltered by id, unlike the movie and book cases. This page is built
                // from show, season and episode rows; the season and episode events name
                // only themselves, and the show id is not even known here when the
                // screen was opened from an episode. The refresh is silent and coalesced,
                // so the cost of catching a neighbour's event is one request that
                // changes nothing on screen.
                matches = {
                    it.kind == "tv_show_updated" ||
                        it.kind == "tv_season_updated" ||
                        it.kind == "tv_episode_updated"
                },
                onRefresh = retry,
            )
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { s ->
                InlineTvPlayer(
                    show = s,
                    api = api,
                    formFactor = formFactor,
                    player = player,
                    gps = gps,
                    autoPlay = dest.autoPlay,
                    onBack = { nav.goBack() },
                    onPersonClick = { personId -> nav.navigate(Destination.PersonDetail(personId)) },
                )
            }
        }
        is Destination.BookDetail -> {
            val (state, retry) = rememberCached("book-${dest.bookId}", dest.bookId) { api.getBook(dest.bookId) }
            RefreshOnEvents(
                api = api,
                key = dest.bookId,
                matches = { it.kind == "library_updated" && it.idField("book_id") == dest.bookId },
                onRefresh = retry,
            )
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { book ->
                BookDetailScreen(
                    book = book,
                    api = api,
                    formFactor = formFactor,
                    onBack = { nav.goBack() },
                    onRead = { nav.navigate(Destination.BookReader(book.id)) },
                    onListen = { nav.navigate(Destination.AudiobookPlayer(book.id)) },
                    onViewComic = { nav.navigate(Destination.ComicViewer(book.id)) },
                    onAuthorClick = { authorId -> nav.navigate(Destination.AuthorDetail(authorId)) },
                    onSeriesClick = { seriesId -> nav.navigate(Destination.SeriesDetail(seriesId)) },
                )
            }
        }
        is Destination.AuthorDetail -> {
            val (state, retry) = rememberCached("author-${dest.authorId}", dest.authorId) {
                coroutineScope {
                    val author = async { api.getAuthor(dest.authorId) }
                    val books = async { api.getAuthorBooks(dest.authorId) }
                    author.await() to books.await()
                }
            }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { (author, books) ->
                AuthorDetailScreen(
                    author = author,
                    books = books,
                    formFactor = formFactor,
                    onBack = { nav.goBack() },
                    onBookClick = { book -> nav.navigate(Destination.BookDetail(book.id)) },
                    onSeriesClick = { seriesId -> nav.navigate(Destination.SeriesDetail(seriesId)) },
                )
            }
        }
        is Destination.SeriesDetail -> {
            val (state, retry) = rememberCached("series-${dest.seriesId}", dest.seriesId) {
                coroutineScope {
                    val series = async { api.getSeries(dest.seriesId) }
                    val books = async { api.getSeriesBooks(dest.seriesId) }
                    series.await() to books.await()
                }
            }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { (series, books) ->
                SeriesDetailScreen(
                    series = series,
                    books = books,
                    formFactor = formFactor,
                    onBack = { nav.goBack() },
                    onBookClick = { book -> nav.navigate(Destination.BookDetail(book.id)) },
                    onAuthorClick = { authorId -> nav.navigate(Destination.AuthorDetail(authorId)) },
                )
            }
        }
        is Destination.DiscoverItemDetail -> {
            val (state, retry) = rememberCached(
                "discover-${dest.mediaType}-${dest.provider}-${dest.providerId}",
                dest.mediaType, dest.provider, dest.providerId,
            ) {
                api.getDiscoverDetail(dest.mediaType, dest.provider, dest.providerId)
            }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { detail ->
                DiscoverDetailScreen(
                    detail = detail,
                    api = api,
                    formFactor = formFactor,
                    player = player,
                    gps = gps,
                    onBack = { nav.goBack() },
                    onViewInLibrary = { mediaType, libraryId ->
                        libraryDestination(mediaType, libraryId)?.let { nav.navigate(it) }
                    },
                )
            }
        }
        is Destination.IptvChannelDetail -> {
            val (state, retry) = rememberCached("channel-${dest.channelId}", dest.channelId) {
                coroutineScope {
                    val channel = async { api.getIptvChannel(dest.channelId) }
                    val epg = async { api.getIptvChannelEpg(dest.channelId) }
                    val fav = async {
                        try { api.getIptvFavourites() } catch (_: Exception) { emptyList() }
                            .firstOrNull { it.channelId == dest.channelId }
                    }
                    val favourite = fav.await()
                    channel.await().copy(isFavourite = favourite != null, favouriteId = favourite?.id) to epg.await()
                }
            }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { (channel, epg) ->
                dev.kobbler.app.ui.detail.IptvChannelDetailScreen(
                    channel = channel,
                    epg = epg,
                    api = api,
                    player = player,
                    gps = gps,
                    onBack = { nav.goBack() },
                    formFactor = formFactor,
                )
            }
        }
        is Destination.LiveChannelDetail -> {
            val (state, retry) = rememberCached("live-channel-${dest.channelId}", dest.channelId) {
                coroutineScope {
                    // There is no GET /channels/{id}; guide/now is the one endpoint that
                    // returns a whole channel row for an id, so it doubles as the fallback
                    // for a channel the guide carries no entries for.
                    val guide = async { api.getLiveGuide(channelIds = listOf(dest.channelId)) }
                    val now = async { api.getLiveGuideNow(listOf(dest.channelId)).firstOrNull() }
                    val row = guide.await().firstOrNull { it.channel.id == dest.channelId }
                    val resolved = row?.channel ?: now.await()
                    requireNotNull(resolved) { "Channel not found" } to (row?.entries ?: emptyList())
                }
            }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { (channel, entries) ->
                dev.kobbler.app.ui.detail.LiveChannelDetailScreen(
                    channel = channel,
                    entries = entries,
                    api = api,
                    formFactor = formFactor,
                    onBack = { nav.goBack() },
                    onWatch = { ch -> dev.kobbler.app.ui.player.startLiveWatch(ch, player, gps, api, playerScope) },
                )
            }
        }
        is Destination.LiveRecordingDetail -> {
            val (state, retry) = rememberCached("live-recording-${dest.recordingId}", dest.recordingId) {
                api.getLiveRecording(dest.recordingId)
            }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { recording ->
                dev.kobbler.app.ui.detail.LiveRecordingDetailScreen(
                    recording = recording,
                    api = api,
                    formFactor = formFactor,
                    onBack = { nav.goBack() },
                    onPlay = { rec -> dev.kobbler.app.ui.player.startRecordingPlayback(rec, player, gps, api, playerScope) },
                )
            }
        }
        is Destination.LiveOnNow -> {
            dev.kobbler.app.ui.dashboard.LiveOnNowScreen(
                api = api,
                modifier = modifier,
                formFactor = formFactor,
                onBack = { nav.goBack() },
                onWatch = { ch -> dev.kobbler.app.ui.player.startLiveWatch(ch, player, gps, api, playerScope) },
            )
        }
        is Destination.LiveSeriesRuleDetail -> {
            dev.kobbler.app.ui.dashboard.LiveSeriesRuleScreen(
                api = api,
                formFactor = formFactor,
                initialRuleId = dest.ruleId,
                seedGuideId = dest.seedGuideId,
                onBack = { nav.goBack() },
                modifier = modifier,
            )
        }
        is Destination.Profile -> {
            dev.kobbler.app.ui.dashboard.ProfileScreen(
                api = api,
                onBack = { nav.goBack() },
                modifier = modifier,
                formFactor = formFactor,
            )
        }
        is Destination.PersonDetail -> {
            val (state, retry) = rememberCached("person-${dest.personId}", dest.personId) {
                coroutineScope {
                    val person = async { api.getPerson(dest.personId) }
                    val filmography = async { api.getPersonFilmography(dest.personId) }
                    person.await() to filmography.await()
                }
            }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }, modifier = modifier) { (person, filmography) ->
                dev.kobbler.app.ui.detail.PersonDetailScreen(
                    person = person,
                    filmography = filmography,
                    api = api,
                    onBack = { nav.goBack() },
                    onCreditClick = { credit ->
                        val id = credit.libraryId ?: return@PersonDetailScreen
                        when (credit.mediaType) {
                            "movie" -> nav.navigate(Destination.MovieDetail(id))
                            "tv" -> nav.navigate(Destination.TvShowDetail(id))
                        }
                    },
                    formFactor = formFactor,
                )
            }
        }
        else -> {}
    }
}

/** Wraps a tab screen with the phone top bar on phones. Desktop/TV use the
 *  scaffold-level KobTopBar instead. */
@Composable
private fun TabScreenWithTopBar(
    onMenuClick: (() -> Unit)?,
    onSearchClick: (() -> Unit)?,
    formFactor: FormFactor,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (onMenuClick != null && formFactor == FormFactor.Phone) {
            KobPhoneTopBar(
                onSearchClick = { onSearchClick?.invoke() },
                onCastClick = {},
                onMenuClick = onMenuClick,
            )
        }
        Box(Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun PlayerContent(
    nav: NavigationState,
    api: KobblerApi,
    formFactor: FormFactor,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
) {
    when (val dest = nav.current) {
        is Destination.AudiobookPlayer -> {
            val (state, retry) = rememberCached("book-${dest.bookId}", dest.bookId) { api.getBook(dest.bookId) }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }) { book ->
                AudiobookPlayerScreen(
                    book = book,
                    api = api,
                    onBack = { nav.goBack() },
                    onOpenReader = { nav.navigate(Destination.BookReader(book.id)) },
                )
            }
        }
        is Destination.BookReader -> {
            val (state, retry) = rememberCached("book-${dest.bookId}", dest.bookId) { api.getBook(dest.bookId) }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }) { book ->
                ReaderScreen(
                    book = book,
                    api = api,
                    formFactor = formFactor,
                    onBack = { nav.goBack() },
                )
            }
        }
        is Destination.ComicViewer -> {
            val (state, retry) = rememberCached("book-${dest.bookId}", dest.bookId) { api.getBook(dest.bookId) }
            AsyncContent(state, onRetry = retry, onBack = { nav.goBack() }) { book ->
                ComicReader(
                    book = book,
                    api = api,
                    onBack = { nav.goBack() },
                )
            }
        }
        else -> {}
    }
}
