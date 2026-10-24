package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.DiscoverSearchResults
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.SearchHitDto
import dev.kobbler.app.ui.dashboard.livetv.LiveGuideSelection

/** One block of results, whichever side of the house it came from. */
sealed interface SearchSection {
    val order: Int

    /** The focus key of this section's first row, for handing focus to the top result. */
    val firstRowKey: String?

    data class Library(val type: String, val hits: List<SearchHitDto>) : SearchSection {
        override val order get() = libraryOrder(type)
        override val firstRowKey get() = hits.firstOrNull()?.let { "lib.${it.type}.${it.id}" }
    }

    data class Discover(val mediaType: String, val items: List<DiscoverItem>) : SearchSection {
        override val order get() = discoverOrder(mediaType)
        override val firstRowKey get() = items.firstOrNull()?.let { "disc.${it.provider}.${it.providerId}" }
    }

    data class LiveChannels(val channels: List<LiveChannel>) : SearchSection {
        override val order get() = 90
        override val firstRowKey get() = channels.firstOrNull()?.let { "live.ch.${it.id}" }
    }

    data class LiveAirings(val airings: List<LiveGuideSelection>) : SearchSection {
        override val order get() = 91
        override val firstRowKey get() = airings.firstOrNull()?.let { "live.air.${it.entry.id}" }
    }

    data class IptvChannels(val channels: List<IptvChannel>) : SearchSection {
        override val order get() = 92
        override val firstRowKey get() = channels.firstOrNull()?.let { "iptv.ch.${it.id}" }
    }
}

/**
 * Every section a query produced, in the order it is read.
 *
 * Discover sits directly under the library section it answers rather than after all of
 * them: a title the library does not have is the same kind of result as one it does, and
 * a house with a big book collection put forty rows of books, authors and series between
 * a search for a show and the show it was looking for.
 *
 * Discover titles the library already holds are dropped here too — the library row is the
 * better answer, and offering both reads as a duplicate rather than a choice.
 */
fun searchSections(
    libraryHits: List<SearchHitDto>,
    discover: DiscoverSearchResults?,
    live: LiveSearchResults? = null,
    iptv: List<IptvChannel>? = null,
): List<SearchSection> {
    val library = libraryHits.groupBy { it.type }
        .map { (type, hits) -> SearchSection.Library(type, hits) }
    val discovered = discover?.let {
        val libKeys = { type: String ->
            libraryHits.asSequence()
                .filter { hit -> hit.type == type }
                .map { hit -> libDedupeKey(hit.title ?: hit.label ?: hit.name, hit.year) }
                .toHashSet()
        }
        val movieKeys = libKeys("movie")
        val tvKeys = libKeys("tv_show")
        listOf(
            SearchSection.Discover("movie", it.movies.filterNot { m -> libDedupeKey(m.title, m.year) in movieKeys }),
            SearchSection.Discover("tv", it.tvShows.filterNot { t -> libDedupeKey(t.title, t.year) in tvKeys }),
            SearchSection.Discover("book", it.books),
        )
    } ?: emptyList()
    val onAir = live?.let { listOf(SearchSection.LiveChannels(it.channels), SearchSection.LiveAirings(it.airings)) } ?: emptyList()
    val streams = iptv?.let { listOf(SearchSection.IptvChannels(it)) } ?: emptyList()
    return (library + discovered + onAir + streams)
        .filter { section ->
            when (section) {
                is SearchSection.Library -> section.hits.isNotEmpty()
                is SearchSection.Discover -> section.items.isNotEmpty()
                is SearchSection.LiveChannels -> section.channels.isNotEmpty()
                is SearchSection.LiveAirings -> section.airings.isNotEmpty()
                is SearchSection.IptvChannels -> section.channels.isNotEmpty()
            }
        }
        .sortedBy { it.order }
}

private fun libraryOrder(type: String): Int = when (type) {
    "movie" -> 0; "tv_show" -> 10; "tv_season" -> 20; "tv_episode" -> 30
    "book" -> 40; "author" -> 50; "series" -> 60; "person" -> 70
    else -> 80
}

private fun discoverOrder(mediaType: String): Int = when (mediaType) {
    "movie" -> 1; "tv" -> 11; "book" -> 41; else -> 81
}

private val NonAlphanumeric = Regex("[^a-z0-9]+")

private fun libDedupeKey(title: String?, year: Int?): String {
    val normalized = (title ?: "").lowercase().trim()
        .replace(NonAlphanumeric, " ")
        .trim()
    return "$normalized|${year ?: ""}"
}
