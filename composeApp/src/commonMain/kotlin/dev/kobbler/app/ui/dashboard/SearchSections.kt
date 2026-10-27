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
    val title: String

    /** The focus key of this section's first row, for handing focus to the top result. */
    val firstRowKey: String?
    val firstTitle: String?

    fun slice(from: Int, until: Int = size): SearchSection

    /** [rank] is where the library's search placed this type's best hit among all of them. */
    data class Library(val type: String, val hits: List<SearchHitDto>, val rank: Int = 0) : SearchSection {
        override val group get() = SearchGroup.Library
        override val order get() = libraryOrder(type)
        override val size get() = hits.size
        override val collapsible get() = true
        override val expandKey get() = "lib:$type"
        override val title get() = sectionTitle(type)
        override val firstRowKey get() = hits.firstOrNull()?.let { "lib.${it.type}.${it.id}" }
        override val firstTitle get() = hits.firstOrNull()?.let { it.title ?: it.label ?: it.name }
        override fun slice(from: Int, until: Int) = copy(hits = hits.subList(from, until))
    }

    data class Discover(val mediaType: String, val items: List<DiscoverItem>) : SearchSection {
        override val group get() = SearchGroup.Discover
        override val order get() = discoverOrder(mediaType)
        override val size get() = items.size
        override val collapsible get() = true
        override val expandKey get() = "disc:$mediaType"
        override val title get() = when (mediaType) {
            "movie" -> "Discover · Movies"; "tv" -> "Discover · TV Shows"
            "book" -> "Discover · Books"; else -> "Discover · $mediaType"
        }
        override val firstRowKey get() = items.firstOrNull()?.let { "disc.${it.provider}.${it.providerId}" }
        override val firstTitle get() = items.firstOrNull()?.title
        override fun slice(from: Int, until: Int) = copy(items = items.subList(from, until))
    }

    data class LiveChannels(val channels: List<LiveChannel>) : SearchSection {
        override val group get() = SearchGroup.LiveTv
        override val order get() = 0
        override val size get() = channels.size
        override val collapsible get() = false
        override val expandKey get() = "live:channels"
        override val title get() = "Live TV · Channels"
        override val firstRowKey get() = channels.firstOrNull()?.let { "live.ch.${it.id}" }
        override val firstTitle get() = channels.firstOrNull()?.name
        override fun slice(from: Int, until: Int) = copy(channels = channels.subList(from, until))
    }

    data class LiveAirings(val airings: List<LiveGuideSelection>) : SearchSection {
        override val group get() = SearchGroup.LiveTv
        override val order get() = 1
        override val size get() = airings.size
        override val collapsible get() = true
        override val expandKey get() = "live:airings"
        override val title get() = "Live TV · On the guide"
        override val firstRowKey get() = airings.firstOrNull()?.let { "live.air.${it.entry.id}" }
        override val firstTitle get() = airings.firstOrNull()?.entry?.title
        override fun slice(from: Int, until: Int) = copy(airings = airings.subList(from, until))
    }

    data class IptvChannels(val channels: List<IptvChannel>) : SearchSection {
        override val group get() = SearchGroup.Iptv
        override val order get() = 0
        override val size get() = channels.size
        override val collapsible get() = false
        override val expandKey get() = "iptv:channels"
        override val title get() = "IPTV · Channels"
        override val firstRowKey get() = channels.firstOrNull()?.let { "iptv.ch.${it.id}" }
        override val firstTitle get() = channels.firstOrNull()?.name
        override fun slice(from: Int, until: Int) = copy(channels = channels.subList(from, until))
    }
}

/** Ordered as they are read, which is also roughly the order they answer in. */
enum class SearchGroup { Library, Discover, LiveTv, Iptv }

data class SearchJump(val label: String, val itemIndex: Int, val focusKey: String?)

/** The best row of every section, read before the sections that hold the rest. */
data class SearchLayout(val top: List<SearchSection>, val sections: List<SearchSection>) {
    val firstRowKey: String? get() = (top.firstOrNull() ?: sections.firstOrNull())?.firstRowKey
}

/**
 * Top results only once there is more than one section to choose between. Each source ranks
 * only its own answers, so across them a title the query names outright comes first, then
 * titles it starts; within that, the library's in the order its search ranked them.
 */
fun searchLayout(sections: List<SearchSection>, query: String = ""): SearchLayout {
    if (sections.size < 2) return SearchLayout(emptyList(), sections)
    val wanted = normalizeTitle(query)
    val top = sections
        .sortedWith(
            compareBy(
                { matchTier(normalizeTitle(it.firstTitle), wanted) },
                { it.group },
                { (it as? SearchSection.Library)?.rank ?: it.order },
            ),
        )
        .map { it.slice(0, 1) }
    return SearchLayout(top, sections.filter { it.size > 1 }.map { it.slice(1) })
}

/** Items ahead of the first section; see the anchor in the results grid. */
internal const val SearchLeadingItems = 1

internal const val SearchSectionTopN = 5

fun SearchSection.visibleCount(expanded: Boolean): Int =
    if (collapsible && !expanded) minOf(size, SearchSectionTopN) else size

fun SearchSection.hasMoreRow(): Boolean = collapsible && size > SearchSectionTopN

/** Heading, rows, and the More row — exactly what the results grid emits for it. */
fun SearchSection.itemCount(expanded: Boolean): Int = 1 + visibleCount(expanded) + if (hasMoreRow()) 1 else 0

fun SearchLayout.topItemCount(): Int = if (top.isEmpty()) 0 else 1 + top.size

/** A jump to every section under the top results. */
fun searchJumps(layout: SearchLayout, expanded: Map<String, Boolean>): List<SearchJump> {
    if (layout.top.isEmpty()) return emptyList()
    var index = SearchLeadingItems + layout.topItemCount()
    return layout.sections.map { section ->
        SearchJump(section.title, index, section.firstRowKey)
            .also { index += section.itemCount(expanded[section.expandKey] == true) }
    }
}

/**
 * Every section a query produced, in the order it is read: the library first, then each
 * slower source after it. A source that answers late must land below what is already on
 * screen, never above it; [searchJumps] keeps every section a press away.
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
    val library = libraryHits.groupBy { it.type }.entries
        .mapIndexed { rank, (type, hits) -> SearchSection.Library(type, hits, rank) }
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

private fun normalizeTitle(title: String?): String = (title ?: "").lowercase().replace(NonAlphanumeric, " ").trim()

private fun matchTier(title: String, query: String): Int = when {
    query.isEmpty() -> 0
    title == query -> 0
    title.startsWith(query) -> 1
    else -> 2
}

private fun libDedupeKey(title: String?, year: Int?): String = "${normalizeTitle(title)}|${year ?: ""}"
