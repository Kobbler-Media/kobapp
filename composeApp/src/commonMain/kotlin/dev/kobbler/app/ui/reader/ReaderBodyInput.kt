package dev.kobbler.app.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The reader body's touch surface, plus the reading cursor's band.
 *
 * Never a focus target and never framed: reading content is a mode the screen is in, not a
 * widget, so the D-pad is owned by the reader root ([readerKeyNav]) instead. The band is
 * the only thing ever painted over the prose, and only while the cursor is active.
 */
@Composable
internal fun ReaderBodyInput(
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onLongPressAt: ((Offset) -> Unit)? = null,
    cursor: ReaderCursorConfig? = null,
    cursorBlock: Int? = null,
    modifier: Modifier = Modifier,
) {
    val prevPage by rememberUpdatedState(onPrevPage)
    val nextPage by rememberUpdatedState(onNextPage)
    val longPress by rememberUpdatedState(onLongPressAt)
    val cfg by rememberUpdatedState(cursor)

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        if (pos.x < size.width / 3f) prevPage()
                        else if (pos.x > size.width * 2f / 3f) nextPage()
                    },
                    onLongPress = { pos -> longPress?.invoke(pos) },
                )
            }
            .drawBehind {
                val c = cfg ?: return@drawBehind
                val band = cursorBlock?.let { c.bounds[it] } ?: return@drawBehind
                val top = (band.first - c.scrollState.value).toFloat()
                val height = (band.last - band.first + 1).toFloat()
                if (top + height > 0f && top < size.height) {
                    drawRect(c.bandColor, topLeft = Offset(0f, top), size = Size(size.width, height))
                }
            }
    )
}
