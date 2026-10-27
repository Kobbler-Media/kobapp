package dev.kobbler.app.ui.detail

import dev.kobbler.app.data.StreamOption

internal data class StreamSection(val title: String?, val options: List<StreamOption>)

private fun groupTitle(group: String?): String? = when (group) {
    "played" -> "Played before"
    "account" -> "On your debrid account"
    "available" -> "Available"
    "uncached" -> "Needs downloading"
    "failed" -> "Previously failed"
    else -> null
}

/** Consecutive runs of one group, in the server's order; the server ranks by group first. */
internal fun streamSections(options: List<StreamOption>): List<StreamSection> {
    val sections = mutableListOf<StreamSection>()
    for (option in options) {
        val title = groupTitle(option.group)
        val last = sections.lastOrNull()
        if (last != null && last.title == title) {
            sections[sections.lastIndex] = last.copy(options = last.options + option)
        } else {
            sections += StreamSection(title, listOf(option))
        }
    }
    return sections
}
