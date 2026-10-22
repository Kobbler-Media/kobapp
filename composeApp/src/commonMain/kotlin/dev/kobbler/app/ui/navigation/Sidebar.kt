package dev.kobbler.app.ui.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.Profile
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.Section
import dev.kobbler.app.theme.accentFor
import dev.kobbler.app.ui.auth.ProfileAvatar

data class SidebarItem(
    val section: Section,
    val label: String,
    val icon: ImageVector,
    val badgeCount: Int = 0,
)

private val RailPadStart = 10.dp
private val RailPadEnd = 10.dp

/**
 * A fixed slot, as wide as the collapsed rail's content area, that a row's glyph is
 * centred in.
 *
 * The rail is always laid out at its expanded width and only the window onto it
 * animates, so a glyph keeps one x for the whole expand/collapse — nothing is
 * re-measured or nudged. The slot is what puts that x on the collapsed rail's centre
 * line: insetting each row by (railCentre - glyph/2) instead centres the icon's *box*
 * but not its artwork, and a material icon does not fill its own box evenly — which
 * left every section icon a few dp left of the brand mark above it.
 */
@Composable
private fun RailGlyph(collapsedWidth: Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.width(collapsedWidth - RailPadStart - RailPadEnd),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun KobSidebar(
    items: List<SidebarItem>,
    active: Section,
    expanded: Boolean,
    tvMode: Boolean = false,
    profile: Profile? = null,
    searchSelected: Boolean = false,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    modifier: Modifier = Modifier,
    onSelect: (Section) -> Unit = {},
    onSearchClick: (() -> Unit)? = null,
    onProfileClick: () -> Unit = {},
    onSettingsClick: (() -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val collapsedWidth = if (tvMode) dimens.sidebarTv else dimens.sidebarCollapsed
    val expandedWidth = dimens.sidebarExpanded
    val width = animateDpAsState(
        if (expanded) expandedWidth else collapsedWidth,
        animationSpec = tween(260),
    )
    // How far open the rail is, measured rather than re-animated so the labels fade
    // exactly in step with the edge that is uncovering them. Read only in layout and
    // draw: the width moves every frame of the slide.
    val reveal = remember(width, collapsedWidth, expandedWidth) {
        { ((width.value - collapsedWidth) / (expandedWidth - collapsedWidth)).coerceIn(0f, 1f) }
    }
    val revealed by remember(reveal) { derivedStateOf { reveal() > 0f } }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.elev1)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(0.dp))
            // Insets are consumed inside the surface but outside the width, so an edge
            // cutout widens the rail instead of squeezing its rows.
            .windowInsetsPadding(windowInsets)
            .layout { measurable, constraints ->
                val w = constraints.constrainWidth(width.value.roundToPx())
                val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                layout(w, placeable.height) { placeable.place(0, 0) }
            }
            .clipToBounds(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(expandedWidth)
                .padding(start = RailPadStart, end = RailPadEnd, top = 20.dp, bottom = 14.dp),
        ) {
            val brandSize = if (tvMode) 32.dp else 28.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 18.dp),
            ) {
                RailGlyph(collapsedWidth) {
                    Box(
                        Modifier.requiredSize(brandSize).clip(RoundedCornerShape(7.dp))
                            .background(colors.accHome),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("K", color = colors.bg, style = KobappTheme.type.h3)
                    }
                }
                Text(
                    "kobapp",
                    color = colors.textHi,
                    style = KobappTheme.type.reader.copy(fontSize = KobappTheme.type.h3.fontSize),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 12.dp).graphicsLayer { alpha = reveal() },
                )
            }

            if (onSearchClick != null) {
                SidebarRow(
                    label = "Search",
                    icon = Icons.Outlined.Search,
                    accent = colors.accHome,
                    isActive = searchSelected,
                    reveal = reveal,
                    collapsedWidth = collapsedWidth,
                    tvMode = tvMode,
                    onClick = onSearchClick,
                )
                Spacer(Modifier.height(8.dp))
            }

            // The rail is taller than a 1080p screen once every tab is listed, and its rows
            // are not focus targets (the Zone state machine drives selection), so nothing
            // brings the active row into view on its own.
            val listState = rememberLazyListState()
            val activeIdx = items.indexOfFirst { it.section == active }
            LaunchedEffect(activeIdx) {
                if (activeIdx < 0) return@LaunchedEffect
                val info = listState.layoutInfo
                val visible = info.visibleItemsInfo
                if (visible.isEmpty()) {
                    listState.scrollToItem(activeIdx)
                    return@LaunchedEffect
                }
                val viewport = info.viewportEndOffset - info.viewportStartOffset
                val row = visible.last()
                when {
                    activeIdx <= visible.first().index -> listState.animateScrollToItem(activeIdx)
                    // Land it against the bottom edge so stepping down moves one row, not a page.
                    activeIdx >= row.index -> listState.animateScrollToItem(activeIdx, -(viewport - row.size))
                }
            }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(items, key = { it.section }) { item ->
                    SidebarRow(
                        label = item.label,
                        icon = item.icon,
                        accent = colors.accentFor(item.section),
                        badgeCount = item.badgeCount,
                        isActive = item.section == active && !searchSelected,
                        reveal = reveal,
                        collapsedWidth = collapsedWidth,
                        tvMode = tvMode,
                        onClick = { onSelect(item.section) },
                    )
                }
            }

            // Profile foot — separator + avatar (+ name/sublabel once the rail is open).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.lineSoft),
            )
            val avatar = if (tvMode) 36.dp else 32.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clickable(onClick = onProfileClick)
                    .padding(top = 4.dp, bottom = 4.dp),
            ) {
                RailGlyph(collapsedWidth) {
                    if (profile != null) {
                        ProfileAvatar(profile, size = avatar, modifier = Modifier.requiredSize(avatar))
                    } else {
                        Box(
                            Modifier.requiredSize(avatar).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(colors.accBooks, colors.accAudio))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("K", color = colors.bg, style = KobappTheme.type.bodySm)
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp).graphicsLayer { alpha = reveal() }) {
                    Text(
                        profile?.displayName ?: "—",
                        color = colors.textHi,
                        style = KobappTheme.type.bodySm,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                        softWrap = false,
                    )
                    val subLabel = profile?.email
                    if (subLabel != null) Text(
                        subLabel,
                        color = colors.textLo,
                        style = KobappTheme.type.caption,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                        softWrap = false,
                    )
                }
                // Only while the rail is open — collapsed there is no room beside the
                // avatar, and the row itself already carries the same action.
                if (onSettingsClick != null && revealed) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer { alpha = reveal() }
                            .clip(CircleShape)
                            .clickable(onClick = onSettingsClick)
                            .padding(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Settings,
                            "Settings",
                            // Settings has no row of its own here, so the cog carries the
                            // active state for it.
                            tint = if (active == Section.Settings) colors.accSet else colors.textMd,
                            modifier = Modifier.requiredSize(if (tvMode) 24.dp else 20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarRow(
    label: String,
    icon: ImageVector,
    accent: Color,
    isActive: Boolean,
    reveal: () -> Float,
    collapsedWidth: Dp,
    tvMode: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    val colors = KobappTheme.colors
    val iconSize = if (tvMode) 24.dp else 22.dp

    // Box wrapper so the active marker can sit absolutely positioned at the
    // sidebar's left edge without displacing the icon (design uses left: -10
    // relative to the row).
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (isActive) lerp(Color.Transparent, accent, 0.14f) else Color.Transparent)
                .clickable(onClick = onClick)
                // Vertical only. The rail's width is clamped by the animating window, so
                // a trailing pad here comes straight out of the leading glyph slot while
                // it is narrow — which walked every icon left of the brand mark above.
                // The label carries its own trailing space instead.
                .padding(top = 10.dp, bottom = 10.dp),
        ) {
            RailGlyph(collapsedWidth) {
                Icon(
                    icon, label,
                    tint = if (isActive) accent else colors.textMd,
                    modifier = Modifier.requiredSize(iconSize),
                )
            }
            Row(
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp).graphicsLayer { alpha = reveal() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = if (isActive) accent else colors.textMd,
                    style = if (tvMode) KobappTheme.type.body else KobappTheme.type.bodySm,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
                if (badgeCount > 0) {
                    Box(
                        Modifier.clip(KobappTheme.shapes.pill).background(accent)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(badgeCount.toString(), color = colors.bg, style = KobappTheme.type.caption)
                    }
                }
            }
        }
        if (isActive) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-6).dp)
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
        }
    }
}
