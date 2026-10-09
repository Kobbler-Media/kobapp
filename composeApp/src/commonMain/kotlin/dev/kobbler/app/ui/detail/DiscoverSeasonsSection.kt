package dev.kobbler.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverEpisode
import dev.kobbler.app.ui.detail.tvshow.IconBtn
import dev.kobbler.app.data.DiscoverSeason
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup
import kotlinx.coroutines.launch

@Composable
internal fun DiscoverSeasonsSection(
    seasons: List<DiscoverSeason>,
    api: KobblerApi,
    detail: DiscoverDetail,
    accent: Color,
    canStream: Boolean,
    onStreamEpisode: (seasonNumber: Int, episodeNumber: Int) -> Unit,
    onStreamEpisodePick: (seasonNumber: Int, episodeNumber: Int) -> Unit,
) {
    val colors = KobappTheme.colors
    var expandedSeason by remember { mutableStateOf<Int?>(null) }
    val episodeCache = remember { mutableStateMapOf<Int, List<DiscoverEpisode>>() }
    var loadingSeason by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.focusKeyGroup("seasons"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "SEASONS",
            style = KobappTheme.type.bodySm.copy(letterSpacing = 0.12.em),
            color = colors.textLo,
        )
        Spacer(Modifier.height(4.dp))
        seasons.forEach { season ->
            val isExpanded = expandedSeason == season.seasonNumber
            val focusState = rememberTvFocusState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .focusKey("season.${season.seasonNumber}")
                    .tvFocusGlow(focusState, cornerRadiusDp = 10f, scale = 1.01f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isExpanded) colors.elev1 else Color.Transparent)
                    .tvClickable(focusState) {
                        if (isExpanded) expandedSeason = null
                        else {
                            expandedSeason = season.seasonNumber
                            if (season.seasonNumber !in episodeCache) {
                                loadingSeason = season.seasonNumber
                                scope.launch {
                                    try {
                                        episodeCache[season.seasonNumber] =
                                            api.getDiscoverSeason(detail.provider, detail.providerId, season.seasonNumber)
                                    } catch (_: Exception) {
                                        episodeCache[season.seasonNumber] = emptyList()
                                    }
                                    loadingSeason = null
                                }
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = colors.textMd,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    season.title ?: "Season ${season.seasonNumber}",
                    style = KobappTheme.type.body.copy(fontWeight = FontWeight.Medium),
                    color = colors.textHi,
                    modifier = Modifier.weight(1f),
                )
                if (season.episodeCount != null) {
                    Text(
                        "${season.episodeCount} episodes",
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                    )
                }
            }
            AnimatedVisibility(isExpanded) {
                Column(
                    Modifier
                        .focusKeyGroup("s${season.seasonNumber}")
                        .padding(start = 24.dp, top = 4.dp, bottom = 8.dp),
                ) {
                    if (loadingSeason == season.seasonNumber) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = accent,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        val episodes = episodeCache[season.seasonNumber] ?: emptyList()
                        if (episodes.isEmpty()) {
                            Text(
                                "No episodes found",
                                style = KobappTheme.type.bodySm,
                                color = colors.textLo,
                                modifier = Modifier.padding(12.dp),
                            )
                        } else {
                            episodes.forEach { ep ->
                                DiscoverEpisodeRow(
                                    episode = ep,
                                    accent = accent,
                                    canStream = canStream,
                                    onStream = { onStreamEpisode(season.seasonNumber, ep.episodeNumber) },
                                    onStreamPick = { onStreamEpisodePick(season.seasonNumber, ep.episodeNumber) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverEpisodeRow(
    episode: DiscoverEpisode,
    accent: Color,
    canStream: Boolean,
    onStream: () -> Unit,
    onStreamPick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    // Body and options are sibling focus targets under a plain group — a focusable row
    // would swallow the D-pad press aimed at the button. See EpisodeRowFocusTest.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().focusKeyGroup("episode.${episode.episodeNumber}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
                .then(if (canStream) Modifier.focusKey("play") else Modifier)
                .tvFocusGlow(focusState, cornerRadiusDp = 6f, scale = 1.01f)
                .clip(RoundedCornerShape(6.dp))
                .then(if (canStream) Modifier.tvClickable(focusState, onStream) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                "${episode.episodeNumber}",
                style = KobappTheme.type.bodySm,
                color = colors.textLo,
                maxLines = 1,
                modifier = Modifier.width(40.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title ?: "Episode ${episode.episodeNumber}",
                    style = KobappTheme.type.bodySm,
                    color = colors.textHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.airDate != null) {
                    Text(
                        episode.airDate,
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                    )
                }
            }
            if (canStream) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "Stream",
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (canStream) {
            IconBtn(
                icon = Icons.Outlined.Tune,
                description = "Stream options",
                focusKey = "streamopts",
                tint = colors.textLo,
                onClick = onStreamPick,
            )
        }
    }
}
