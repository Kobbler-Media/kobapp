package dev.kobbler.app.ui.dashboard

import dev.kobbler.app.data.DiscoverItem
import dev.kobbler.app.data.DiscoverSearchResults
import dev.kobbler.app.data.IptvChannel
import dev.kobbler.app.data.LiveChannel
import dev.kobbler.app.data.SearchHitDto
import dev.kobbler.app.ui.dashboard.livetv.LiveGuideSelection

/** One block of results, whichever side of the house it came from. */
sealed interface SearchSection {
    val group: SearchGroup
    val order: Int
    val size: Int
    val collapsible: Boolean
    val expandKey: String

    /** The focus key of this section's first row, for handing focus to the top result. */
    val firstRowKey: String?

    data class Library(val type: String, val hits: List<SearchHitDto>) : SearchSection {
        override val group get() = SearchGroup.Library
        override val order get() = libraryOrder(type)
        override val size get() = hits.size
        override val collapsible get() = true
        override val expandKey get() = "lib:$type"
        override val firstRowKey get() = hits.firstOrNull()?.let { "lib.${it.type}.${it.id}" }
    }

    data class Discover(val mediaType: String, val items: List<DiscoverItem>) : SearchSection {
        override val group get() = SearchGroup.Discover
        override val order get() = discoverOrder(mediaType)
        override val size get() = items.size
        override val collapsible get() = true
        override val expandKey get() = "disc:$mediaType"
        override val firstRowKey get() = items.firstOrNull()?.let { "disc.${it.provider}.${it.providerId}" }
    }

    data class LiveChannels(val channels: List<LiveChannel>) : SearchSection {
        override val group get() = SearchGroup.LiveTv
        override val order get() = 0
        override val size get() = channels.size
        override val collapsible get() = false
        override val expandKey get() = "live:channels"
        override val firstRowKey get() = channels.firstOrNull()?.let { "live.ch.${it.id}" }
    }

    data class LiveAirings(val airings: List<LiveGuideSelection>) : SearchSection {
        override val group get() = SearchGroup.LiveTv
        override val order get() = 1
        override val size get() = airings.size
        override val collapsible get() = true
        override val expandKey get() = "live:airings"
        override val firstRowKey get() = airings.firstOrNull()?.let { "live.air.${it.entry.id}" }
    }

    data class IptvChannels(val channels: List<IptvChannel>) : SearchSection {
        override val group get() = SearchGroup.Iptv
        override val order get() = 0
        override val size get() = channels.size
        override val collapsible get() = false
        override val expandKey get() = "iptv:channels"
        override val firstRowKey get() = channels.firstOrNull()?.let { "iptv.ch.${it.id}" }
    }
}

/** Ordered as they are read, which is also roughly the order they answer in. */
enum class SearchGroup(val label: String) { Library("Library"), Discover("Discover"), LiveTv("Live TV"), Iptv("IPTV") }

data class SearchJump(val group: SearchGroup, val itemIndex: Int, val focusKey: String?)

/** Items ahead of the first section; see the anchor in the results grid. */
internal const val SearchLeadingItems = 1

internal const val SearchSectionTopN = 5

fun SearchSection.visibleCount(expanded: Boolean): Int =
    if (collapsible && !expanded) minOf(size, SearchSectionTopN) else size

fun SearchSection.hasMoreRow(): Boolean = collapsible && size > SearchSectionTopN

/** Heading, rows, and the More row — exactly what the results grid emits for it. */
fun SearchSection.itemCount(expanded: Boolean): Int = 1 + visibleCount(expanded) + if (hasMoreRow()) 1 else 0

/** A jump to the first section of every group that answers below something else. */
fun searchJumps(sections: List<SearchSection>, expanded: Map<String, Boolean>): List<SearchJump> {
    var index = SearchLeadingItems
    val jumps = mutableListOf<SearchJump>()
    sections.forEachIndexed { i, section ->
        if (i > 0 && section.group != sections[i - 1].group) {
            jumps += SearchJump(section.group, index, section.firstRowKey)
        }
        index += section.itemCount(expanded[section.expandKey] == true)
    }
    return jumps
}

/**
 * Every section a query produced, in the order it is read: the library first, then each
 * slower source after it. A source that answers late must land below what is already on
 * screen, never above it; [searchJumps] keeps the later groups a press away.
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
        .sortedWith(compareBy({ it.group }, { it.order }))
}

private fun libraryOrder(type: String): Int = when (type) {
    "movie" -> 0; "tv_show" -> 10; "tv_season" -> 20; "tv_episode" -> 30
    "book" -> 40; "author" -> 50; "series" -> 60; "person" -> 70
    else -> 80
}

private fun discoverOrder(mediaType: String): Int = when (mediaType) {
    "movie" -> 0; "tv" -> 1; "book" -> 2; else -> 3
}

private val NonAlphanumeric = Regex("[^a-z0-9]+")

private fun libDedupeKey(title: String?, year: Int?): String {
    val normalized = (title ?: "").lowercase().trim()
        .replace(NonAlphanumeric, " ")
        .trim()
    return "$normalized|${year ?: ""}"
}
