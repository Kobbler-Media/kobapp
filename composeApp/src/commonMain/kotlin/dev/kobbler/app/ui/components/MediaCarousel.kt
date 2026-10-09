package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.kobbler.app.theme.KobappColorsDefault
import dev.kobbler.app.theme.KobappTheme

private val DefaultMediaCarouselAccent: Color = KobappColorsDefault.textLo

@Composable
fun MediaCarousel(
    title: String,
    accentColor: Color = DefaultMediaCarouselAccent,
    badge: String? = null,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val spacing = KobappTheme.dimens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.s2, bottom = spacing.s4),
    ) {
        SectionHeading(
            title = title,
            accent = accentColor,
            seeAllLabel = if (onSeeAll != null) "see all →" else null,
            onSeeAll = onSeeAll,
            trailing = if (badge != null) {
                {
                    Text(
                        badge.uppercase(),
                        color = accentColor,
                        style = KobappTheme.type.label,
                    )
                    Spacer(Modifier.padding(end = spacing.s2))
                }
            } else null,
        )
        Spacer(Modifier.height(spacing.s2))
        val bleed = rowEdgeBleed()
        LazyRow(
            state = rememberLazyListState(),
            modifier = Modifier
                .bleedHorizontally(bleed)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = bleed),
            horizontalArrangement = Arrangement.spacedBy(spacing.s3),
            content = content,
        )
    }
}
