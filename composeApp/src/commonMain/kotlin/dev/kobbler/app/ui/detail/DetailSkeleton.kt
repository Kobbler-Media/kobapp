package dev.kobbler.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.ui.components.ActionRowSkeleton
import dev.kobbler.app.ui.components.ChipRowSkeleton
import dev.kobbler.app.ui.components.ParagraphSkeleton
import dev.kobbler.app.ui.components.SectionHeadingSkeleton
import dev.kobbler.app.ui.components.ShimmerBox
import dev.kobbler.app.ui.components.SkeletonLine
import dev.kobbler.app.ui.components.SkeletonPage
import dev.kobbler.app.ui.components.BackButtonGutter
import dev.kobbler.app.ui.components.BackButtonSize
import dev.kobbler.app.ui.components.BackButtonTop
import dev.kobbler.app.ui.kobTopInset
import dev.kobbler.app.ui.safeTopPadding

/**
 * A title page — movie, show, book, person, author, series, discover — while it loads:
 * poster beside title, synopsis and actions; stacked under a hero on a phone.
 */
@Composable
fun DetailSkeleton(modifier: Modifier = Modifier) {
    val formFactor = LocalFormFactor.current
    val gutter = gutterFor(formFactor)
    SkeletonPage("detail", modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (detailStacked(formFactor, maxWidth)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = gutter, end = gutter, top = detailStackedTop, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        ShimmerBox(Modifier.width(110.dp).aspectRatio(2f / 3f), cornerRadius = 8.dp)
                        Column(Modifier.weight(1f).padding(bottom = 8.dp)) {
                            SkeletonLine(KobappTheme.type.bodySm, Modifier.fillMaxWidth(0.7f))
                            Spacer(Modifier.height(8.dp))
                            SkeletonLine(KobappTheme.displayTitle, Modifier.fillMaxWidth(0.9f))
                            SkeletonLine(KobappTheme.type.caption, Modifier.fillMaxWidth(0.4f))
                        }
                    }
                    Column(Modifier.padding(horizontal = gutter)) {
                        Spacer(Modifier.height(16.dp))
                        ChipRowSkeleton()
                        Spacer(Modifier.height(14.dp))
                        ActionRowSkeleton(icons = 3, fill = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(18.dp))
                        ParagraphSkeleton(lines = 4)
                        Spacer(Modifier.height(18.dp))
                        repeat(2) { SkeletonLine(KobappTheme.type.body, Modifier.fillMaxWidth(0.6f)) }
                        Spacer(Modifier.height(24.dp))
                        SectionHeadingSkeleton()
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            repeat(2) {
                                ShimmerBox(Modifier.size(76.dp), cornerRadius = 38.dp)
                                Column(Modifier.width(96.dp)) {
                                    SkeletonLine(KobappTheme.type.body, Modifier.fillMaxWidth())
                                    SkeletonLine(KobappTheme.type.bodySm, Modifier.fillMaxWidth(0.8f))
                                }
                            }
                        }
                    }
                }
                ShimmerBox(
                    Modifier.safeTopPadding().padding(start = BackButtonGutter(formFactor, gutter), top = BackButtonTop).size(BackButtonSize),
                    cornerRadius = BackButtonSize / 2,
                )
            } else {
                SkeletonLine(
                    KobappTheme.type.bodySm,
                    Modifier.safeTopPadding().padding(start = gutter, top = 14.dp).width(240.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = kobTopInset + detailWideTop(formFactor)),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                ) {
                    ShimmerBox(
                        Modifier
                            .width(
                                when (formFactor) {
                                    FormFactor.TV -> 200.dp
                                    FormFactor.Phone -> 160.dp
                                    FormFactor.Desktop -> 280.dp
                                },
                            )
                            .aspectRatio(2f / 3f),
                        cornerRadius = 8.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        SkeletonLine(KobappTheme.displayTitle, Modifier.fillMaxWidth(0.4f))
                        SkeletonLine(KobappTheme.type.caption, Modifier.fillMaxWidth(0.15f))
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            repeat(3) {
                                Column {
                                    SkeletonLine(KobappTheme.type.label, Modifier.width(64.dp))
                                    SkeletonLine(KobappTheme.type.body, Modifier.width(52.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        ParagraphSkeleton(lines = 4, Modifier.widthIn(max = 900.dp))
                        Spacer(Modifier.height(26.dp))
                        ActionRowSkeleton(icons = 3, fill = false)
                        Spacer(Modifier.height(20.dp))
                        ChipRowSkeleton()
                        Spacer(Modifier.height(20.dp))
                        repeat(2) { SkeletonLine(KobappTheme.type.bodySm, Modifier.width(240.dp)) }
                    }
                }
            }
        }
    }
}
