package dev.kobbler.app.ui.detail

import dev.kobbler.app.data.StreamOption
import dev.kobbler.app.data.StreamSearchResult
import dev.kobbler.app.ui.player.PLAYBACK_FAILED

internal data class StreamSection(val title: String?, val options: List<StreamOption>)

private fun groupTitle(group: String?): String? = when (group) {
    "played" -> "Played before"
    "available" -> "Available"
    "uncached" -> "Needs downloading"
    "failed" -> "Previously failed"
    else -> null
}

private const val REPORTED_TITLE = "Reported bad"

/**
 * Consecutive runs of one group, in the server's order; the server ranks by group first.
 * Copies the viewer reported are gathered last, wherever they ranked among the failed.
 */
internal fun streamSections(options: List<StreamOption>): List<StreamSection> {
    val (reported, rest) = options.partition { it.reportedBad }
    val sections = mutableListOf<StreamSection>()
    for (option in rest) {
        val title = groupTitle(option.group)
        val last = sections.lastOrNull()
        if (last != null && last.title == title) {
            sections[sections.lastIndex] = last.copy(options = last.options + option)
        } else {
            sections += StreamSection(title, listOf(option))
        }
    }
    if (reported.isNotEmpty()) sections += StreamSection(REPORTED_TITLE, reported)
    return sections
}

internal fun StreamSearchResult.withReport(option: StreamOption, bad: Boolean): StreamSearchResult = copy(
    options = options.map {
        if (it.id != option.id) it
        else it.copy(reportedBad = bad, playback = PLAYBACK_FAILED.takeIf { bad }, group = it.group.takeUnless { g -> !bad && g == "failed" })
    },
)
