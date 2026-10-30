package dev.kobbler.app.ui.components

import dev.kobbler.app.ui.offline.OfflineBadge

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.LibraryStatus
import dev.kobbler.app.theme.KobappTheme

// Shared instances, not per-composition copies: background() caches its shader against
// the Brush identity, so a fresh equal instance still rebuilds the shader.
private val BottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
private val MenuShape = RoundedCornerShape(8.dp)
private val CoverShape = RoundedCornerShape(4.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String,
    imageUrl: String,
    subtitle: String? = null,
    accentColor: Color? = null,
    statusBadge: LibraryStatus? = null,
    badge: String? = null,
    progress: Float? = null,
    contextMenuItems: List<Pair<String, () -> Unit>> = emptyList(),
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    width: Dp? = null,
    offline: Boolean = false,
) {
    val dimens = KobappTheme.dimens
    val colors = KobappTheme.colors
    val acc = accentColor ?: KobappTheme.accent
    val shape = KobappTheme.shapes.poster
    var showMenu by remember { mutableStateOf(false) }
    val focus = rememberTvFocusState()
    val placeholder = remember(colors.elev2, acc) {
        Brush.linearGradient(listOf(lerp(colors.elev2, acc, 0.30f), colors.elev2))
    }

    Column(
        // The focus scale is drawn, never a graphicsLayer: LayoutCoordinates carry layer
        // transforms, so a scaled focusable reports bounds 4% larger than its slot and the
        // lazy grid scrolls to reveal the overhang on every left/right press.
        modifier = modifier
            .width(width ?: dimens.posterWidth)
            .onFocusChanged { focus.isFocused = it.isFocused }
            .pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick ?: if (contextMenuItems.isNotEmpty()) { { showMenu = true } } else null,
            )
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.DirectionCenter)
                ) {
                    onClick(); true
                } else false
            }
            .tvFocusGlow(focus, 8.dp.value, TvFocusScale.Standard, acc),
    ) {
        // Every layer here is paid on every card of every rail and grid, each frame the
        // row moves: the placeholder goes once the art covers it, and the scrim is drawn
        // only behind the progress bar it exists for — the title sits below the poster.
        var artShown by remember(imageUrl) { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .drawBehind { if (!artShown) drawRect(placeholder) },
        ) {
            FadeInImage(
                model = imageUrl,
                contentDescription = title,
                onShown = { artShown = true },
                modifier = Modifier.fillMaxSize(),
            )
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.45f)
                        .align(Alignment.BottomCenter)
                        .background(BottomScrim),
                )
            }
            // top-left external badge (e.g. "NEW"), with the offline mark under it
            Column(Modifier.padding(6.dp).align(Alignment.TopStart), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .clip(KobappTheme.shapes.pill)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), KobappTheme.shapes.pill)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(badge.uppercase(), color = colors.textHi, style = KobappTheme.type.caption)
                    }
                }
                if (offline) OfflineBadge(label = null)
            }
            // top-right library status pill
            if (statusBadge != null) {
                val pill = when (statusBadge) {
                    LibraryStatus.InLibrary -> PillStatus.InLibrary
                    LibraryStatus.Wanted -> PillStatus.Wanted
                    LibraryStatus.NotInLibrary -> PillStatus.NotInLibrary
                }
                StatusPill(pill, Modifier.padding(6.dp).align(Alignment.TopEnd))
            }
            if (progress != null) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth().height(3.dp)
                        .clip(KobappTheme.shapes.pill)
                        .background(colors.elev3),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                            .background(acc),
                    )
                }
            }
            if (contextMenuItems.isNotEmpty()) {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    shape = MenuShape,
                    containerColor = colors.elev1,
                ) {
                    contextMenuItems.forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text(label, color = colors.textHi) },
                            onClick = { showMenu = false; action() },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(dimens.s2))
        MarqueeLine(title, KobappTheme.type.bodySm, colors.textHi, focus, Modifier.padding(horizontal = 2.dp))
        // Reserved even when absent: a card without a subtitle is shorter, which makes grid
        // rows ragged and shifts the row's centre as focus moves along it.
        Spacer(Modifier.height(2.dp))
        MarqueeLine(subtitle.orEmpty(), KobappTheme.type.caption, colors.textLo, focus, Modifier.padding(horizontal = 2.dp))
        Spacer(Modifier.height(dimens.s1))
    }
}

@Composable
fun WideCard(
    title: String,
    subtitle: String?,
    imageUrl: String,
    backdropUrl: String? = null,
    progress: Float,
    accentColor: Color? = null,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    onClick: () -> Unit = {},
    offline: Boolean = false,
) {
    val dimens = KobappTheme.dimens
    val colors = KobappTheme.colors
    val acc = accentColor ?: KobappTheme.accent
    val shape = KobappTheme.shapes.card
    val focus = rememberTvFocusState()
    val displayUrl = backdropUrl ?: imageUrl
    val placeholder = remember(colors.elev2, acc) {
        Brush.linearGradient(listOf(lerp(colors.elev2, acc, 0.35f), colors.elev2))
    }

    Column(
        modifier = modifier
            .width(dimens.wideCardWidth)
            .onFocusChanged { focus.isFocused = it.isFocused }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.DirectionCenter)
                ) {
                    onClick(); true
                } else false
            }
            .tvFocusGlow(focus, 10.dp.value, TvFocusScale.Standard, acc),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(shape)
                .background(placeholder),
        ) {
            if (backdropUrl != null) {
                FadeInImage(displayUrl, title, modifier = Modifier.fillMaxSize())
            } else {
                FadeInImage(
                    model = softenedArt(imageUrl),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    alpha = 0.6f,
                )
                FadeInImage(
                    imageUrl, title,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.BottomCenter)
                    .background(BottomScrim),
            )
            if (offline) OfflineBadge(Modifier.padding(6.dp).align(Alignment.TopStart), label = null)
            Column(Modifier.align(Alignment.BottomStart).padding(dimens.s3)) {
                MarqueeLine(title, KobappTheme.type.bodySm, Color.White, focus)
                if (subtitle != null) {
                    MarqueeLine(subtitle, KobappTheme.type.caption, Color.White.copy(alpha = 0.7f), focus)
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                color = acc,
                trackColor = colors.elev3,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
fun BookCover(
    title: String,
    author: String?,
    imageUrl: String?,
    accentColor: Color? = null,
    progress: Float? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = KobappTheme.colors
    val acc = accentColor ?: colors.accBooks
    val focus = rememberTvFocusState()
    val cover = remember(colors.elev3, colors.elev1, acc) {
        Brush.linearGradient(
            listOf(lerp(colors.elev3, acc, 0.30f), lerp(colors.elev1, acc, 0.18f)),
        )
    }
    Column(
        modifier = modifier
            .onFocusChanged { focus.isFocused = it.isFocused }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .tvFocusGlow(focus, 6.dp.value, TvFocusScale.Standard, acc),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3.2f)
                .clip(CoverShape)
                .border(1.dp, colors.line, CoverShape)
                .background(cover),
        ) {
            if (!imageUrl.isNullOrBlank()) {
                FadeInImage(imageUrl, title, modifier = Modifier.fillMaxSize())
            }
            // spine line (left)
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(2.dp).background(Color.Black.copy(alpha = 0.35f)))
            // page-edge stripes (right) — simple double-line + tint
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp).background(Color.Black.copy(alpha = 0.18f)))
            Column(
                Modifier
                    .padding(horizontal = 10.dp, vertical = 14.dp)
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
            ) {
                if (progress != null) {
                    Box(
                        Modifier.fillMaxWidth().height(3.dp).clip(KobappTheme.shapes.pill).background(Color.Black.copy(alpha = 0.35f)),
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(acc))
                    }
                }
            }
        }
        if (imageUrl.isNullOrBlank()) {
            val type = KobappTheme.type
            val titleStyle = remember(type) { type.reader.copy(fontSize = type.bodySm.fontSize) }
            Spacer(Modifier.height(KobappTheme.dimens.s2))
            Text(
                title, color = colors.textHi,
                style = titleStyle,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (author != null) {
                MarqueeLine(author.uppercase(), KobappTheme.type.label, colors.textLo, focus)
            }
        }
    }
}

// Its own restart scope, so focus moving on or off a card recomposes the line that
// scrolls rather than the whole card.
@Composable
private fun MarqueeLine(
    text: String,
    style: TextStyle,
    color: Color,
    focus: TvFocusState,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.tvMarquee(focus.isFocused),
    )
}
