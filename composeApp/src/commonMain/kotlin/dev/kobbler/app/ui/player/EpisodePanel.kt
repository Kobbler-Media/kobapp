package dev.kobbler.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.data.indexOfEpisode
import dev.kobbler.app.data.localToday
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.CenterFocusedItem
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.rememberArtTone
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow
import dev.kobbler.app.ui.detail.tvshow.EpisodeThumbnail
import dev.kobbler.app.ui.detail.tvshow.airDateLabel
import dev.kobbler.kobfocus.LocalFocusScope
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

private val PanelWidth = 460.dp
private val PhonePanelMaxWidth = 420.dp

/**
 * The show's episodes from inside the player, as a column against the right edge — the
 * same shape as [LiveChannelRail], on every form factor: a phone plays landscape, so a
 * bottom sheet would leave room for two rows with no art.
 */
@Composable
fun EpisodePanel(
    show: TvShow,
    currentEpisodeId: String?,
    accent: Color,
    formFactor: FormFactor,
    canStream: Boolean,
    modifier: Modifier = Modifier,
    onPick: (episodeId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val phone = formFactor == FormFactor.Phone
    val playingAt = remember(show, currentEpisodeId) { currentEpisodeId?.let(show.seasons::indexOfEpisode) }
    var seasonIdx by remember(show.id) { mutableIntStateOf(playingAt?.first ?: 0) }
    val season = show.seasons.getOrNull(seasonIdx)
    val artTone = rememberArtTone(show.backdropUrl ?: show.posterUrl, accent)
    val today = remember { localToday() }

    Column(
        modifier
            .then(if (phone) Modifier.fillMaxWidth(0.58f).widthIn(max = PhonePanelMaxWidth) else Modifier.width(PanelWidth))
            .fillMaxHeight()
            .background(colors.bg.copy(alpha = 0.96f))
            .focusKeyGroup("episodepanel")
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back, Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            }
            .padding(horizontal = if (phone) dimens.s4 else dimens.s5, vertical = dimens.s4),
        verticalArrangement = Arrangement.spacedBy(dimens.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Episodes", style = KobappTheme.type.h3, color = colors.textHi)
                Text(
                    show.title,
                    style = KobappTheme.type.caption,
                    color = colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A remote has Back; a phone in landscape has nothing on screen to close with.
            if (formFactor != FormFactor.TV) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, "Close episodes", tint = colors.textHi)
                }
            }
        }

        if (show.seasons.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(dimens.s2),
                contentPadding = PaddingValues(vertical = dimens.focusBleed),
            ) {
                itemsIndexed(show.seasons, key = { _, s -> s.id ?: s.number }) { idx, s ->
                    KobChip(
                        text = if (s.number == 0) "Specials" else "Season ${s.number}",
                        style = if (idx == seasonIdx) KobChipStyle.AccentTint else KobChipStyle.Default,
                        accent = accent,
                        onClick = { seasonIdx = idx },
                        modifier = Modifier.focusKey("season.${s.number}", entryEligible = false),
                    )
                }
            }
        }

        val episodes = season?.episodes.orEmpty()
        val currentIndex = episodes.indexOfFirst { it.id == currentEpisodeId }.takeIf { it >= 0 }
        val listState = rememberLazyListState()
        var focusedIndex by remember { mutableIntStateOf(-1) }
        CenterFocusedItem(listState, focusedIndex, topRow = 0)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // LEFT is the way back out to the transport, but only from the list: across
                // the season chips it is how a remote reaches an earlier season.
                .onPreviewKeyEvent { event ->
                    if (!phone && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                        onDismiss(); true
                    } else false
                },
        ) {
            val rowHeightGuess = if (phone) 120.dp else 140.dp
            val centreOffsetPx = with(LocalDensity.current) { ((maxHeight - rowHeightGuess) / 2).coerceAtLeast(0.dp).roundToPx() }
            // Placed before the first measure, so the panel does not visibly scroll down to
            // the episode being watched each time it opens.
            val placedFor = remember(listState) { mutableStateOf<Pair<Int, Int?>?>(null) }
            if (placedFor.value != (seasonIdx to currentIndex)) {
                placedFor.value = seasonIdx to currentIndex
                if (currentIndex != null) listState.requestScrollToItem(currentIndex, -centreOffsetPx)
                else listState.requestScrollToItem(0)
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(dimens.s2),
                contentPadding = PaddingValues(vertical = dimens.focusBleed),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(episodes, key = { idx, ep -> ep.id ?: "${season?.number}:$idx" }) { idx, ep ->
                    val current = ep.id != null && ep.id == currentEpisodeId
                    val airsSoon = ep.filePath == null && ep.airDate?.let { it > today } == true
                    val playable = ep.id != null && !current && !airsSoon && (ep.filePath != null || canStream)
                    EpisodePanelRow(
                        episode = ep,
                        accent = accent,
                        artTone = artTone,
                        current = current,
                        airsSoon = airsSoon,
                        airLabel = ep.airDate?.let { airDateLabel(it, today) },
                        compact = phone,
                        preferredEntry = idx == (currentIndex ?: 0),
                        onFocused = { focusedIndex = idx },
                        onClick = { if (playable) onPick(ep.id) },
                    )
                }
            }
        }

        val focusScope = LocalFocusScope.current
        LaunchedEffect(currentIndex) {
            val id = currentIndex?.let { episodes[it].id } ?: return@LaunchedEffect
            focusScope.restore("episodepanel.ep.$id")
        }
    }
}

@Composable
private fun EpisodePanelRow(
    episode: Episode,
    accent: Color,
    artTone: Color,
    current: Boolean,
    airsSoon: Boolean,
    airLabel: String?,
    compact: Boolean,
    preferredEntry: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .focusKey("ep.${episode.id ?: episode.number}", preferredEntry = preferredEntry)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .tvFocusGlow(focusState, cornerRadiusDp = 12f, scale = 1.01f)
            .clip(shape)
            .background(if (current) lerp(colors.elev2, accent, 0.16f) else colors.elev1)
            .border(if (current) 2.dp else 1.dp, if (current) accent else colors.lineSoft, shape)
            .tvClickable(focusState, onClick)
            .padding(10.dp),
    ) {
        EpisodeThumbnail(
            episode = episode,
            accent = accent,
            artTone = artTone,
            watched = episode.isWatched,
            airsSoon = airsSoon,
            offline = false,
            width = if (compact) 120.dp else 156.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    listOfNotNull(
                        "E${episode.number}",
                        airLabel,
                        episode.duration?.takeIf { !airsSoon },
                    ).joinToString(" · "),
                    style = KobappTheme.type.labelSm,
                    color = if (current) accent else colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (current) WatchingTag(accent)
            }
            Text(
                episode.title,
                style = KobappTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold),
                color = if (airsSoon) colors.textLo else colors.textHi,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episode.synopsis?.takeIf { !airsSoon }?.let {
                Text(
                    it,
                    style = KobappTheme.type.caption,
                    color = colors.textLo,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
