package dev.kobbler.app.ui.detail

import dev.kobbler.app.data.DiscoverDetail
import dev.kobbler.app.data.DiscoverEpisode
import dev.kobbler.app.data.DiscoverEpisodeRef
import dev.kobbler.app.data.Episode
import dev.kobbler.app.data.Movie
import dev.kobbler.app.data.Season
import dev.kobbler.app.data.TvShow
import dev.kobbler.app.data.formatRuntime
import dev.kobbler.app.data.parseIsoDate

internal fun DiscoverDetail.asMovie(): Movie = Movie(
    id = "discover:$provider:$providerId",
    title = title,
    year = year ?: 0,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    rating = rating,
    genres = genres,
    synopsis = description,
    duration = formatRuntime(runtime),
    runtimeMinutes = runtime,
    cast = cast,
    releaseDate = releaseDate,
)

private val DiscoverDetail.listedSeasons get() = seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }

internal fun DiscoverDetail.asTvShow(episodes: Map<Int, List<DiscoverEpisode>>): TvShow = TvShow(
    id = "discover:$provider:$providerId",
    title = title,
    year = year ?: 0,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    network = network,
    rating = rating,
    genres = genres,
    synopsis = description,
    cast = cast,
    seasons = listedSeasons.map { s ->
        Season(number = s.seasonNumber, episodes = episodes[s.seasonNumber].orEmpty().map { it.asEpisode() })
    },
)

private fun DiscoverEpisode.asEpisode() = Episode(
    number = episodeNumber,
    title = title ?: "Episode $episodeNumber",
    synopsis = description,
    thumbnailUrl = stillUrl,
    duration = formatRuntime(runtime),
    runtimeMinutes = runtime,
    airDate = parseIsoDate(airDate),
)

internal fun DiscoverDetail.episodeTotal(): Int = listedSeasons.sumOf { it.episodeCount ?: 0 }

internal fun DiscoverDetail.streamTarget(episodes: Map<Int, List<DiscoverEpisode>>): DiscoverEpisodeRef? {
    val season = listedSeasons.firstOrNull { it.episodeCount != 0 }
        ?: seasons.minByOrNull { it.seasonNumber }
        ?: return null
    val first = episodes[season.seasonNumber]?.minOfOrNull { it.episodeNumber } ?: 1
    return DiscoverEpisodeRef(season.seasonNumber, first)
}
