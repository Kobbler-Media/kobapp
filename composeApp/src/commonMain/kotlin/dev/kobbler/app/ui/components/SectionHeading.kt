package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.focusKey

/**
 * `● Title` heading: title text, optional trailing, and an accent square only where the
 * section has a colour of its own (a library page) — on a mixed row it means nothing.
 */
@Composable
fun SectionHeading(
    title: String,
    accent: Color?,
    modifier: Modifier = Modifier,
    seeAllLabel: String? = null,
    onSeeAll: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(14.dp))
        }
        // Weighted, so the count/see-all beside it measure first and keep their own
        // width. Unweighted the title took the whole row and left them nothing.
        Text(
            title,
            color = colors.textHi,
            style = KobappTheme.type.h3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
        if (seeAllLabel != null && onSeeAll != null) {
            Text(
                seeAllLabel,
                color = colors.textLo,
                style = KobappTheme.type.caption,
                modifier = Modifier
                    .focusKey("see-all")
                    .tvAutoGlow(cornerRadiusDp = 6f, scale = TvFocusScale.Subtle)
                    .clip(RoundedCornerShape(6.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}
