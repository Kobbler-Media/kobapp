package dev.kobbler.app.ui.reader.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Container(
    val id: Int = 0,
    @SerialName("inset_start") val insetStart: Float = 0f,
    @SerialName("inset_end") val insetEnd: Float = 0f,
    @SerialName("space_top") val spaceTop: Float = 0f,
    @SerialName("space_bottom") val spaceBottom: Float = 0f,
    val top: BorderLine? = null,
    val right: BorderLine? = null,
    val bottom: BorderLine? = null,
    val left: BorderLine? = null,
    val background: String? = null,
    val radius: List<Float>? = null,
    val centred: Measure? = null,
    val inner: Float = 0f,
)

@Serializable
data class Borders(
    val top: BorderLine? = null,
    val right: BorderLine? = null,
    val bottom: BorderLine? = null,
    val left: BorderLine? = null,
)

/** A CSS width: in em, or as a fraction of the column it sits in. */
@Serializable
data class Measure(val em: Float? = null, val fraction: Float? = null)

@Serializable
data class BorderLine(
    val width: Float = 0f,
    val style: LineStyle = LineStyle.Solid,
    val color: String? = null,
)

@Serializable
enum class LineStyle {
    @SerialName("solid") Solid,
    @SerialName("dashed") Dashed,
    @SerialName("dotted") Dotted,
    @SerialName("double") Double,
}

@Serializable
data class RuleStyle(
    val line: BorderLine? = null,
    val length: Float? = null,
    val height: Float? = null,
    val image: RuleImage? = null,
)

@Serializable
data class RuleImage(val src: String, val width: Int? = null, val height: Int? = null, val repeat: Boolean = false)
