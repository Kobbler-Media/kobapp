package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.graphics.Color
import dev.kobbler.app.ui.reader.pack.TextTransform
import kotlin.test.Test
import kotlin.test.assertEquals

class PackTextTest {

    @Test
    fun `text transforms never change the utf16 length`() {
        val cases = listOf(
            Triple("straße ŉ ǰ", TextTransform.Uppercase, "STRAßE ŉ ǰ"),
            Triple("İSTANBUL", TextTransform.Lowercase, "istanbul"),
            Triple("the 😀 old-man", TextTransform.Capitalize, "The 😀 Old-man"),
            Triple("same", null, "same"),
        )
        for ((input, transform, expected) in cases) {
            val out = transformed(input, transform)
            assertEquals(input.length, out.length, "$transform $input")
            assertEquals(expected, out, "$transform $input")
        }
    }

    @Test
    fun `hex colors parse in every css hex form`() {
        val cases = listOf(
            "#333333" to Color(0xFF333333),
            "#abc" to Color(0xFFAABBCC),
            "#ff000080" to Color(0x80FF0000),
            "#f008" to Color(0x88FF0000),
            "red" to null,
            null to null,
        )
        for ((input, expected) in cases) assertEquals(expected, parseHexColor(input), "$input")
    }
}
