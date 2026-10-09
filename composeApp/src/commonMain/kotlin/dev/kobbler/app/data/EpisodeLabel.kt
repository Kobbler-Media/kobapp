package dev.kobbler.app.data

// Sports events carry a synthetic identifier instead of SxxEyy: "evt-r{round}-{session}"
// when the round is known, "evt-{providerId}" otherwise. Only the first form means
// anything to a viewer; the second is an opaque id and is dropped.
private const val EVENT_PREFIX = "evt-"
private val ROUND_SESSION = Regex("""^r(\d+)-(\S+)$""")

fun formatEpisodeLabel(identifier: String, episodeTitle: String?): String {
    val label = if (identifier.startsWith(EVENT_PREFIX)) eventLabel(identifier) else identifier
    val title = episodeTitle?.trim().orEmpty()
    return when {
        label.isEmpty() -> title
        title.isEmpty() -> label
        else -> "$label · $title"
    }
}

private fun eventLabel(identifier: String): String {
    val match = ROUND_SESSION.matchEntire(identifier.removePrefix(EVENT_PREFIX)) ?: return ""
    val session = match.groupValues[2]
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    return "R${match.groupValues[1]} $session"
}
