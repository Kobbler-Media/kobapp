package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.WatchHistoryEntry
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.ConfirmDialog
import dev.kobbler.app.ui.components.EmptyState
import dev.kobbler.app.ui.components.FadeInImage
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.KobBackHeader
import dev.kobbler.app.ui.components.ListSkeleton
import dev.kobbler.app.ui.components.SegmentedFilter
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.safeBottomPadding
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.launch

private val Sources = listOf("All" to null, "Library" to "local", "Trakt" to "trakt", "Stremio" to "stremio")
private const val PageSize = 100

private fun WatchHistoryEntry.key() = "$mediaType.${tmdbId ?: title}"

/**
 * Everything recommendations learn from, for clearing out what was never this viewer's:
 * an imported Trakt or Stremio history can carry a whole household. Hiding is reversible
 * and only stops a title seeding For You; removing clears the watch state itself.
 */
@Composable
fun WatchHistoryScreen(
    api: KobblerApi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = FormFactor.Desktop,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val accent = colors.accSet
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }
    val scope = rememberCoroutineScope()

    var sourceIdx by remember { mutableIntStateOf(0) }
    val entries = remember { mutableStateListOf<WatchHistoryEntry>() }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateListOf<String>() }
    var confirmRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(sourceIdx, reload) {
        loading = true
        failed = false
        selected.clear()
        try {
            val page = api.getWatchHistory(source = Sources[sourceIdx].second, limit = PageSize)
            entries.clear()
            entries.addAll(page.items)
            total = page.total
        } catch (_: Exception) {
            failed = true
        }
        loading = false
    }

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= entries.size - 10 && entries.size < total && !loading
        }
    }
    LaunchedEffect(nearEnd) {
        if (!nearEnd) return@LaunchedEffect
        loading = true
        try {
            entries.addAll(api.getWatchHistory(source = Sources[sourceIdx].second, limit = PageSize, offset = entries.size).items)
        } catch (_: Exception) {}
        loading = false
    }

    val chosen = entries.filter { it.key() in selected }
    val act: (suspend () -> Unit) -> Unit = { action ->
        busy = true
        scope.launch {
            try {
                action()
                reload++
            } catch (_: Exception) {}
            busy = false
        }
    }

    // Not a tab, so no app bar or bottom bar pads it clear of the system bars.
    Column(modifier.fillMaxSize().safeTopPadding().safeBottomPadding()) {
        val phone = formFactor == FormFactor.Phone
        KobBackHeader(onBack, formFactor, gutter) {
            BrowseHeader(
                title = if (phone) "Watched" else "Watch history",
                accent = accent,
                compact = phone,
                trailing = {
                    SegmentedFilter(
                        options = Sources.map { it.first },
                        selectedIndex = sourceIdx,
                        accent = accent,
                        onSelected = { sourceIdx = it },
                    )
                },
                secondaryRow = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        if (chosen.isNotEmpty()) {
                            Text("(${chosen.size})", color = colors.textLo, style = KobappTheme.type.label)
                        }
                        val allChosen = entries.isNotEmpty() && chosen.size == entries.size
                        KobButton(
                            onClick = {
                                selected.clear()
                                if (!allChosen) selected.addAll(entries.map { it.key() })
                            },
                            style = KobButtonStyle.Ghost,
                            modifier = Modifier.focusKey("select-all"),
                        ) { Text(if (allChosen) "Select none" else "Select all", style = KobappTheme.type.caption, maxLines = 1) }
                        if (chosen.isNotEmpty()) {
                            val unhide = chosen.all { it.excluded }
                            KobButton(
                                onClick = { act { api.setWatchHistoryExcluded(chosen, excluded = !unhide) } },
                                style = KobButtonStyle.Default,
                                modifier = Modifier.focusKey("hide"),
                            ) {
                                Text(
                                    when {
                                        phone -> if (unhide) "Unhide" else "Hide"
                                        unhide -> "Use for recommendations"
                                        else -> "Hide from recommendations"
                                    },
                                    style = KobappTheme.type.caption,
                                    maxLines = 1,
                                )
                            }
                            KobButton(
                                onClick = { confirmRemove = true },
                                style = KobButtonStyle.Ghost,
                                modifier = Modifier.focusKey("remove"),
                            ) {
                                Text(
                                    if (phone) "Remove" else "Remove from history",
                                    style = KobappTheme.type.caption,
                                    color = colors.err,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
            )
        }

        when {
            loading && entries.isEmpty() -> ListSkeleton()
            failed && entries.isEmpty() -> EmptyState(
                icon = Icons.Outlined.History,
                title = "Couldn't load watch history",
                subtitle = "Check the server and try again",
            )
            entries.isEmpty() -> EmptyState(
                icon = Icons.Outlined.History,
                title = "Nothing here",
                subtitle = "Titles you watch, and history imported from Trakt or Stremio, show up here",
            )
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = gutter, vertical = dimens.s2),
                verticalArrangement = Arrangement.spacedBy(dimens.s1),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.key() }) { entry ->
                    val key = entry.key()
                    HistoryRow(entry, checked = key in selected, enabled = !busy) {
                        if (key in selected) selected.remove(key) else selected.add(key)
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove ${chosen.size} from history?",
            body = "Their watched marks and resume points are cleared. Titles from Stremio can't be deleted here, so they are hidden from recommendations instead.",
            confirmLabel = "Remove",
            danger = true,
            onConfirm = {
                confirmRemove = false
                act { api.removeWatchHistory(chosen) }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun HistoryRow(entry: WatchHistoryEntry, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = KobappTheme.colors
    val focus = rememberTvFocusState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusKey("history.${entry.key()}")
            .tvFocusGlow(focus, cornerRadiusDp = 10f, scale = 1.01f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) colors.elev2 else colors.elev1)
            .tvClickable(focus) { if (enabled) onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) colors.accSet else colors.elev3)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Outlined.Check, null, tint = colors.bg, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(36.dp).height(54.dp).clip(RoundedCornerShape(4.dp)).background(colors.elev3)) {
            FadeInImage(model = entry.posterUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(entry.title, entry.year?.toString()).joinToString("  ·  "),
                style = KobappTheme.type.body,
                color = if (entry.excluded) colors.textLo else colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    if (entry.mediaType == "tv") "Show" else "Movie",
                    entry.sources.joinToString(", ") { it.replaceFirstChar(Char::titlecase) }.ifBlank { null },
                    entry.lastWatchedAt?.take(10),
                    "Hidden from recommendations".takeIf { entry.excluded },
                ).joinToString("  ·  "),
                style = KobappTheme.type.caption,
                color = colors.textLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
