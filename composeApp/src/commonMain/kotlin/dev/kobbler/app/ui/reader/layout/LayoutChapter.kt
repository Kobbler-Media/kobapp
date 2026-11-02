package dev.kobbler.app.ui.reader.layout

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.kobbler.app.ui.reader.pack.Block
import dev.kobbler.app.ui.reader.pack.PackTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

suspend fun layoutChapter(
    blocks: List<Block>,
    styles: List<PackTextStyle>,
    typography: ReaderTypography,
    pageSize: IntSize,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): ChapterLayout = withContext(Dispatchers.Default) {
    // A shared TextMeasurer's cache is not thread-safe; this one is private to the call and uncached.
    val textMeasurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 0)
    val measurer = BlockMeasurer(styles, typography, pageSize, density, textMeasurer)
    val pages = Paginator(blocks, measurer, pageSize.height.toFloat()) { ensureActive() }.run()
    ChapterLayout(pages, pageSize)
}
