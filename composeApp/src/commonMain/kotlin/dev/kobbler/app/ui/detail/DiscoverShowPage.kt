package dev.kobbler.app.ui.detail

import dev.kobbler.app.ui.components.rememberArtTone
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverEpisode
import dev.kobbler.app.data.DiscoverEpisodeRef
import dev.kobbler.app.data.KobblerApi
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.data.localToday
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.detail.tvshow.episodeList
import kotlinx.coroutines.CancellationException

/**
 * A show Discover found, on the library's show page. Nothing here has history: once a
 * stream has made the show a library row, the discover detail carries its libraryId and
 * the page hands over to the library's own, Resume and all.
 */
@Composable
internal fun DiscoverShowPage(
    detail: DiscoverDetail,
    api: KobblerApi,
    formFactor: FormFactor,
    accent: Color,
    onBack: () -> Unit,
    onStreamEpisode: ((DiscoverEpisodeRef) -> Unit)?,
    onStreamEpisodePick: ((DiscoverEpisodeRef) -> Unit)?,
    onWantSeason: ((Int) -> Unit)?,
    actions: @Composable (wide: Boolean, anchor: Modifier, exitDown: FocusRequester?, target: DiscoverEpisodeRef?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val episodes = remember(detail.providerId) { mutableStateMapOf<Int, List<DiscoverEpisode>>() }
    val show = detail.asTvShow(episodes)
    val target = detail.streamTarget(episodes)
    var selectedSeasonIdx by remember(detail.providerId) {
        mutableStateOf(show.seasons.indexOfFirst { it.number == target?.season }.coerceAtLeast(0))
    }
    val selectedNumber = show.seasons.getOrNull(selectedSeasonIdx)?.number
    var failed by remember(detail.providerId) { mutableStateOf(emptySet<Int>()) }

    LaunchedEffect(detail.providerId, selectedNumber) {
        val n = selectedNumber ?: return@LaunchedEffect
        if (n in episodes) return@LaunchedEffect
        try {
            episodes[n] = api.getDiscoverSeason(detail.provider, detail.providerId, n)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = failed + n
        }
    }

    val today = remember { localToday() }
    val noOverrides = remember { mutableStateMapOf<String, Boolean>() }
    val refAt: (Int, Int) -> DiscoverEpisodeRef? = { s, e ->
        show.seasons.getOrNull(s)?.let { season -> season.episodes.getOrNull(e)?.let { DiscoverEpisodeRef(season.number, it.number) } }
    }

    val artTone = rememberArtTone(show.backdropUrl ?: show.posterUrl, accent)
    ShowDetailLayout(
        show = show,
        episodeTotal = detail.episodeTotal(),
        formFactor = formFactor,
        accent = accent,
        selectedSeasonIdx = selectedSeasonIdx,
        onSeasonSelected = { selectedSeasonIdx = it },
        onBack = onBack,
        onPersonClick = null,
        modifier = modifier,
        actions = { wide, anchor, exitDown -> actions(wide, anchor, exitDown, target) },
        seasonMenu = { discoverSeasonMenu(selectedNumber, accent, onWantSeason) },
        episodes = { seasonEntry ->
            val n = selectedNumber
            when {
                n == null -> Unit
                n in failed || episodes[n]?.isEmpty() == true -> item {
                    Text(
                        "No episodes found",
                        style = KobappTheme.type.bodySm,
                        color = colors.textLo,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                n !in episodes -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
                else -> episodeList(
                    season = show.seasons[selectedSeasonIdx],
                    seasonIdx = selectedSeasonIdx,
                    accent = accent,
                    artTone = artTone,
                    currentEpisodeId = null,
                    api = null,
                    watchedOverrides = noOverrides,
                    wantOverrides = noOverrides,
                    onPlayEpisode = { s, e, _ -> refAt(s, e)?.let { onStreamEpisode?.invoke(it) } },
                    onStreamEpisode = onStreamEpisode?.let { stream -> { s, e -> refAt(s, e)?.let(stream) } },
                    onStreamEpisodePick = onStreamEpisodePick?.let { pick -> { s, e -> refAt(s, e)?.let(pick) } },
                    seasonEntry = seasonEntry,
                    // Nothing here is held, so being unplayable says nothing about airing.
                    isUpcoming = { ep -> ep.airDate?.let { it > today } ?: false },
                )
            }
        },
        topBarStatus = { DiscoverStatus(detail.libraryStatus) },
    )
}

@Composable
internal fun DiscoverStatus(status: LibraryStatus) {
    when (status) {
        LibraryStatus.NotInLibrary -> TopBarStatus("Not in library")
        LibraryStatus.Wanted -> TopBarStatus("Wanted", KobappTheme.colors.warn)
        LibraryStatus.InLibrary -> Unit
    }
}

private fun discoverSeasonMenu(season: Int?, accent: Color, onWantSeason: ((Int) -> Unit)?): SeasonMenu? {
    if (season == null || onWantSeason == null) return null
    return { close ->
        OverflowItem(
            key = "wantSeason",
            icon = Icons.Outlined.BookmarkAdd,
            label = "Want season $season",
            accent = accent,
            onClick = {
                close()
                onWantSeason(season)
            },
        )
    }
}
