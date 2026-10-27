package dev.kobbler.app.ui.detail

import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamSectionsTest {

    private fun option(id: String, group: String?) = StreamOption(
        id = id, releaseTitle = id, size = 0, indexer = "", infoHash = "h-$id",
        quality = StreamQuality(null, null, null, null), qualityJson = null,
        totalScore = 0.0, debridProvider = "realdebrid", debridProviderId = "p1",
        cached = false, sourceType = "torrent", fileId = null, publishDate = null,
        group = group,
    )

    @Test
    fun sectionsFollowTheServerOrderUnderOneHeadingEach() {
        val sections = streamSections(
            listOf(
                option("a", "played"),
                option("b", "account"),
                option("c", "account"),
                option("d", "available"),
                option("e", "uncached"),
                option("f", "failed"),
            ),
        )
        assertEquals(
            listOf(
                "Played before" to listOf("a"),
                "On your debrid account" to listOf("b", "c"),
                "Available" to listOf("d"),
                "Needs downloading" to listOf("e"),
                "Previously failed" to listOf("f"),
            ),
            sections.map { it.title to it.options.map(StreamOption::id) },
        )
    }

    @Test
    fun ungroupedOptionsFromAnOlderServerStayOneUntitledList() {
        val sections = streamSections(listOf(option("a", null), option("b", null)))
        assertEquals(
            listOf<Pair<String?, List<String>>>(null to listOf("a", "b")),
            sections.map { it.title to it.options.map(StreamOption::id) },
        )
    }
}
