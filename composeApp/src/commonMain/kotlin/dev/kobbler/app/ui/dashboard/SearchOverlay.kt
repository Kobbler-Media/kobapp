package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.SearchHitDto
import dev.kobbler.app.data.SearchResult
import dev.kobbler.app.data.SearchSuggestion
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.BackHandler
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.platform.KeyboardCompletions
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.tvAutoGlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.app.ui.imePaddingUnlessTv
import dev.kobbler.app.ui.kobKeyboardShown

private const val RECENT_MAX = 8
private const val RECENT_KEY = "search_recents"
internal const val SearchFieldTag = "search-field"
internal const val SearchResultsTag = "search-results"

@Composable
fun SearchOverlay(
    api: KobblerApi,
    scope: SearchScope,
    onScopeChange: (SearchScope) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onResultClick: (SearchResult) -> Unit,
    onDiscoverItemClick: (DiscoverItem) -> Unit = {},
    formFactor: FormFactor = FormFactor.Desktop,
    keyboardShown: Boolean = kobKeyboardShown,
    /**
     * Whether the query field should take focus. False while the search page is on
     * screen but the viewer is still in the sidebar: focusing the field there opens the
     * on-screen keyboard, which then eats the D-pad keys the rail needs.
     */
    autoFocusQuery: Boolean = true,
    scopes: List<SearchScope> = SearchScope.entries,
    session: SearchSession = remember { SearchSession() },
) {
    val colors = KobappTheme.colors
    val tv = formFactor == FormFactor.TV
    val searchesLive = scope == SearchScope.LiveTv || (scope == SearchScope.All && SearchScope.LiveTv in scopes)
    val searchesIptv = scope == SearchScope.Iptv || (scope == SearchScope.All && SearchScope.Iptv in scopes)

    val searchKey = SearchKey(query, scope, searchesLive, searchesIptv)
    val returning = remember { session.takeReturn(searchKey) }
    var libraryLoading by remember { mutableStateOf(false) }
    var discoverLoading by remember { mutableStateOf(false) }
    var liveLoading by remember { mutableStateOf(false) }
    var iptvLoading by remember { mutableStateOf(false) }
    val libraryHits = remember(session.library, scope) {
        session.library?.let { it.topHits + it.remaining }.orEmpty().filter { scope.includes(it.type) }
    }
    val expandedSections = session.expanded
    val gridState = rememberLazyGridState(returning?.index ?: 0, returning?.offset ?: 0)
    val focusRequester = remember { FocusRequester() }
    var recents by remember { mutableStateOf(loadRecents()) }
    // Committing a query — picking a recent, or pressing the keyboard's go — takes the
    // element that held focus off screen with it. Hand focus to the first result once
    // there is one; typing does not arm it, or every keystroke would yank focus out of
    // the field and into the list.
    var focusResults by remember { mutableStateOf(false) }
    val layout = remember(libraryHits, session.discover, session.live, session.iptv) {
        searchLayout(searchSections(libraryHits, session.discover, session.live, session.iptv), query)
    }
    val firstResultKey = layout.firstRowKey

    LaunchedEffect(searchKey) {
        if (session.completeKey == searchKey) return@LaunchedEffect
        // A page left for a result before every source answered keeps what it had, so
        // BACK lands on the same rows while the rest is fetched again.
        val resumed = session.resultsKey == searchKey
        if (!resumed) session.clear()
        libraryLoading = false
        discoverLoading = false
        liveLoading = false
        iptvLoading = false
        if (query.isBlank()) return@LaunchedEffect
        if (!resumed) {
            delay(250)
            expandedSections.clear()
            session.resultsKey = searchKey
        }
        coroutineScope {
            if (scope.searchesLibrary) {
                libraryLoading = true
                launch {
                    try { session.library = api.search(query, limit = 50) } catch (_: Exception) {}
                    libraryLoading = false
                }
            }
            if (scope.discoverMediaTypes.isNotEmpty()) {
                discoverLoading = true
                launch {
                    try { session.discover = api.searchDiscover(query, scope.discoverMediaTypes) } catch (_: Exception) {}
                    discoverLoading = false
                }
            }
            if (searchesLive) {
                liveLoading = true
                launch {
                    session.live = searchLive(api, query)
                    liveLoading = false
                }
            }
            if (searchesIptv) {
                iptvLoading = true
                launch {
                    session.iptv = searchIptv(api, query)
                    iptvLoading = false
                }
            }
        }
        session.completeKey = searchKey
    }

    LaunchedEffect(query.trim(), scope) {
        val key = query.trim() to scope
        if (session.suggestionsKey == key) return@LaunchedEffect
        if (session.suggestionsKey?.first?.isBlank() != key.first.isBlank()) session.suggestions = emptyList()
        delay(150)
        session.suggestions = fetchSuggestions(api, query, scope)
        session.suggestionsKey = key
    }

    val commitRecent: (String) -> Unit = { term ->
        val t = term.trim()
        if (t.isNotEmpty()) {
            val updated = (listOf(t) + recents.filter { !it.equals(t, ignoreCase = true) }).take(RECENT_MAX)
            recents = updated
            UserSettings.putString(RECENT_KEY, updated.joinToString("\n"))
        }
    }

    val handleResultClick: (SearchHitDto) -> Unit = { hit ->
        commitRecent(query)
        onResultClick(hit.toSearchResult(api.apiUrl))
        onDismiss()
    }
    val handleDiscoverClick: (DiscoverItem) -> Unit = { item ->
        commitRecent(query)
        onDiscoverItemClick(item)
        onDismiss()
    }
    val handleLiveClick: (LiveChannel) -> Unit = { channel ->
        commitRecent(query)
        onResultClick(SearchResult(type = LiveChannelResultType, id = channel.id, title = channel.name))
        onDismiss()
    }
    val handleIptvClick: (IptvChannel) -> Unit = { channel ->
        commitRecent(query)
        onResultClick(SearchResult(type = IptvChannelResultType, id = channel.id, title = channel.name))
        onDismiss()
    }

    val content: @Composable () -> Unit = {
        val focusScope = LocalFocusScope.current
        val leaving: (() -> Unit) -> Unit = { open ->
            session.leaveFor(focusScope.focused, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            open()
        }
        SearchContent(
            query = query,
            onQueryChange = { onQueryChange(it); focusResults = false },
            onSubmit = { commitRecent(query); focusResults = formFactor != FormFactor.Phone },
            onClear = { onQueryChange("") },
            onDismiss = onDismiss,
            focusRequester = focusRequester,
            scope = scope,
            scopes = scopes,
            onScopeChange = onScopeChange,
            layout = layout,
            loading = libraryLoading || discoverLoading || liveLoading || iptvLoading,
            libraryLoading = libraryLoading,
            discoverLoading = discoverLoading,
            liveLoading = liveLoading,
            iptvLoading = iptvLoading,
            expandedSections = expandedSections,
            gridState = gridState,
            apiUrl = api.apiUrl,
            onResultClick = { leaving { handleResultClick(it) } },
            onDiscoverClick = { leaving { handleDiscoverClick(it) } },
            onLiveClick = { leaving { handleLiveClick(it) } },
            onIptvClick = { leaving { handleIptvClick(it) } },
            recents = recents,
            suggestions = session.suggestions.filterNot { it.title.equals(query.trim(), ignoreCase = true) },
            onPickRecent = { onQueryChange(it); focusResults = formFactor != FormFactor.Phone },
            onClearRecents = {
                recents = emptyList()
                UserSettings.remove(RECENT_KEY)
            },
            formFactor = formFactor,
            keyboardShown = keyboardShown,
        )
    }

    BackHandler { onDismiss() }

    val escapeToDismiss = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            onDismiss()
            true
        } else {
            false
        }
    }

    // Its own scope, so the keyed rows below register here and not in the detached
    // global one shared with the screen underneath. Entry focus is set explicitly.
    FocusScopeHost(id = "search-overlay", restoreEnabled = false) {
        EntryFocus(
            enabled = autoFocusQuery,
            returnTo = returning?.let { listOfNotNull(it.focusKey, firstResultKey) },
            queryField = focusRequester,
        )
        FocusFirstResult(
            armed = focusResults,
            key = firstResultKey,
            onDone = { focusResults = false },
        )
        when (formFactor) {
            FormFactor.Phone -> Box(
                Modifier.fillMaxSize().background(colors.bg).safeTopPadding().then(escapeToDismiss),
            ) { content() }
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The TV panel is full-bleed and opaque, so a scrim under it is never seen.
                    .then(if (tv) Modifier else Modifier.background(colors.bg.copy(alpha = 0.78f)))
                    .then(escapeToDismiss)
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .then(
                            // Full-bleed on TV: an inset panel over a scrim reads as a
                            // stray frame when the overlay already owns the screen.
                            if (tv) Modifier.fillMaxSize()
                            else Modifier.padding(top = 92.dp).widthIn(max = 820.dp).fillMaxWidth(0.86f),
                        )
                        .clip(RoundedCornerShape(if (tv) 0.dp else 18.dp))
                        .background(if (tv) colors.bg else colors.elev1)
                        .then(
                            // A floating bordered card reads as a stray frame on a TV,
                            // where the overlay already owns the whole screen.
                            if (tv) Modifier
                            else Modifier.border(1.dp, colors.line, RoundedCornerShape(18.dp)),
                        )
                        .focusProperties { canFocus = false }
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) { content() }
            }
        }
    }
}

@Composable
private fun SearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    focusRequester: FocusRequester,
    scope: SearchScope,
    scopes: List<SearchScope>,
    onScopeChange: (SearchScope) -> Unit,
    layout: SearchLayout,
    loading: Boolean,
    libraryLoading: Boolean,
    discoverLoading: Boolean,
    liveLoading: Boolean,
    iptvLoading: Boolean,
    expandedSections: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    gridState: LazyGridState,
    apiUrl: String,
    onResultClick: (SearchHitDto) -> Unit,
    onDiscoverClick: (DiscoverItem) -> Unit,
    onLiveClick: (LiveChannel) -> Unit,
    onIptvClick: (IptvChannel) -> Unit,
    recents: List<String>,
    suggestions: List<SearchSuggestion>,
    onPickRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    formFactor: FormFactor,
    keyboardShown: Boolean,
) {
    val colors = KobappTheme.colors
    val phone = formFactor == FormFactor.Phone
    val tv = formFactor == FormFactor.TV

    val nothing = !loading && layout.top.isEmpty() && layout.sections.isEmpty() && query.isNotBlank()
    val jumps = if (query.isBlank()) emptyList() else searchJumps(layout, expandedSections)
    val coroutineScope = rememberCoroutineScope()
    val focusScope = LocalFocusScope.current
    val onJump: (SearchJump) -> Unit = { jump ->
        coroutineScope.launch {
            if (tv) {
                gridState.scrollToItem(jump.itemIndex)
                focusScope.restore(jump.focusKey, maxFrames = 40)
            } else {
                gridState.animateScrollToItem(jump.itemIndex)
            }
        }
    }

    Column(Modifier.fillMaxSize().imePaddingUnlessTv()) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onSubmit = onSubmit,
            onClear = onClear,
            onDismiss = onDismiss,
            focusRequester = focusRequester,
            phone = phone,
            tv = tv,
            keyboardSuggestions = if (tv) suggestions.map { it.title } else emptyList(),
            onPickSuggestion = onPickRecent,
            placeholder = scope.placeholder,
            scopeChip = if (scopes.size > 1) {
                { SearchScopeChip(scope = scope, scopes = scopes, onSelect = onScopeChange) }
            } else {
                null
            },
        )
        if (formFactor == FormFactor.Desktop && suggestions.isNotEmpty()) {
            SearchSuggestionStrip(
                suggestions = suggestions,
                onPick = onPickRecent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        if (jumps.isNotEmpty()) {
            SearchJumpChips(
                jumps = jumps,
                onJump = onJump,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (phone) 16.dp else 18.dp)
                    .padding(bottom = 6.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(if (tv) 2 else 1),
            state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(SearchResultsTag),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 24.dp else 0.dp),
            contentPadding = PaddingValues(
                start = if (phone) 16.dp else 14.dp,
                end = if (phone) 16.dp else 14.dp,
                bottom = if (phone) 24.dp else 18.dp,
            ),
        ) {
            // The lazy grid holds its place by the key of the first visible item. With no
            // fixed item up here, a source answering late slots in above whatever row was
            // first and the grid scrolls to keep that row, shoving the new results off the top.
            fullWidthItem(key = "top") { Spacer(Modifier.height(4.dp)) }
            if (query.isBlank()) {
                if (recents.isNotEmpty()) {
                    fullWidthItem(key = "recents-head") {
                        SearchTermsHeader("RECENT SEARCHES", Icons.Outlined.History, onClear = onClearRecents)
                    }
                    items(recents, key = { "recent-$it" }, span = { GridItemSpan(maxLineSpan) }) { term ->
                        SearchTermRow(term = term, icon = Icons.Outlined.History, focusKey = "recent.$term", onClick = { onPickRecent(term) })
                    }
                } else {
                    fullWidthItem(key = "empty-hint") { EmptyHint() }
                }
            } else if (nothing) {
                fullWidthItem(key = "no-results") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No results", style = KobappTheme.type.body, color = colors.textLo)
                    }
                }
            } else {
                searchResultSections(
                    layout = layout,
                    expanded = expandedSections,
                    apiUrl = apiUrl,
                    colors = colors,
                    onResultClick = onResultClick,
                    onDiscoverClick = onDiscoverClick,
                    onLiveClick = onLiveClick,
                    onIptvClick = onIptvClick,
                )
                if (libraryLoading) {
                    fullWidthItem(key = "lib-loading") { SectionLoadingRow("Searching library…") }
                }
                if (discoverLoading) {
                    fullWidthItem(key = "disc-loading") { SectionLoadingRow("Searching Discover…") }
                }
                if (liveLoading) {
                    fullWidthItem(key = "live-loading") { SectionLoadingRow("Searching the TV guide…") }
                }
                if (iptvLoading) {
                    fullWidthItem(key = "iptv-loading") { SectionLoadingRow("Searching IPTV…") }
                }

                if (phone) {
                    fullWidthItem(key = "bottom-spacer") { Spacer(Modifier.height(32.dp).navigationBarsPadding()) }
                }
            }
        }
        if (phone && keyboardShown && suggestions.isNotEmpty()) {
            SearchSuggestionStrip(
                suggestions = suggestions,
                onPick = onPickRecent,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = colors.lineSoft)
                    .navigationBarsPadding(),
            )
        }
    }
}

/**
 * Back from a result the viewer opened, focus goes back to that row; focusing the query
 * field instead would raise the on-screen keyboard over the results they came back to.
 */
@Composable
private fun EntryFocus(enabled: Boolean, returnTo: List<String>?, queryField: FocusRequester) {
    val scope = LocalFocusScope.current
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        if (returnTo?.any { scope.restore(it, maxFrames = 40) } != true) queryField.requestFocus()
    }
}

/**
 * Waits for [key] to exist and focuses it. The list is still being composed when a query
 * commits, so this cannot be a single requestFocus — [FocusScope.restore] retries across
 * frames until the row it names is there.
 */
@Composable
private fun FocusFirstResult(armed: Boolean, key: String?, onDone: () -> Unit) {
    val scope = LocalFocusScope.current
    LaunchedEffect(armed, key) {
        if (!armed || key == null) return@LaunchedEffect
        if (scope.restore(key, maxFrames = 40)) onDone()
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    focusRequester: FocusRequester,
    phone: Boolean,
    tv: Boolean,
    keyboardSuggestions: List<String>,
    onPickSuggestion: (String) -> Unit,
    placeholder: String,
    scopeChip: (@Composable () -> Unit)?,
) {
    val colors = KobappTheme.colors
    val focusManager = LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKeyGroup("searchbar")
            .padding(
                horizontal = if (phone) 10.dp else 18.dp,
                vertical = if (phone) 8.dp else 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 12.dp),
    ) {
        IconBubble(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            onClick = onDismiss,
            tint = colors.textHi,
            key = "back",
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = if (phone) 40.dp else 48.dp)
                .clip(KobappTheme.shapes.pill)
                .background(colors.elev2)
                .border(1.dp, colors.lineSoft, KobappTheme.shapes.pill)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = colors.textLo,
                    modifier = Modifier.size(18.dp),
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = KobappTheme.type.body,
                            color = colors.textLo,
                        )
                    }
                    KeyboardCompletions(keyboardSuggestions, onPickSuggestion) {
                        BasicTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            textStyle = KobappTheme.type.body.copy(color = colors.textHi),
                            cursorBrush = SolidColor(KobappTheme.colors.accHome),
                            // A single-line text field consumes DPAD down, which would
                            // otherwise trap the remote in the query box.
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SearchFieldTag)
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    event.type == KeyEventType.KeyDown &&
                                        event.key == Key.DirectionDown &&
                                        focusManager.moveFocus(FocusDirection.Down)
                                },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            // The TV IME's tick only dismisses if something actually
                            // handles the action.
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onAny = {
                                    onSubmit()
                                    keyboardController?.hide()
                                },
                            ),
                        )
                    }
                }
                if (query.isNotEmpty()) {
                    IconBubble(icon = Icons.Outlined.Close, onClick = onClear, tint = colors.textLo, key = "clear", size = 28.dp)
                }
                scopeChip?.invoke()
            }
        }
        if (phone) {
            val voiceLauncher = dev.kobbler.app.platform.rememberVoiceInputLauncher { spoken ->
                onQueryChange(spoken)
                onSubmit()
            }
            if (voiceLauncher != null && voiceLauncher.isAvailable) {
                IconBubble(
                    icon = Icons.Outlined.Mic,
                    onClick = { voiceLauncher.start() },
                    tint = colors.textHi,
                    key = "voice",
                    accentTint = colors.accHome,
                )
            }
        } else if (!tv) {
            Text(
                "Esc",
                style = KobappTheme.type.bodySm,
                color = colors.textLo,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun IconBubble(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color,
    key: String,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    accentTint: Color? = null,
) {
    val colors = KobappTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .focusKey(key)
            .tvAutoGlow(cornerRadiusDp = size.value / 2f, scale = 1f)
            .clip(CircleShape)
            .background(if (accentTint != null) lerp(colors.elev2, accentTint, 0.18f) else colors.elev2)
            .border(1.dp, if (accentTint != null) lerp(Color.Transparent, accentTint, 0.4f) else colors.lineSoft, CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = accentTint ?: tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
internal fun SearchTermsHeader(label: String, icon: ImageVector, onClear: (() -> Unit)? = null) {
    val colors = KobappTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.textLo, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = KobappTheme.type.label,
            color = colors.textLo,
        )
        if (onClear != null) {
            Spacer(Modifier.weight(1f))
            KobButton(
                onClick = onClear,
                style = KobButtonStyle.Ghost,
                modifier = Modifier.focusKey("clear-recents"),
            ) {
                Text("Clear", style = KobappTheme.type.caption, color = colors.textLo)
            }
        }
    }
}

@Composable
internal fun SearchTermRow(term: String, icon: ImageVector, focusKey: String, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(focusKey)
            .tvAutoGlow(cornerRadiusDp = 10f, scale = 1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.elev2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.textLo, modifier = Modifier.size(14.dp))
        }
        Text(term, style = KobappTheme.type.body, color = colors.textHi, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.Search, null, tint = colors.textLo, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun EmptyHint() {
    val colors = KobappTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Search, null, tint = colors.textLo, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            "Start typing to search your library and Discover",
            style = KobappTheme.type.caption,
            color = colors.textLo,
        )
    }
}

private fun loadRecents(): List<String> =
    UserSettings.getString(RECENT_KEY)
        ?.split('\n')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
