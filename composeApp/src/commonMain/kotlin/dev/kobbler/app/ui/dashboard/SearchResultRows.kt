package dev.kobbler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.SearchHitDto
import dev.kobbler.app.data.SearchResult
import dev.kobbler.app.theme.KobappColors
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.PillStatus
import dev.kobbler.app.ui.components.StatusPill
import dev.kobbler.app.ui.components.tvAutoGlow
import dev.kobbler.app.ui.detail.RatingBadge
import dev.kobbler.app.ui.detail.ratingOutOf
import dev.kobbler.kobfocus.focusKey

@Composable
internal fun SearchResultRow(
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
        Thumb(imageUrl, hit.sectionType, accent)
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
internal fun DiscoverOverlayCard(item: DiscoverItem, accent: Color, key: String, onClick: () -> Unit) {
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
                if (item.rating != null) RatingBadge(item.rating, ratingOutOf(item.mediaType))
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

internal fun sectionTitle(type: String): String = when (type) {
    "movie" -> "Movies"; "tv_show" -> "TV Shows"; "sport" -> "Sports"; "tv_season" -> "Seasons"; "tv_episode" -> "Episodes"
    "book" -> "Books"; "author" -> "Authors"; "series" -> "Series"; "person" -> "People"
    else -> type.replaceFirstChar { it.uppercase() }
}

private fun typeIcon(type: String): ImageVector = when (type) {
    "movie" -> Icons.Outlined.Movie
    "tv_show", "tv_season" -> Icons.Outlined.Tv
    "sport" -> Icons.Outlined.SportsScore
    "tv_episode" -> Icons.Outlined.PlayCircle
    "book" -> Icons.AutoMirrored.Outlined.MenuBook
    "author", "person" -> Icons.Outlined.Person
    "series" -> Icons.Outlined.Collections
    else -> Icons.Outlined.Search
}

internal fun typeAccent(type: String, colors: KobappColors): Color = when (type) {
    "movie" -> colors.accMovies
    "tv_show", "tv_season", "tv_episode" -> colors.accTv
    "sport" -> colors.accSports
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

internal fun SearchHitDto.toSearchResult(apiUrl: String): SearchResult = SearchResult(
    type = type,
    id = id,
    showId = showId,
    title = resolvedTitle(),
    subtitle = resolvedSubtitle(),
    imageUrl = resolvedImageUrl(apiUrl),
)
