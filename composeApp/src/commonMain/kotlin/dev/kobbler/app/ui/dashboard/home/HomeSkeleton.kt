package dev.kobbler.app.ui.dashboard.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.PosterSkeleton
import dev.kobbler.app.ui.components.SectionHeadingSkeleton
import dev.kobbler.app.ui.components.ShimmerBox
import dev.kobbler.app.ui.components.SkeletonPage
import dev.kobbler.app.ui.components.SkeletonRail
import dev.kobbler.app.ui.components.pageGutter

/** Sized from the same rules as [HomePhone] and [HomeWide], so it lines up with the page that replaces it. */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    SkeletonPage("home", modifier) {
        if (LocalFormFactor.current == FormFactor.Phone) PhoneHomeSkeleton() else WideHomeSkeleton()
    }
}

@Composable
private fun PhoneHomeSkeleton() {
    val gutter = KobappTheme.dimens.pageGutterPhone
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val content = maxWidth - gutter * 2
        val heroHeight = minOf(content * (10f / 16f), maxHeight * 0.55f)
        val upNextWidth = minOf((content - UpNextCardGap) * 0.62f, UpNextCardMaxWidth)
        val posterWidth = railPosterWidth(content)
        // Rails run on to the screen's edge, as HomePhone's bleed does; only the hero keeps both gutters.
        Column(Modifier.padding(start = gutter)) {
            ShimmerBox(Modifier.padding(end = gutter).fillMaxWidth().height(heroHeight), cornerRadius = 14.dp)
            Spacer(Modifier.height(16.dp))
            SectionHeadingSkeleton()
            Spacer(Modifier.height(8.dp))
            SkeletonRail(UpNextCardGap) {
                repeat(3) { ShimmerBox(Modifier.width(upNextWidth).aspectRatio(16f / 9f), cornerRadius = 10.dp) }
            }
            Spacer(Modifier.height(16.dp))
            SectionHeadingSkeleton()
            Spacer(Modifier.height(10.dp))
            SkeletonRail(PhoneRailGap) {
                repeat(5) { PosterSkeleton(posterWidth) }
            }
        }
    }
}

@Composable
private fun WideHomeSkeleton() {
    val dimens = KobappTheme.dimens
    val gutter = pageGutter()
    val upNextWidth = dimens.posterWidth * 1.5f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // HomeWide gives the backdrop whatever Up Next and the first rail leave of the
        // screen; these are those two rows' heights, card plus heading and padding.
        val strip = 4.dp + 30.dp + 10.dp + upNextWidth / 1.5f + 16.dp
        val rail = 10.dp + 30.dp + 10.dp + dimens.posterWidth * 1.5f + 48.dp + 10.dp
        val hero = (maxHeight - strip - rail).coerceIn(HeroMinHeight, HeroMaxHeight)
        Column {
            Spacer(Modifier.height(hero + 4.dp))
            SectionHeadingSkeleton(Modifier.padding(start = gutter))
            Spacer(Modifier.height(10.dp))
            SkeletonRail(dimens.railGap, Modifier.padding(start = gutter)) {
                repeat(6) { ShimmerBox(Modifier.width(upNextWidth).aspectRatio(3f / 2f), cornerRadius = 10.dp) }
            }
            Spacer(Modifier.height(26.dp))
            SectionHeadingSkeleton(Modifier.padding(start = gutter))
            Spacer(Modifier.height(10.dp))
            SkeletonRail(dimens.railGap, Modifier.padding(start = gutter)) {
                repeat(12) { PosterSkeleton() }
            }
        }
    }
}
