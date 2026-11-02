package dev.kobbler.app.ui.reader.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.kobbler.app.ui.reader.pack.FixedPageDto
import dev.kobbler.app.ui.reader.pack.PackTextStyle

// Fragment font sizes are page px from the book's stylesheet, not the reader's base size:
// the text must stay registered with the artwork as the box scales.
@Composable
internal fun FixedPageArt(
    fixed: FixedPageDto,
    styles: List<PackTextStyle>,
    typography: ReaderTypography,
    imageModel: (String) -> Any?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        AsyncImage(
            model = imageModel(fixed.image.src),
            contentDescription = fixed.image.alt,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        val boxWidth = maxWidth.value
        val boxHeight = maxHeight.value
        val scale = boxWidth / fixed.pageWidth
        Box(Modifier.matchParentSize()) {
            for (fragment in fixed.fragments) {
                val text = remember(fragment, styles, typography) {
                    buildPackText(fragment.spans, styles, typography, sized = false)
                }
                Text(
                    text = text,
                    style = TextStyle(
                        color = parseHexColor(fragment.color) ?: typography.theme.textColor,
                        fontSize = (fragment.fontSize * scale).sp,
                        lineHeight = (fragment.fontSize * scale * FragmentLineHeight).sp,
                        textAlign = fragment.align?.let(::textAlignOf) ?: TextAlign.Start,
                        fontFamily = typography.theme.fontFamily,
                    ),
                    modifier = Modifier
                        .offset(x = (fragment.left * boxWidth).dp, y = (fragment.top * boxHeight).dp)
                        .width((fragment.width * scale).dp),
                )
            }
        }
    }
}

private const val FragmentLineHeight = 1.2f
