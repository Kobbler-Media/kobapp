package dev.kobbler.app.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.fontSize(text: String): TextUnit {
    val results = mutableListOf<TextLayoutResult>()
    onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    return results.single().layoutInput.style.fontSize
}
