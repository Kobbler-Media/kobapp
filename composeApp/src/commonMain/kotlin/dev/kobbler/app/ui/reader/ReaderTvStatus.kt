package dev.kobbler.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.safeBottomPadding
import kotlinx.coroutines.delay

private const val KeyHintMs = 8_000L

internal fun readerProgressLabel(pages: Pair<Int, Int>, currentChapter: Int, totalChapters: Int): String {
    val (currentPage, totalPages) = pages
    val overallPercent = if (totalChapters > 0) {
        ((currentChapter.toFloat() + currentPage.toFloat() / totalPages.coerceAtLeast(1)) / totalChapters * 100).toInt()
    } else 0
    return "Ch ${currentChapter + 1}/$totalChapters  •  ${currentPage + 1}/$totalPages  •  $overallPercent%"
}

/**
 * What a TV page shows while its bars are hidden. A remote has nothing to hover or tap, so
 * without this the menu, chapters and read-aloud behind the arrow keys were undiscoverable.
 */
@Composable
internal fun ReaderTvStatus(
    pages: () -> Pair<Int, Int>,
    currentChapter: Int,
    totalChapters: Int,
    cursorActive: Boolean,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    var showKeys by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(KeyHintMs)
        showKeys = false
    }
    val dim = theme.textColor.copy(alpha = 0.45f)
    Column(
        modifier.safeBottomPadding().padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val hint = when {
            cursorActive -> "OK  read aloud from here  •  ▲ ▼  move  •  Back  cancel"
            showKeys -> "◀ ▶  turn pages  •  ▲  menu: contents, text size, read aloud  •  ▼  chapters  •  OK  pick a line"
            else -> null
        }
        AnimatedVisibility(hint != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                hint.orEmpty(),
                style = KobappTheme.type.caption.copy(color = theme.textColor.copy(alpha = 0.8f)),
                modifier = Modifier
                    .background(theme.backgroundColor.copy(alpha = 0.85f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Text(readerProgressLabel(pages(), currentChapter, totalChapters), style = KobappTheme.type.caption.copy(color = dim))
    }
}
