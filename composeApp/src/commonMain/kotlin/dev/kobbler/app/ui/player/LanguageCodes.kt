package dev.kobbler.app.ui.player

import dev.kobbler.app.data.AudioTrackDto

// Containers tag tracks in ISO 639-2 (bibliographic or terminologic) or 639-1, and the
// preference is stored as 639-2/B, so equality has to go through one canonical form.
private val Aliases: Map<String, String> = listOf(
    listOf("en", "eng", "english"),
    listOf("it", "ita", "italian"),
    listOf("es", "spa", "spanish"),
    listOf("fr", "fre", "fra", "french"),
    listOf("de", "ger", "deu", "german"),
    listOf("pt", "por", "portuguese"),
    listOf("ja", "jpn", "japanese"),
    listOf("ko", "kor", "korean"),
    listOf("zh", "chi", "zho", "chinese"),
    listOf("ar", "ara", "arabic"),
    listOf("nl", "dut", "nld", "dutch"),
    listOf("pl", "pol", "polish"),
    listOf("tr", "tur", "turkish"),
    listOf("sv", "swe", "swedish"),
    listOf("no", "nor", "nb", "nob", "norwegian"),
    listOf("da", "dan", "danish"),
    listOf("fi", "fin", "finnish"),
    listOf("ru", "rus", "russian"),
).flatMap { codes -> codes.map { it to codes.first() } }.toMap()

private fun canonicalLanguage(code: String): String = code.trim().lowercase().let { Aliases[it] ?: it }

internal fun sameLanguage(a: String?, b: String?): Boolean =
    !a.isNullOrBlank() && !b.isNullOrBlank() && canonicalLanguage(a) == canonicalLanguage(b)

private val UntaggedCodes = setOf("und", "unk", "mis", "zxx")
private val TitleWordBreak = Regex("[^a-z0-9]+")

// Muxers often leave the tag empty and name the language only in the title ("AC3 [English]").
internal fun AudioTrackDto.speaks(preferred: String?): Boolean {
    val tag = language?.trim()?.lowercase()
    if (tag.isNullOrEmpty() || tag in UntaggedCodes) {
        val words = title?.lowercase()?.split(TitleWordBreak).orEmpty()
        return words.any { it.length > 2 && sameLanguage(it, preferred) }
    }
    return sameLanguage(tag, preferred)
}

private val CommentaryMarkers = listOf("commentary", "commentaries", "audio description", "descriptive", "described video")

internal val AudioTrackDto.isCommentary: Boolean
    get() = title?.lowercase()?.let { t -> CommentaryMarkers.any { it in t } } ?: false
