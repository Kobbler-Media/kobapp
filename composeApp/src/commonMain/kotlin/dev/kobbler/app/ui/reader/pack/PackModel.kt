package dev.kobbler.app.ui.reader.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val PackJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class Position(val chapter: Int = 0, val block: Int = 0, val offset: Int = 0)

@Serializable
data class BlockOffset(val block: Int = 0, val offset: Int = 0)

@Serializable
data class Manifest(
    val generation: Int = 0,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val language: String? = null,
    val rtl: Boolean = false,
    val chapters: List<ChapterEntry> = emptyList(),
    val toc: List<TocNode> = emptyList(),
    val styles: List<PackTextStyle> = emptyList(),
    val fonts: List<FontFace> = emptyList(),
)

@Serializable
data class ChapterEntry(
    val href: String,
    val title: String? = null,
    val weight: Long = 0,
    @SerialName("fixed_layout") val fixedLayout: FixedLayoutDto? = null,
)

@Serializable
data class FixedLayoutDto(val side: PageSide = PageSide.Auto, val spreads: Boolean = false)

@Serializable
enum class PageSide {
    @SerialName("auto") Auto,
    @SerialName("left") Left,
    @SerialName("right") Right,
    @SerialName("center") Center,
}

@Serializable
data class TocNode(
    val title: String = "",
    val target: Position = Position(),
    val children: List<TocNode> = emptyList(),
)

@Serializable
data class FontFace(
    val family: String,
    val weight: Int = 400,
    val italic: Boolean = false,
    val src: String,
)

@Serializable
data class PackChapter(
    val blocks: List<Block> = emptyList(),
    val anchors: Map<String, BlockOffset> = emptyMap(),
    val notes: Map<String, List<Block>> = emptyMap(),
)
