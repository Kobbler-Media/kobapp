package dev.kobbler.app.ui.player

// Containers tag tracks in ISO 639-2 (bibliographic or terminologic) or 639-1, and the
// preference is stored as 639-2/B, so equality has to go through one canonical form.
private val Aliases: Map<String, String> = listOf(
    listOf("en", "eng"),
    listOf("it", "ita"),
    listOf("es", "spa"),
    listOf("fr", "fre", "fra"),
    listOf("de", "ger", "deu"),
    listOf("pt", "por"),
    listOf("ja", "jpn"),
    listOf("ko", "kor"),
    listOf("zh", "chi", "zho"),
    listOf("ar", "ara"),
    listOf("nl", "dut", "nld"),
    listOf("pl", "pol"),
    listOf("tr", "tur"),
    listOf("sv", "swe"),
    listOf("no", "nor", "nb", "nob"),
    listOf("da", "dan"),
    listOf("fi", "fin"),
    listOf("ru", "rus"),
).flatMap { codes -> codes.map { it to codes.first() } }.toMap()

private fun canonicalLanguage(code: String): String = code.trim().lowercase().let { Aliases[it] ?: it }

internal fun sameLanguage(a: String?, b: String?): Boolean =
    !a.isNullOrBlank() && !b.isNullOrBlank() && canonicalLanguage(a) == canonicalLanguage(b)
