package dev.kobbler.app.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistAddCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.theme.LocalFormFactor
import dev.kobbler.app.theme.ratingColor
import dev.kobbler.app.ui.components.FocusEdgeMargin
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonSize
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.StripedArt
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.app.ui.components.softenedArt
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.launch

@Composable
fun DetailHero(
    backdropUrl: String?,
    posterUrl: String?,
    formFactor: FormFactor,
    accentTint: Color = Color.Unspecified,
    accent: Color = Color.Unspecified,
    infoContent: @Composable ColumnScope.() -> Unit,
) {
    val colors = KobappTheme.colors
    val dimens = KobappTheme.dimens
    val posterWidth = when (formFactor) {
        FormFactor.Phone -> 110.dp
        FormFactor.Desktop -> 160.dp
        FormFactor.TV -> 160.dp
    }
    val gutter = when (formFactor) {
        FormFactor.TV -> dimens.pageGutterTv
        FormFactor.Phone -> dimens.pageGutterPhone
        else -> dimens.pageGutter
    }
    val accentColor = if (accent != Color.Unspecified) accent else KobappTheme.accent

    Box(Modifier.fillMaxWidth().clipToBounds()) {
        StripedBackdrop(
            accent = accentColor,
            opacity = 0.32f,
            withRadialOverlay = false,
            modifier = Modifier.matchParentSize(),
        )

        val bgUrl = backdropUrl ?: posterUrl
        if (!bgUrl.isNullOrEmpty()) {
            if (backdropUrl != null) {
                AsyncImage(
                    model = bgUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0.85f },
                )
            } else {
                AsyncImage(
                    model = softenedArt(bgUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0.55f },
                )
            }
        }

        if (accentTint != Color.Unspecified) {
            Box(Modifier.matchParentSize().background(accentTint.copy(alpha = 0.18f)))
        }

        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.4f to colors.bg.copy(alpha = 0.45f),
                    1.0f to colors.bg,
                )
            )
        )

        Row(
            modifier = Modifier
                .safeTopPadding()
                .padding(start = gutter, end = gutter, top = 64.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!posterUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(posterWidth)
                        .aspectRatio(2f / 3f)
                        .clip(KobappTheme.shapes.poster),
                )
            }

            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                content = infoContent,
            )
        }
    }
}

/**
 * The poster block a wide detail page leads with: fixed width, 2:3, striped fallback
 * behind whatever art there is.
 */
@Composable
fun DetailPoster(posterUrl: String?, accent: Color, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(KobappTheme.shapes.poster)
            .border(1.dp, colors.lineSoft, KobappTheme.shapes.poster),
    ) {
        StripedArt(posterUrl, accent, Modifier.fillMaxSize())
    }
}

@Composable
fun DetailBackButton(
    onBack: () -> Unit,
    formFactor: FormFactor,
    accent: Color = Color.Unspecified,
) {
    // TV has no way in: this floats over the content, outside the D-pad graph, and the
    // remote's BACK already unwinds the detail destination.
    if (formFactor == FormFactor.TV) return
    Box(
        modifier = Modifier
            .safeTopPadding()
            .padding(14.dp),
    ) {
        CircleBackButton(onBack, accent = if (accent != Color.Unspecified) accent else KobappTheme.accent)
    }
}

@Composable
fun CircleBackButton(
    onBack: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    KobButton(
        onClick = onBack,
        style = KobButtonStyle.Ghost,
        size = KobButtonSize.Icon,
        accent = accent,
        // Circular, and no .clip here — a clip ahead of KobButton's ring modifier
        // crops the focus ring away and leaves the button looking unfocusable.
        cornerRadiusDp = 20f,
        modifier = modifier
            .size(40.dp)
            .background(colors.bg.copy(alpha = 0.55f), CircleShape)
            .border(1.dp, colors.lineSoft, CircleShape),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Back",
            tint = colors.textHi,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Keeps a detail page pinned to the top while its action block holds focus.
 *
 * Entry focus lands on the action block, and on a 10-foot layout its own bring-into-view
 * scrolls the title off screen before the viewer has pressed anything.
 *
 * The pin is a [BringIntoViewSpec] rather than a scroll-it-back effect. Undoing the
 * scroll afterwards loses twice: the page visibly jumps and returns on every press
 * inside the block, and the request survives being undone, so it lands all at once the
 * moment focus leaves — scrolling the page down on the way to a control that was above
 * it. Refusing the scroll up front means no request is ever queued.
 *
 * Provide [spec] over the scrolling container and put [anchor] on the action block:
 *
 * ```
 * val hold = rememberPageTopHold(pageScroll)
 * CompositionLocalProvider(LocalBringIntoViewSpec provides hold.spec) {
 *     Column(Modifier.verticalScroll(pageScroll)) { ActionStack(modifier = hold.anchor) }
 * }
 * ```
 */
@Stable
class PageTopHold internal constructor(
    private val scroll: ScrollState,
    private val enabled: Boolean,
    private val edgeMarginPx: Float,
) {
    private var focused by mutableStateOf(false)

    /**
     * Height of the bar painted over the top of the scrolling page, in pixels.
     *
     * The bar is an overlay, so the scroller has no idea it is there and brings a
     * control "into view" flush against the viewport's top edge — underneath it. That is
     * what left the season strip half-hidden when focus came back up out of the episode
     * list.
     */
    var overlayTopPx by mutableStateOf(0)

    // A page whose actions sit below a long synopsis cannot show both the title and the
    // focused control, and a control the viewer cannot see is worse than a title scrolled
    // off: OK would fire something invisible. Measured against the focused button rather
    // than the whole block, so a stack that overflows by one button still pins until
    // focus actually reaches that button.
    private var fitsAtTop by mutableStateOf(false)

    internal val holding: Boolean get() = enabled && focused && fitsAtTop

    @OptIn(ExperimentalFoundationApi::class)
    val spec: BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            if (holding) return 0f
            // Measure against the part of the viewport the bar does not cover. The delta
            // returned applies unchanged, since scrolling moves all of the content.
            val inset = overlayTopPx.toFloat().coerceIn(0f, containerSize)
            val start = inset + edgeMarginPx
            val end = containerSize - edgeMarginPx
            if (offset >= start && offset + size <= end) return 0f
            // Centre rather than nudge into view. A row parked flush against the bottom
            // edge has its focus ring and its scale-up — both drawn outside its own
            // bounds — clipped off, and nothing below it to move on to.
            val target = ((containerSize - size) / 2f).coerceAtLeast(start)
            return offset - target
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    val anchor: Modifier = Modifier
        .onFocusedBoundsChanged { coords ->
            // Null arrives between the old target losing focus and the new one gaining
            // it. Acting on it drops the pin for a frame, which is all a queued scroll
            // needs. `focused` already says when focus has genuinely left.
            if (coords != null && coords.isAttached) {
                // Undo the current scroll to get where the focused control rests with the
                // page at the top; the scrolling column starts at the root's origin.
                fitsAtTop = coords.positionInRoot().y + coords.size.height + scroll.value <=
                    coords.findRootCoordinates().size.height
            }
        }
        .onFocusChanged { focused = it.hasFocus }
}

/**
 * TV only: on a pointer platform pinning would fight the wheel whenever a button
 * happens to hold focus.
 */
@Composable
fun rememberPageTopHold(scroll: ScrollState): PageTopHold {
    val enabled = LocalFormFactor.current == FormFactor.TV
    val margin = with(LocalDensity.current) { FocusEdgeMargin.toPx() }
    val hold = remember(scroll, enabled, margin) { PageTopHold(scroll, enabled, margin) }
    // Arriving at the action block from further down the page still has to travel; the
    // spec only refuses scrolls, it cannot produce the one that gets us to the top.
    LaunchedEffect(hold.holding) {
        if (hold.holding && scroll.value != 0) scroll.animateScrollTo(0)
    }
    return hold
}

/**
 * Lets UP/DOWN scroll the page once the focus search has nowhere left to go.
 *
 * A 10-foot page is navigated by moving focus, so whatever sits past the last focusable
 * element — the tail of a synopsis, a credits block — is unreachable: the key does
 * nothing and the text stays off screen. This turns that dead press into a scroll, and
 * only that press. While focus can still move, it moves.
 *
 * The page registers as a focus target so a screen with no controls at all still has
 * somewhere for focus to sit, but never as an entry candidate — landing the viewer on
 * the page instead of its play button would be a worse first frame than the one this
 * fixes.
 */
@Composable
fun Modifier.scrollWhenFocusIsStuck(scroll: ScrollState, step: Dp = 480.dp): Modifier {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { step.toPx() }
    return this
        .focusKey("page", entryEligible = false)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val down = when (event.key) {
                Key.DirectionDown -> true
                Key.DirectionUp -> false
                else -> return@onKeyEvent false
            }
            if (focusManager.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)) {
                return@onKeyEvent true
            }
            val room = if (down) scroll.maxValue - scroll.value else scroll.value
            // Nothing to scroll and nowhere to focus: let the key through, so the root
            // handler can still fall back to the sidebar.
            if (room <= 0) return@onKeyEvent false
            scope.launch { scroll.animateScrollBy(if (down) stepPx else -stepPx) }
            true
        }
        .focusable()
}

/** Reports this element's height to [hold] as the page's pinned overlay. */
fun Modifier.pageTopOverlay(hold: PageTopHold): Modifier =
    onSizeChanged { hold.overlayTopPx = it.height }

/**
 * Fade behind a detail screen's fixed top bar. The bar is painted over scrolling
 * content, so without this the synopsis runs straight through the breadcrumb.
 */
@Composable
fun Modifier.detailTopScrim(): Modifier {
    val bg = KobappTheme.colors.bg
    return this.background(
        Brush.verticalGradient(0f to bg, 0.6f to bg, 1f to Color.Transparent),
    )
}

@Composable
fun InfoLine(label: String, value: String, formFactor: FormFactor) {
    val colors = KobappTheme.colors
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label.uppercase(),
            style = KobappTheme.type.label.copy(letterSpacing = 0.12.em),
            color = colors.textLo,
            modifier = Modifier.width(if (formFactor == FormFactor.TV) 116.dp else 88.dp),
        )
        Text(value, style = KobappTheme.type.bodySm, color = colors.textMd)
    }
}

@Composable
fun MetaText(text: String) {
    Text(
        text,
        style = KobappTheme.type.caption,
        color = KobappTheme.colors.textMd,
    )
}

@Composable
fun MetaDot() {
    Box(
        Modifier
            .size(3.dp)
            .clip(CircleShape)
            .background(KobappTheme.colors.textXlo),
    )
}

@Composable
fun GenreChips(
    genres: List<String>,
    accent: Color = Color.Unspecified,
) {
    val acc = if (accent != Color.Unspecified) accent else KobappTheme.accent
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        genres.take(5).forEach { genre ->
            KobChip(text = genre, style = KobChipStyle.Default, accent = acc)
        }
    }
}

@Composable
fun RatingBadge(rating: Float, modifier: Modifier = Modifier) {
    val color = ratingColor(rating, KobappTheme.colors)
    Row(
        modifier = modifier
            .clip(KobappTheme.shapes.small)
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            formatRating(rating),
            style = KobappTheme.type.caption.copy(
                fontWeight = FontWeight.Bold,
                color = KobappTheme.colors.bg,
            ),
        )
        Icon(
            Icons.Outlined.Star,
            contentDescription = "Rating",
            tint = KobappTheme.colors.bg,
            modifier = Modifier.size(11.dp),
        )
    }
}

fun formatRating(rating: Float): String {
    val rounded = (rating * 10 + 0.5f).toInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}.0" else rounded.toString()
}

// Upright, a phone stacks its detail page. Turned sideways it has the width to set the
// text beside the art, and none of the height a stacked hero spends.
internal fun detailStacked(formFactor: FormFactor, width: androidx.compose.ui.unit.Dp): Boolean =
    formFactor == FormFactor.Phone && width < 600.dp

internal fun gutterFor(formFactor: FormFactor): androidx.compose.ui.unit.Dp = when (formFactor) {
    FormFactor.TV -> 48.dp
    FormFactor.Phone -> 18.dp
    FormFactor.Desktop -> 28.dp
}

// =============================================================================
// "Want to watch" — per-user list
// =============================================================================

/// The whole point of this control is that it costs nothing: no disk, no
/// download, no confirmation. So it is one icon, and the toast carries the
/// meaning rather than a label competing with Play for the eye.
@Composable
fun WatchlistToggle(
    inWatchlist: Boolean,
    accent: Color,
    toast: ToastState,
    onToggle: suspend (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusKeyName: String = "action.watchlist",
) {
    val colors = KobappTheme.colors
    // Optimistic: the server round trip is not worth a spinner on a control whose
    // entire value is feeling instant. A failure reverts and says so.
    var on by remember(inWatchlist) { mutableStateOf(inWatchlist) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    KobButton(
        onClick = {
            if (busy) return@KobButton
            val next = !on
            on = next
            busy = true
            scope.launch {
                runCatching { onToggle(next) }
                    .onSuccess {
                        toast.show(if (next) "Added to your list" else "Removed from your list")
                    }
                    .onFailure {
                        on = !next
                        toast.show("Could not update your list")
                    }
                busy = false
            }
        },
        style = KobButtonStyle.Default,
        accent = accent,
        modifier = modifier.focusKey(focusKeyName),
    ) {
        Icon(
            // NOT the eye: `Visibility` is already the watched toggle on these
            // screens, and one glyph meaning two things is worse than a duller one.
            if (on) Icons.Outlined.PlaylistAddCheck else Icons.Outlined.PlaylistAdd,
            if (on) "On your list" else "Add to your list",
            tint = if (on) accent else colors.textMd,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Stable
class ToastState {
    var message: String? by mutableStateOf(null)
        private set

    private var token = 0

    fun show(text: String) {
        message = text
        token += 1
    }

    internal fun currentToken() = token

    internal fun clear(forToken: Int) {
        if (token == forToken) message = null
    }
}

@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/// Small transient confirmation. Deliberately not a Material Snackbar: those
/// want a host at the Scaffold level and steal focus on TV, and every screen
/// here draws its own chrome.
@Composable
fun ToastOverlay(state: ToastState, modifier: Modifier = Modifier) {
    val colors = KobappTheme.colors
    val text = state.message
    LaunchedEffect(text, state.currentToken()) {
        if (text == null) return@LaunchedEffect
        val t = state.currentToken()
        kotlinx.coroutines.delay(2400)
        state.clear(t)
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .padding(bottom = 32.dp)
                    .clip(KobappTheme.shapes.small)
                    .background(colors.elev2)
                    .border(1.dp, colors.line, KobappTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text.orEmpty(), style = KobappTheme.type.body, color = colors.textHi)
            }
        }
    }
}
