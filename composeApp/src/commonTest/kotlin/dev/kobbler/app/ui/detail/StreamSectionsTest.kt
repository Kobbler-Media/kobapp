package dev.kobbler.app.ui.detail

import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamQuality
import dev.kobbler.app.data.StreamSearchResult
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

    @Test
    fun reportedCopiesAreGatheredUnderOneLastHeadingWhereverTheServerRankedThem() {
        val sections = streamSections(
            listOf(
                option("a", "played"),
                option("r1", "failed").copy(reportedBad = true),
                option("f", "failed"),
                option("r2", "failed").copy(reportedBad = true),
            ),
        )
        assertEquals(
            listOf("Played before" to listOf("a"), "Previously failed" to listOf("f"), "Reported bad" to listOf("r1", "r2")),
            sections.map { it.title to it.options.map(StreamOption::id) },
        )
    }

    @Test
    fun aReportMarksTheCopyInPlaceAndWithdrawingItClearsTheMark() {
        val found = StreamSearchResult(listOf(option("a", "played").copy(playback = "worked"), option("b", "available")), emptyList())
        val reported = found.withReport(found.options[0], bad = true)
        assertEquals(listOf(true to "failed", false to null), reported.options.map { it.reportedBad to it.playback })
        val withdrawn = reported.withReport(reported.options[0], bad = false)
        assertEquals(listOf(false to null, false to null), withdrawn.options.map { it.reportedBad to it.playback })
    }
}
