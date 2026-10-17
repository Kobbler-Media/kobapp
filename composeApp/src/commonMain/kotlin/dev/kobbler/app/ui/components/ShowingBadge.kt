package dev.kobbler.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

const val ShownAsBackground = "Shown as background"

private val BadgeSize = 18.dp
private val BadgeInset = 6.dp

/**
 * Touch has no focus ring to say which card the page's picture comes from, so that card
 * carries a small image mark in its top-left corner. [state] is null where focus or hover already
 * says it, and then this adds nothing.
 *
 * Read at draw time through a derived state, so a change of picture redraws the two cards
 * involved rather than recomposing every card on the page.
 */
@Composable
fun Modifier.showingBadge(state: SpotlightState?, spot: Spotlight): Modifier {
    if (state == null) return this
    val showing by remember(state, spot) { derivedStateOf { state.shown == spot } }
    val painter = rememberVectorPainter(Icons.Outlined.Image)
    val colors = KobappTheme.colors
    return semantics { if (showing) stateDescription = ShownAsBackground }
        .drawWithContent {
            drawContent()
            if (!showing) return@drawWithContent
            val size = BadgeSize.toPx()
            val inset = BadgeInset.toPx()
            // Top-left: the top-right corner is where a card's library status sits.
            val corner = Offset(inset, inset)
            drawCircle(colors.bg.copy(alpha = 0.7f), radius = size * 0.75f, center = corner + Offset(size / 2, size / 2))
            translate(corner.x, corner.y) {
                with(painter) { draw(Size(size, size), colorFilter = ColorFilter.tint(colors.textHi)) }
            }
        }
}
