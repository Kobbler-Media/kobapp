package dev.kobbler.app.ui.reader.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PackTextStyle(
    val size: Float? = null,
    val weight: Int? = null,
    val italic: Boolean = false,
    @SerialName("small_caps") val smallCaps: Boolean = false,
    val family: String? = null,
    val align: TextAlignDto? = null,
    val indent: Float? = null,
    @SerialName("line_height") val lineHeight: Float? = null,
    @SerialName("letter_spacing") val letterSpacing: Float? = null,
    @SerialName("word_spacing") val wordSpacing: Float? = null,
    val color: String? = null,
    val transform: TextTransform? = null,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val baseline: Baseline? = null,
    val hyphens: Hyphenation? = null,
    val widows: Int? = null,
    val orphans: Int? = null,
    val dropcap: DropCap? = null,
)

@Serializable
data class DropCap(val lines: Int = 2, val chars: Int = 1)

@Serializable
enum class TextAlignDto {
    @SerialName("start") Start,
    @SerialName("end") End,
    @SerialName("left") Left,
    @SerialName("right") Right,
    @SerialName("center") Center,
    @SerialName("justify") Justify,
}

@Serializable
enum class TextTransform {
    @SerialName("uppercase") Uppercase,
    @SerialName("lowercase") Lowercase,
    @SerialName("capitalize") Capitalize,
}

@Serializable
enum class Baseline {
    @SerialName("super") Super,
    @SerialName("sub") Sub,
}

@Serializable
enum class Hyphenation {
    @SerialName("auto") Auto,
    @SerialName("manual") Manual,
    @SerialName("none") None,
}

fun List<PackTextStyle>.styleAt(index: Int): PackTextStyle = getOrNull(index) ?: DefaultPackStyle

private val DefaultPackStyle = PackTextStyle()
