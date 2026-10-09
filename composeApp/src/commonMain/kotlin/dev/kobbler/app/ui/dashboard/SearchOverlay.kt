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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.kobfocus.FocusScopeHost
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.DiscoverSearchResults
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.SearchHitDto
import dev.kobbler.app.data.SearchResponse
import dev.kobbler.app.data.SearchResult
import dev.kobbler.app.data.UserSettings
import dev.kobbler.app.platform.BackHandler
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappColors
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.PillStatus
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.StatusPill
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.detail.RatingBadge
import dev.kobbler.app.ui.navigation.Destination
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.app.ui.imePaddingUnlessTv

private const val SECTION_TOP_N = 5
private const val RECENT_MAX = 8
private const val RECENT_KEY = "search_recents"

@Composable
fun SearchOverlay(
    api: KobblerApi,
    currentTab: Destination.Tab,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onResultClick: (SearchResult) -> Unit,
    onDiscoverItemClick: (DiscoverItem) -> Unit = {},
    formFactor: FormFactor = FormFactor.Desktop,
    /**
     * Whether the query field should take focus. False while the search page is on
     * screen but the viewer is still in the sidebar: focusing the field there opens the
     * on-screen keyboard, which then eats the D-pad keys the rail needs.
     */
    autoFocusQuery: Boolean = true,
) {
    val colors = KobappTheme.colors
    val tv = formFactor == FormFactor.TV
    val discoverMediaTypes = remember(currentTab) {
        when (currentTab) {
            Destination.Tab.Movies -> setOf("movie")
            Destination.Tab.TvShows -> setOf("tv")
            Destination.Tab.Books, Destination.Tab.Audiobooks -> setOf("book")
            else -> setOf("movie", "tv")
        }
    }

    var libraryResults by remember { mutableStateOf<SearchResponse?>(null) }
    var libraryLoading by remember { mutableStateOf(false) }
    var discoverResults by remember { mutableStateOf<DiscoverSearchResults?>(null) }
    var discoverLoading by remember { mutableStateOf(false) }
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    val focusRequester = remember { FocusRequester() }
    var recents by remember { mutableStateOf(loadRecents()) }
    // Committing a query — picking a recent, or pressing the keyboard's go — takes the
    // element that held focus off screen with it. Hand focus to the first result once
    // there is one; typing does not arm it, or every keystroke would yank focus out of
    // the field and into the list.
    var focusResults by remember { mutableStateOf(false) }
    val firstResultKey = remember(libraryResults, discoverResults) {
        val hits = libraryResults?.let { it.topHits + it.remaining } ?: emptyList()
        searchSections(hits, discoverResults).firstOrNull()?.firstRowKey
    }

    LaunchedEffect(query, discoverMediaTypes) {
        if (query.isBlank()) {
            libraryResults = null
            discoverResults = null
            libraryLoading = false
            discoverLoading = false
            return@LaunchedEffect
        }
        delay(250)
        libraryResults = null
        discoverResults = null
        libraryLoading = true
        discoverLoading = true
        expandedSections.clear()
        launch {
            try { libraryResults = api.search(query, limit = 50) } catch (_: Exception) {}
            libraryLoading = false
        }
        launch {
            try { discoverResults = api.searchDiscover(query, discoverMediaTypes) } catch (_: Exception) {}
            discoverLoading = false
        }
    }

    LaunchedEffect(autoFocusQuery) { if (autoFocusQuery) focusRequester.requestFocus() }

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

    val content: @Composable () -> Unit = {
        SearchContent(
            query = query,
            onQueryChange = { onQueryChange(it); focusResults = false },
            onSubmit = { commitRecent(query); focusResults = formFactor != FormFactor.Phone },
            onClear = { onQueryChange("") },
            onDismiss = onDismiss,
            focusRequester = focusRequester,
            libraryResults = libraryResults,
            libraryLoading = libraryLoading,
            discoverResults = discoverResults,
            discoverLoading = discoverLoading,
            expandedSections = expandedSections,
            apiUrl = api.apiUrl,
            onResultClick = handleResultClick,
            onDiscoverClick = handleDiscoverClick,
            recents = recents,
            onPickRecent = { onQueryChange(it); focusResults = formFactor != FormFactor.Phone },
            onClearRecents = {
                recents = emptyList()
                UserSettings.remove(RECENT_KEY)
            },
            formFactor = formFactor,
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
                    .background(colors.bg.copy(alpha = 0.78f))
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
    libraryResults: SearchResponse?,
    libraryLoading: Boolean,
    discoverResults: DiscoverSearchResults?,
    discoverLoading: Boolean,
    expandedSections: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    apiUrl: String,
    onResultClick: (SearchHitDto) -> Unit,
    onDiscoverClick: (DiscoverItem) -> Unit,
    recents: List<String>,
    onPickRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    formFactor: FormFactor,
) {
    val colors = KobappTheme.colors
    val phone = formFactor == FormFactor.Phone
    val tv = formFactor == FormFactor.TV

    val allLibHits = libraryResults?.let { it.topHits + it.remaining } ?: emptyList()

    val sections = remember(allLibHits, discoverResults) {
        searchSections(allLibHits, discoverResults)
    }
    val bothDone = !libraryLoading && !discoverLoading
    val nothing = bothDone && sections.isEmpty() && query.isNotBlank()

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
            loading = libraryLoading || discoverLoading,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (phone) 16.dp else 14.dp,
                end = if (phone) 16.dp else 14.dp,
                top = 4.dp,
                bottom = if (phone) 24.dp else 18.dp,
            ),
        ) {
            if (query.isBlank()) {
                if (recents.isNotEmpty()) {
                    item(key = "recents-head") {
                        RecentsHeader(onClear = onClearRecents)
                    }
                    items(recents, key = { "recent-$it" }) { term ->
                        RecentRow(term = term, onClick = { onPickRecent(term) })
                    }
                } else {
                    item(key = "empty-hint") { EmptyHint() }
                }
            } else if (nothing) {
                item(key = "no-results") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No results", style = KobappTheme.type.body, color = colors.textLo)
                    }
                }
            } else {
                sections.forEach { section ->
                    when (section) {
                        is SearchSection.Library -> librarySubsection(
                            type = section.type,
                            hits = section.hits,
                            apiUrl = apiUrl,
                            expanded = expandedSections["lib:${section.type}"] == true,
                            onToggleExpand = {
                                expandedSections["lib:${section.type}"] =
                                    expandedSections["lib:${section.type}"] != true
                            },
                            onResultClick = onResultClick,
                            colors = colors,
                        )
                        is SearchSection.Discover -> discoverSubsection(
                            mediaType = section.mediaType,
                            items = section.items,
                            expanded = expandedSections["disc:${section.mediaType}"] == true,
                            onToggleExpand = {
                                expandedSections["disc:${section.mediaType}"] =
                                    expandedSections["disc:${section.mediaType}"] != true
                            },
                            onItemClick = onDiscoverClick,
                            colors = colors,
                        )
                    }
                }
                if (libraryLoading) {
                    item(key = "lib-loading") { SectionLoadingRow("Searching library…") }
                }
                // Discover answers well after the library does, so its own rows arrive
                // into a list that is already being read. The search bar carries the
                // spinner; this only says which half is still out.
                if (discoverLoading) {
                    item(key = "disc-loading") { SectionLoadingRow("Searching Discover…") }
                }

                if (phone) {
                    item { Spacer(Modifier.height(32.dp).navigationBarsPadding()) }
                }
            }
        }
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
    loading: Boolean,
) {
    val colors = KobappTheme.colors
    val focusManager = LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth()) {
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
                                "Search movies, TV, books, authors…",
                                style = KobappTheme.type.body,
                                color = colors.textLo,
                            )
                        }
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
                    if (query.isNotEmpty()) {
                        IconBubble(icon = Icons.Outlined.Close, onClick = onClear, tint = colors.textLo, key = "clear", size = 28.dp)
                    }
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
        if (loading) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(lerp(colors.elev2, colors.accHome, 0.6f).copy(alpha = 0.55f)),
            )
        } else if (!phone) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineSoft))
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
private fun RecentsHeader(onClear: () -> Unit) {
    val colors = KobappTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.History, null, tint = colors.textLo, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "RECENT SEARCHES",
            style = KobappTheme.type.label,
            color = colors.textLo,
        )
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

@Composable
private fun RecentRow(term: String, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey("recent.$term")
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
            Icon(Icons.Outlined.History, null, tint = colors.textLo, modifier = Modifier.size(14.dp))
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

private fun LazyListScope.librarySubsection(
    type: String,
    hits: List<SearchHitDto>,
    apiUrl: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onResultClick: (SearchHitDto) -> Unit,
    colors: KobappColors,
) {
    item(key = "lib-section-$type") {
        Spacer(Modifier.height(14.dp))
        SectionHeading(
            title = sectionTitle(type),
            accent = typeAccent(type, colors),
            trailing = {
                Text(
                    "${hits.size}",
                    style = KobappTheme.type.bodySm,
                    color = colors.textLo,
                )
            },
        )
        Spacer(Modifier.height(6.dp))
    }
    val visible = if (expanded) hits else hits.take(SECTION_TOP_N)
    items(visible, key = { "lib-${it.type}-${it.id}" }) { hit ->
        SearchResultRow(hit = hit, apiUrl = apiUrl, accent = typeAccent(hit.type, colors), key = "lib.${hit.type}.${hit.id}", onClick = { onResultClick(hit) })
    }
    if (hits.size > SECTION_TOP_N) {
        item(key = "lib-more-$type") {
            MoreRow(
                expanded = expanded,
                remaining = hits.size - SECTION_TOP_N,
                accent = typeAccent(type, colors),
                key = "lib-more.$type",
                onClick = onToggleExpand,
            )
        }
    }
}

private fun LazyListScope.discoverSubsection(
    mediaType: String,
    items: List<DiscoverItem>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onItemClick: (DiscoverItem) -> Unit,
    colors: KobappColors,
) {
    val accent = when (mediaType) {
        "movie" -> colors.accMovies
        "tv" -> colors.accTv
        "book" -> colors.accBooks
        else -> colors.textLo
    }
    // Each one stands on its own now that they are read beside the library's sections,
    // so each says where it came from instead of sharing one heading further down.
    val label = when (mediaType) {
        "movie" -> "Discover · Movies"; "tv" -> "Discover · TV Shows"
        "book" -> "Discover · Books"; else -> "Discover · $mediaType"
    }
    item(key = "disc-section-$mediaType") {
        Spacer(Modifier.height(10.dp))
        SectionHeading(
            title = label,
            accent = accent,
            trailing = {
                Text(
                    "${items.size}",
                    style = KobappTheme.type.bodySm,
                    color = colors.textLo,
                )
            },
        )
        Spacer(Modifier.height(4.dp))
    }
    val visible = if (expanded) items else items.take(SECTION_TOP_N)
    items(visible, key = { "disc-${it.provider}-${it.providerId}" }) { item ->
        DiscoverOverlayCard(item = item, accent = accent, key = "disc.${item.provider}.${item.providerId}", onClick = { onItemClick(item) })
    }
    if (items.size > SECTION_TOP_N) {
        item(key = "disc-more-$mediaType") {
            MoreRow(
                expanded = expanded,
                remaining = items.size - SECTION_TOP_N,
                accent = accent,
                key = "disc-more.$mediaType",
                onClick = onToggleExpand,
            )
        }
    }
}

@Composable
private fun MoreRow(expanded: Boolean, remaining: Int, accent: Color, key: String, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(key)
            .tvAutoGlow(cornerRadiusDp = 8f, scale = 1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (expanded) "Show less" else "More ($remaining)",
            style = KobappTheme.type.caption.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
        )
    }
}

@Composable
private fun SectionLoadingRow(label: String) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = colors.textLo,
        )
        Text(label, style = KobappTheme.type.caption, color = colors.textLo)
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHitDto,
    apiUrl: String,
    accent: Color,
    key: String,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val displayTitle = hit.resolvedTitle()
    val subtitle = hit.resolvedSubtitle()
    val imageUrl = hit.resolvedImageUrl(apiUrl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(key)
            .tvAutoGlow(cornerRadiusDp = 10f, scale = 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Thumb(imageUrl, hit.type, accent)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayTitle,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = KobappTheme.type.bodySm,
                    color = colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StatusPill(status = PillStatus.InLibrary)
    }
}

@Composable
private fun DiscoverOverlayCard(item: DiscoverItem, accent: Color, key: String, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey(key)
            .tvAutoGlow(cornerRadiusDp = 10f, scale = 1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 42.dp, height = 60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.elev2),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val meta = when (item.mediaType) {
                    "book" -> item.authors.firstOrNull()
                    else -> item.year?.toString()
                }
                if (meta != null) {
                    Text(
                        meta,
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                        maxLines = 1,
                    )
                }
                if (item.rating != null) RatingBadge(item.rating)
            }
        }
        StatusPill(status = if (item.isInLibrary) PillStatus.InLibrary else PillStatus.NotInLibrary)
    }
}

@Composable
private fun Thumb(imageUrl: String?, type: String, accent: Color) {
    val colors = KobappTheme.colors
    if (imageUrl != null) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 42.dp, height = 60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.elev2),
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(lerp(colors.elev2, accent, 0.12f))
                .border(1.dp, lerp(Color.Transparent, accent, 0.25f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(typeIcon(type), null, tint = accent, modifier = Modifier.size(20.dp))
        }
    }
}

private fun sectionTitle(type: String): String = when (type) {
    "movie" -> "Movies"; "tv_show" -> "TV Shows"; "tv_season" -> "Seasons"; "tv_episode" -> "Episodes"
    "book" -> "Books"; "author" -> "Authors"; "series" -> "Series"; "person" -> "People"; "page" -> "Pages"
    else -> type.replaceFirstChar { it.uppercase() }
}

private fun typeIcon(type: String): ImageVector = when (type) {
    "movie" -> Icons.Outlined.Movie
    "tv_show", "tv_season" -> Icons.Outlined.Tv
    "tv_episode" -> Icons.Outlined.PlayCircle
    "book" -> Icons.AutoMirrored.Outlined.MenuBook
    "author", "person" -> Icons.Outlined.Person
    "series" -> Icons.Outlined.Collections
    else -> Icons.Outlined.Search
}

private fun typeAccent(type: String, colors: KobappColors): Color = when (type) {
    "movie" -> colors.accMovies
    "tv_show", "tv_season", "tv_episode" -> colors.accTv
    "book" -> colors.accBooks
    "author", "series" -> colors.accBooks.copy(alpha = 0.85f)
    "person" -> colors.accMovies.copy(alpha = 0.85f)
    else -> colors.textLo
}

private fun SearchHitDto.resolvedTitle(): String = when (type) {
    "tv_season" -> displayTitle ?: seasonIdentifier ?: title ?: label ?: name ?: id
    "tv_episode" -> displayTitle ?: buildString {
        episodeCode?.let { append(it) }
        episodeTitle?.let { if (isNotEmpty()) append(" "); append(it) }
    }.ifEmpty { null } ?: title ?: label ?: name ?: id
    else -> title ?: label ?: name ?: id
}

private fun SearchHitDto.resolvedSubtitle(): String? = when (type) {
    "movie" -> year?.toString()
    "tv_show" -> buildString {
        network?.let { append(it) }
        year?.let { if (isNotEmpty()) append(" • "); append(it) }
    }.ifEmpty { null }
    "tv_season" -> showTitle
    "tv_episode" -> showTitle
    "book" -> authors?.joinToString(", ")
    else -> null
}

private fun SearchHitDto.resolvedImageUrl(apiUrl: String): String? =
    (coverUrl ?: posterUrl ?: imageUrl ?: stillUrl ?: logoUrl)?.let { url ->
        if (url.startsWith("http")) url else "$apiUrl$url"
    }

private fun SearchHitDto.toSearchResult(apiUrl: String): SearchResult = SearchResult(
    type = type,
    id = id,
    title = resolvedTitle(),
    subtitle = resolvedSubtitle(),
    imageUrl = resolvedImageUrl(apiUrl),
)

private fun loadRecents(): List<String> =
    UserSettings.getString(RECENT_KEY)
        ?.split('\n')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
