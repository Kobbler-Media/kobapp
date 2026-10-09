package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import dev.kobbler.app.data.Book
import dev.kobbler.app.theme.KobappTheme

@Composable
fun ReadingProgressPopover(
    book: Book,
    progress: Float = 0f,
    lastOpened: String? = null,
    onDismiss: () -> Unit,
) {
    val colors = KobappTheme.colors
    val accent = colors.accBooks
    val pct = (progress.coerceIn(0f, 1f) * 100).toInt()

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Row(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.elev1)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 64.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.elev2),
                contentAlignment = Alignment.Center,
            ) {
                if (!book.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    color = colors.textHi,
                    style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        book.author,
                        color = colors.textLo,
                        style = KobappTheme.type.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = accent,
                    trackColor = colors.elev2,
                )
                Spacer(Modifier.height(6.dp))
                val statusLine = buildString {
                    append(if (progress > 0f) "$pct% read" else "Not started")
                    if (!lastOpened.isNullOrBlank()) append(" · $lastOpened")
                }
                Text(
                    statusLine,
                    color = colors.textLo,
                    style = KobappTheme.type.bodySm,
                )
            }
        }
    }
}
