package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.safeTopPadding

const val SkeletonTag = "skeleton"

/** A page-sized skeleton: the screen's background, a shared shimmer clock, and a tag tests can find. */
@Composable
fun SkeletonPage(name: String, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    ShimmerHost {
        Box(
            modifier
                .fillMaxSize()
                .background(KobappTheme.colors.bg)
                .clipToBounds()
                .testTag("$SkeletonTag.$name"),
        ) { content() }
    }
}

/** One line of [style]'s text: as tall as the line, the bar as tall as its glyphs. */
@Composable
fun SkeletonLine(style: TextStyle, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val line = with(density) { (if (style.lineHeight.isSp) style.lineHeight else style.fontSize * 1.3f).toDp() }
    val glyphs = with(density) { style.fontSize.toDp() } * 0.8f
    Box(modifier.height(line), contentAlignment = Alignment.CenterStart) {
        ShimmerBox(Modifier.fillMaxWidth().height(glyphs))
    }
}

@Composable
fun PosterSkeleton(width: Dp = KobappTheme.dimens.posterWidth) {
    Column(Modifier.width(width)) {
        ShimmerBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f), cornerRadius = 8.dp)
        Spacer(Modifier.height(8.dp))
        SkeletonLine(KobappTheme.type.bodySm, Modifier.fillMaxWidth(0.8f))
        SkeletonLine(KobappTheme.type.caption, Modifier.fillMaxWidth(0.45f))
    }
}

/** A row that runs off the edge like a real rail, rather than squeezing its last card. */
@Composable
fun SkeletonRail(gap: Dp, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().clipToBounds().wrapContentWidth(Alignment.Start, unbounded = true),
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

@Composable
fun SectionHeadingSkeleton(modifier: Modifier = Modifier) {
    SkeletonLine(KobappTheme.type.h3, modifier.width(150.dp))
}

/** Pill and round buttons at the detail pages' PrimaryAction and IconAction sizes. */
@Composable
fun ActionRowSkeleton(icons: Int, fill: Boolean, modifier: Modifier = Modifier) {
    val height = KobappTheme.dimens.minTouchTarget
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (fill) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ShimmerBox((if (fill) Modifier.weight(1f) else Modifier.width(120.dp)).height(height + 4.dp), cornerRadius = 24.dp)
        repeat(icons) { ShimmerBox(Modifier.size(width = 52.dp, height = height), cornerRadius = 24.dp) }
    }
}

/** A row of genre chips. */
@Composable
fun ChipRowSkeleton(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(76.dp, 58.dp, 64.dp).forEach { ShimmerBox(Modifier.size(width = it, height = 26.dp), cornerRadius = 13.dp) }
    }
}

@Composable
fun ParagraphSkeleton(lines: Int, modifier: Modifier = Modifier) {
    val widths = listOf(1f, 0.96f, 0.99f, 0.93f, 0.98f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(lines) { i ->
            SkeletonLine(KobappTheme.type.body, Modifier.fillMaxWidth(if (i == lines - 1) 0.55f else widths[i % widths.size]))
        }
    }
}

/** The grid half of a browse screen; also the whole of the screens that are only a grid. */
@Composable
fun PosterGridSkeleton(modifier: Modifier = Modifier, endPadding: Dp = pageGutter()) {
    val dimens = KobappTheme.dimens
    FlowRow(
        modifier
            .fillMaxWidth()
            .padding(start = pageGutter(), end = endPadding, top = dimens.s4)
            .wrapContentHeight(Alignment.Top, unbounded = true),
        horizontalArrangement = Arrangement.spacedBy(dimens.s3),
        verticalArrangement = Arrangement.spacedBy(dimens.s4),
    ) {
        repeat(24) { PosterSkeleton() }
    }
}

/** Movies, TV, Books: [BrowseHeader] with Categories, a rail, then the grid and its A–Z bar. */
@Composable
fun BrowseSkeleton(modifier: Modifier = Modifier) {
    val dimens = KobappTheme.dimens
    val compact = LocalFormFactor.current == FormFactor.Phone
    val gutter = pageGutter()
    val end = if (compact) gutter else gutter + AlphabetBarWidth
    SkeletonPage("browse", modifier) {
        Column(Modifier.fillMaxSize().padding(top = dimens.s4)) {
            Row(
                Modifier.fillMaxWidth().padding(start = gutter, end = end),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 20.dp),
            ) {
                SkeletonLine(if (compact) KobappTheme.type.h2 else KobappTheme.type.h1, Modifier.width(if (compact) 110.dp else 150.dp))
                SkeletonLine(if (compact) KobappTheme.type.caption else KobappTheme.type.body, Modifier.width(100.dp))
                Spacer(Modifier.weight(1f))
                SkeletonLine(KobappTheme.type.bodySm, Modifier.padding(end = 16.dp).width(96.dp))
            }
            Spacer(Modifier.height(dimens.s4 * 3))
            SectionHeadingSkeleton(Modifier.padding(start = gutter))
            Spacer(Modifier.height(10.dp))
            SkeletonRail(dimens.s3, Modifier.padding(start = gutter)) { repeat(10) { PosterSkeleton() } }
            PosterGridSkeleton(endPadding = end)
        }
        if (!compact) {
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(AlphabetBarWidth).padding(vertical = dimens.s3),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(26) { ShimmerBox(Modifier.size(10.dp), cornerRadius = 2.dp) }
            }
        }
    }
}

/** Notifications, activity, recordings, guides: a title and rows of thumbnail plus two lines. */
@Composable
fun ListSkeleton(modifier: Modifier = Modifier, title: Boolean = true) {
    val gutter = pageGutter()
    SkeletonPage("list", modifier) {
        Column(
            Modifier.fillMaxWidth().safeTopPadding().padding(horizontal = gutter, vertical = KobappTheme.dimens.s5),
            verticalArrangement = Arrangement.spacedBy(KobappTheme.dimens.s3),
        ) {
            if (title) {
                SkeletonLine(KobappTheme.type.h1, Modifier.width(180.dp))
                Spacer(Modifier.height(KobappTheme.dimens.s2))
            }
            repeat(12) { ListRowSkeleton() }
        }
    }
}

@Composable
private fun ListRowSkeleton() {
    Row(
        Modifier.fillMaxWidth().height(KobappTheme.dimens.rowHeight + 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KobappTheme.dimens.s3),
    ) {
        ShimmerBox(Modifier.size(width = 96.dp, height = 54.dp), cornerRadius = 6.dp)
        Column(Modifier.weight(1f)) {
            SkeletonLine(KobappTheme.type.body, Modifier.fillMaxWidth(0.5f))
            SkeletonLine(KobappTheme.type.caption, Modifier.fillMaxWidth(0.3f))
        }
    }
}

/** A live channel or recording: back, logo, name and Watch across the top, then the guide. */
@Composable
fun ChannelSkeleton(modifier: Modifier = Modifier) {
    val gutter = pageGutter()
    val dimens = KobappTheme.dimens
    SkeletonPage("channel", modifier) {
        Column(
            Modifier.fillMaxWidth().safeTopPadding().padding(horizontal = gutter, vertical = dimens.s4),
            verticalArrangement = Arrangement.spacedBy(dimens.s4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.s4)) {
                ShimmerBox(Modifier.size(40.dp), cornerRadius = 20.dp)
                ShimmerBox(Modifier.size(64.dp), cornerRadius = 10.dp)
                Column(Modifier.weight(1f)) {
                    SkeletonLine(KobappTheme.type.h1, Modifier.fillMaxWidth(0.4f))
                    SkeletonLine(KobappTheme.type.bodySm, Modifier.fillMaxWidth(0.3f))
                }
                ShimmerBox(Modifier.size(width = 120.dp, height = 48.dp), cornerRadius = 24.dp)
            }
            repeat(10) { ListRowSkeleton() }
        }
    }
}

/** The audiobook player: cover, title, scrubber, transport. */
@Composable
fun PlayerSkeleton(modifier: Modifier = Modifier) {
    SkeletonPage("player", modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().safeTopPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            val cover = minOf(maxWidth * 0.7f, maxHeight * 0.45f, 320.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
                ShimmerBox(Modifier.size(cover), cornerRadius = 14.dp)
                Spacer(Modifier.height(24.dp))
                SkeletonLine(KobappTheme.type.h2, Modifier.width(cover * 0.8f))
                SkeletonLine(KobappTheme.type.body, Modifier.width(cover * 0.5f))
                Spacer(Modifier.height(28.dp))
                ShimmerBox(Modifier.fillMaxWidth().height(4.dp), cornerRadius = 2.dp)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShimmerBox(Modifier.size(44.dp), cornerRadius = 22.dp)
                    ShimmerBox(Modifier.size(68.dp), cornerRadius = 34.dp)
                    ShimmerBox(Modifier.size(44.dp), cornerRadius = 22.dp)
                }
            }
        }
    }
}

/** A page of a book. */
@Composable
fun ReaderSkeleton(modifier: Modifier = Modifier) {
    SkeletonPage("reader", modifier) {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 680.dp).fillMaxWidth().safeTopPadding().padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            repeat(4) { ParagraphSkeleton(lines = 6) }
        }
    }
}

/** A screen that is only a poster grid, under a header its screen draws itself. */
@Composable
fun GridSkeleton(modifier: Modifier = Modifier) {
    SkeletonPage("grid", modifier) { PosterGridSkeleton() }
}

/** A comic page, centred at the reader's fit-to-height. */
@Composable
fun ComicSkeleton(modifier: Modifier = Modifier) {
    SkeletonPage("comic", modifier) {
        ShimmerBox(Modifier.align(Alignment.Center).fillMaxHeight().padding(vertical = 24.dp).aspectRatio(2f / 3f), cornerRadius = 4.dp)
    }
}
