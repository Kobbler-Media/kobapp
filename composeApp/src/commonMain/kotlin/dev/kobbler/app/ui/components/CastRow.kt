package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kobbler.app.data.CastMember
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

private const val MAX_CAST = 20

@Composable
fun CastRow(
    cast: List<CastMember>,
    modifier: Modifier = Modifier,
    onPersonClick: ((String) -> Unit)? = null,
    accent: Color = KobappTheme.accent,
    showHeading: Boolean = true,
) {
    if (cast.isEmpty()) return
    val items = cast.take(MAX_CAST)
    val scrollState = rememberLazyListState()
    // The row spans the page, so a search down into it from a full-width episode row
    // resolves geometrically and lands on whichever card sits nearest the middle. Enter
    // as a group instead and the first name always wins.
    val entry = remember { FocusRequester() }
    Column(
        modifier
            .focusKeyGroup("cast")
            // Guarded: the row is lazy, so after scrolling it the first card can be out
            // of composition when focus comes back, and requesting an unattached
            // requester throws — which would drop the enter entirely.
            .focusProperties { onEnter = { runCatching { entry.requestFocus() } } }
            .focusGroup(),
    ) {
        if (showHeading) {
            SectionHeading(title = "Cast", accent = accent)
            Spacer(Modifier.height(16.dp))
        }
        val bleed = rowEdgeBleed()
        FocusEdgeSlack {
            LazyRow(
                state = scrollState,
                modifier = Modifier.bleedHorizontally(bleed).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(horizontal = bleed),
            ) {
                items(items.size) { index ->
                    CastCard(
                        member = items[index],
                        accent = accent,
                        onClick = items[index].personId?.let { pid -> onPersonClick?.let { { it(pid) } } },
                        modifier = Modifier
                            .focusKey("member.$index")
                            .then(if (index == 0) Modifier.focusRequester(entry) else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
private fun CastCard(
    member: CastMember,
    accent: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    val focusState = rememberTvFocusState()
    Row(
        modifier = modifier
            .width(240.dp)
            .tvFocusGlow(focusState, cornerRadiusDp = 12f, scale = TvFocusScale.Standard)
            .onFocusChanged { focusState.isFocused = it.isFocused }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier.focusable()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(accent, colors.elev3)))
                .border(1.dp, colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (member.imageUrl != null) {
                AsyncImage(
                    model = member.imageUrl,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = member.name,
                    tint = colors.textLo,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                member.name,
                style = KobappTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.tvMarquee(focusState.isFocused),
            )
            if (member.character != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "as ${member.character}",
                    style = KobappTheme.type.bodySm,
                    color = colors.textLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.tvMarquee(focusState.isFocused),
                )
            }
        }
    }
}
