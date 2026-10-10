package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.HomeData
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.RecentlyAddedItem
import dev.kobbler.app.data.UpNextItem
import dev.kobbler.app.data.toUpNextItem
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.PosterCard
import dev.kobbler.app.ui.components.WideCard
import dev.kobbler.app.ui.components.SectionHeading
import dev.kobbler.app.ui.components.StripedArt
import dev.kobbler.app.ui.components.bleedHorizontally

@Composable
fun HomePhone(
    homeData: HomeData,
    api: KobblerApi,
    onContinueClick: (Int) -> Unit,
    onRecentClick: (RecentlyAddedItem) -> Unit,
    onDiscoverItemClick: (DiscoverItem) -> Unit,
) {
    val colors = KobappTheme.colors
    val gutter = KobappTheme.dimens.pageGutterPhone
    val upNext = remember(homeData) { homeData.watchNext.map { it.toUpNextItem() } }
    val hero = upNext.firstOrNull()
    val stack = upNext.drop(1)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Turned sideways the page is twice as wide and half as tall: a 16:10 hero across
        // the full width would be taller than the screen.
        val heroHeight = minOf((maxWidth - gutter * 2) * (10f / 16f), maxHeight * 0.55f)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 0.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (hero != null) {
                item(key = "hero") {
                    PhoneHero(item = hero, height = heroHeight, onClick = { onContinueClick(0) })
                }
            }

            if (stack.isNotEmpty()) {
                item(key = "upnext-strip") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Up Next",
                            color = colors.textHi,
                            style = KobappTheme.type.h3,
                        )
                        // One horizontal axis, bounded height: a vertical list grew with the
                        // queue, and a second axis inside a vertically scrolling page makes the
                        // swipe ambiguous. The trailing peek is what advertises the rest.
                        BoxWithConstraints {
                            // Continue watching earns the loudest treatment after the hero, so
                            // these are backdrop cards rather than the compact list rows. Just
                            // under two fit, which puts the next card's edge permanently on
                            // screen — that peek is what says "swipe".
                            val cardWidth = minOf((maxWidth - UpNextCardGap) * 0.62f, UpNextCardMaxWidth)
                            LazyRow(
                                modifier = Modifier.bleedHorizontally(gutter).fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = gutter),
                                horizontalArrangement = Arrangement.spacedBy(UpNextCardGap),
                            ) {
                                itemsIndexed(stack) { i, item ->
                                    WideCard(
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        imageUrl = item.mediaArtUrl.orEmpty(),
                                        backdropUrl = item.backdropUrl,
                                        progress = item.progress ?: 0f,
                                        accentColor = colors.accentForMediaType(item.mediaType),
                                        onClick = { onContinueClick(i + 1) },
                                        modifier = Modifier.width(cardWidth),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (homeData.recentlyAdded.isNotEmpty()) {
                item(key = "recent") {
                    PhoneRail(
                        title = "Recently Added",
                        items = homeData.recentlyAdded,
                    ) { item, cardWidth ->
                        PosterCard(
                            title = item.title,
                            imageUrl = item.imageUrl,
                            subtitle = item.subtitle,
                            accentColor = colors.accentForRecentlyAdded(item.itemType),
                            onClick = { onRecentClick(item) },
                            width = cardWidth,
                        )
                    }
                }
            }

            homeData.sections.forEachIndexed { i, section ->
                item(key = "section-$i") {
                    val sectionAccent = colors.accentForSection(section.sectionKey)
                    val wantScope = rememberCoroutineScope()
                    PhoneRail(title = section.title, items = section.items) { item, cardWidth ->
                        // buildList hands back a fresh List every call, and List compares by
                        // reference under strong skipping — inline, no card in any rail can
                        // ever skip.
                        val menu = remember(item, api) { discoverContextMenu(item, api, wantScope, onDiscoverItemClick) }
                        PosterCard(
                            title = item.title,
                            imageUrl = item.posterUrl,
                            subtitle = when (item.mediaType) {
                                "book" -> item.authors.firstOrNull() ?: ""
                                else -> item.year?.toString() ?: ""
                            },
                            accentColor = sectionAccent,
                            statusBadge = item.libraryStatus,
                            contextMenuItems = menu,
                            onClick = { onDiscoverItemClick(item) },
                            width = cardWidth,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneHero(item: UpNextItem, height: Dp, onClick: () -> Unit) {
    val colors = KobappTheme.colors
    val accent = colors.accentForMediaType(item.mediaType)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(KobappTheme.shapes.hero)
            .border(1.dp, colors.lineSoft, KobappTheme.shapes.hero)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
    ) {
        StripedArt(item.backdropUrl, accent, Modifier.fillMaxSize(), contentDescription = item.title)
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.30f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.70f),
                    ),
                ),
            ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                item.title,
                color = Color.White,
                style = KobappTheme.type.h2.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.subtitle,
                    color = Color.White.copy(alpha = 0.78f),
                    style = KobappTheme.type.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.progress != null) {
                Spacer(Modifier.height(10.dp))
                ProgressLine(item.progress, accent)
            }
        }
    }
}

@Composable
private fun <T> PhoneRail(
    title: String,
    items: List<T>,
    card: @Composable (T, Dp) -> Unit,
) {
    val gutter = KobappTheme.dimens.pageGutterPhone
    Column {
        SectionHeading(title = title, accent = null)
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints {
            val cardWidth = railPosterWidth(maxWidth)
            LazyRow(
                modifier = Modifier.bleedHorizontally(gutter).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = gutter),
                horizontalArrangement = Arrangement.spacedBy(RailGap),
            ) {
                items(items) { card(it, cardWidth) }
            }
        }
    }
}

private val RailGap = 10.dp
private val RailPeek = 26.dp
private val RailPosterTarget = 120.dp

// At least three cards and the edge of a fourth: an exact three-up row reads as a
// complete set and nobody thinks to swipe. A wider screen adds cards rather than
// growing them.
private fun railPosterWidth(available: Dp): Dp {
    val count = maxOf(3, ((available - RailPeek + RailGap) / (RailPosterTarget + RailGap)).toInt())
    return (available - RailGap * (count - 1) - RailPeek) / count
}
