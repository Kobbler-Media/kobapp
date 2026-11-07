package dev.kobbler.app.ui.offline

import dev.kobbler.app.ui.components.CardAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kobbler.app.offline.OfflineItem
import dev.kobbler.app.offline.OfflineKind
import dev.kobbler.app.offline.OfflineState
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.BrowseHeader
import dev.kobbler.app.ui.components.MediaCarousel
import dev.kobbler.app.ui.components.PosterCard
import dev.kobbler.app.ui.components.pageGutter
import dev.kobbler.app.ui.player.formatBytes
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.launch

/** One card on the page: a title, or a show standing for all of its downloaded episodes. */
private class OfflineCard(
    val key: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String,
    val items: List<OfflineItem>,
    val open: () -> Unit,
) {
    val complete: Boolean get() = items.all { it.state == OfflineState.Complete }
    val progress: Float?
        get() = if (complete) null else items.sumOf { it.progress.toDouble() }.toFloat() / items.size
}

private class OfflineRow(val title: String, val accent: Color, val cards: List<OfflineCard>)

/**
 * Everything on this device, a row per kind. The pages it opens are the ordinary detail
 * pages, drawn from what was kept with each download.
 */
@Composable
fun OfflineScreen(
    onOpenMovie: (String) -> Unit,
    onOpenShow: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = LocalOfflineEngine.current ?: return
    val index = rememberOfflineIndex() ?: return
    val online = rememberOnline()
    val colors = KobappTheme.colors
    val gutter = pageGutter()
    val scope = rememberCoroutineScope()
    val items = index.items.values.filter { !engine.kidsProfile || it.kidsAllowed }.sortedByDescending { it.addedAtMs }
    val (used, _) = rememberOfflineStorage(index.items.size to items.count { it.state == OfflineState.Complete })

    val rows = listOf(
        OfflineRow("Movies", colors.accMovies, items.filter { it.kind == OfflineKind.Movie }.map { it.card(it.title, it.subtitle) { onOpenMovie(it.mediaId) } }),
        OfflineRow(
            "TV shows",
            colors.accTv,
            items.filter { it.kind == OfflineKind.Episode }.groupBy { it.showId ?: it.mediaId }.map { (showId, episodes) ->
                val first = episodes.first()
                OfflineCard(
                    key = "show-$showId",
                    title = first.showTitle ?: first.title,
                    subtitle = if (episodes.size == 1) episodeLabel(first) else "${episodes.size} episodes",
                    posterUrl = first.showPosterUrl ?: first.posterUrl.orEmpty(),
                    items = episodes,
                    open = { onOpenShow(showId) },
                )
            },
        ),
        OfflineRow("Books", colors.accBooks, items.filter { it.kind == OfflineKind.Book && !it.isComic }.map { it.card(it.title, it.subtitle) { onOpenBook(it.mediaId) } }),
        OfflineRow("Comics", colors.accBooks, items.filter { it.kind == OfflineKind.Book && it.isComic }.map { it.card(it.title, it.subtitle) { onOpenBook(it.mediaId) } }),
        OfflineRow("Audiobooks", colors.accAudio, items.filter { it.kind == OfflineKind.Audiobook }.map { it.card(it.title, it.subtitle) { onOpenBook(it.mediaId) } }),
    ).filter { it.cards.isNotEmpty() }

    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(KobappTheme.dimens.s4)) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = gutter, vertical = KobappTheme.dimens.s4)) {
                BrowseHeader(
                    title = "Offline",
                    accent = colors.ok,
                    countLine = listOfNotNull(
                        "${items.size} ${if (items.size == 1) "title" else "titles"}".takeIf { items.isNotEmpty() },
                        formatBytes(used).takeIf { used > 0 },
                        items.count { it.state != OfflineState.Complete }.takeIf { it > 0 }?.let { "$it saving" },
                    ).joinToString(" · ").ifEmpty { null },
                )
                if (!online) {
                    Text(
                        "No connection to the server. Everything here still plays.",
                        style = KobappTheme.type.bodySm,
                        color = colors.textMd,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (rows.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing saved yet. Use Save offline on a movie, an episode, a book or an audiobook to keep it on this device.",
                        style = KobappTheme.type.body,
                        color = colors.textLo,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(rows, key = { it.title }) { row ->
            MediaCarousel(title = row.title, accentColor = row.accent, modifier = Modifier.padding(horizontal = gutter).focusKeyGroup("offline.${row.title}")) {
                items(row.cards, key = { it.key }) { card ->
                    PosterCard(
                        title = card.title,
                        imageUrl = card.posterUrl,
                        subtitle = if (card.complete) card.subtitle else card.items.firstOrNull { it.state != OfflineState.Complete }?.let(::stateLabel),
                        accentColor = row.accent,
                        progress = card.progress,
                        offline = card.complete,
                        contextMenuItems = buildList {
                            card.items.filter { it.state == OfflineState.Failed }.takeIf { it.isNotEmpty() }?.let { failed ->
                                add(CardAction("Try again", { failed.forEach { engine.retry(it.key) } }))
                            }
                            add(CardAction(if (card.items.size > 1) "Remove all ${card.items.size}" else "Remove from device", {
                                scope.launch { card.items.forEach { engine.remove(it.key) } }
                            }))
                        },
                        onClick = card.open,
                        modifier = Modifier.focusKey(card.key),
                    )
                }
            }
        }
    }
}

private fun OfflineItem.card(title: String, subtitle: String?, open: () -> Unit) =
    OfflineCard(key, title, subtitle, posterUrl.orEmpty(), listOf(this), open)

private fun episodeLabel(item: OfflineItem): String =
    listOfNotNull(
        item.seasonNumber?.let { s -> item.episodeNumber?.let { e -> "S$s · E$e" } },
        item.title,
    ).joinToString(" · ")
