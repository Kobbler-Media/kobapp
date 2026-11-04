package dev.kobbler.app.ui.detail

import dev.kobbler.app.ui.kobTopInset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.getFocusedRect
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
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
import dev.kobbler.app.ui.rememberTopChromeShade
import dev.kobbler.app.theme.ratingColor
import dev.kobbler.app.ui.components.FocusEdgeMargin
import dev.kobbler.app.ui.components.KobChip
import dev.kobbler.app.ui.components.KobChipStyle
import dev.kobbler.app.ui.components.StripedArt
import dev.kobbler.app.ui.components.StripedBackdrop
import dev.kobbler.app.ui.components.softenedArt
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.kobfocus.focusKey
import kotlinx.coroutines.launch

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

// A landscape phone has no more height to spare than a stacked one, so its wide head starts
// just as high and the back button floats over the poster's corner the same way.
internal fun detailWideTop(formFactor: FormFactor): Dp =
    if (formFactor == FormFactor.Phone) 8.dp else 72.dp

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
 * val hold = rememberPageTopHold(pageScroll.asPageScroll())
 * CompositionLocalProvider(LocalBringIntoViewSpec provides hold.spec) {
 *     Column(Modifier.verticalScroll(pageScroll)) { ActionStack(modifier = hold.anchor) }
 * }
 * ```
 */
@Stable
class PageTopHold internal constructor(
    private val scroll: PageScroll,
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

    internal var anchorNode: PageTopAnchorNode? = null

    @OptIn(ExperimentalFoundationApi::class)
    val spec: BringIntoViewSpec = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            // Asked on every focus move inside the page, which is exactly when the focused
            // control can change: measure it now rather than tracking its bounds.
            anchorNode?.let { fitsAtTop = it.focusedFitsAtTop(scroll.offset) }
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
            // Never more than the page has: the scroller asks again every frame until the
            // distance is covered, so an unreachable one keeps a frame callback armed for as
            // long as the page is open. The page itself is a focus target taller than the
            // viewport, and at the top it always wants to move down to the margin.
            return (offset - target).coerceIn((-scroll.offset).toFloat(), scroll.remaining.toFloat())
        }
    }

    val anchor: Modifier = Modifier then PageTopAnchorElement(this)

    internal fun onAnchorFocus(hasFocus: Boolean) {
        focused = hasFocus
    }
}

private data class PageTopAnchorElement(val hold: PageTopHold) : ModifierNodeElement<PageTopAnchorNode>() {
    override fun create() = PageTopAnchorNode(hold)
    override fun update(node: PageTopAnchorNode) = node.retarget(hold)
}

/** A focus group over the action block that answers where its focused control would sit at the top. */
internal class PageTopAnchorNode(private var hold: PageTopHold) : DelegatingNode() {
    private val group = delegate(
        FocusTargetModifierNode(Focusability.Never) { _, now -> hold.onAnchorFocus(now.hasFocus) },
    )

    fun retarget(next: PageTopHold) {
        if (hold.anchorNode === this) hold.anchorNode = null
        hold = next
        if (isAttached) next.anchorNode = this
    }

    override fun onAttach() {
        hold.anchorNode = this
    }

    override fun onDetach() {
        if (hold.anchorNode === this) hold.anchorNode = null
    }

    fun focusedFitsAtTop(scrollOffset: Int): Boolean {
        val focused = group.getFocusedRect() ?: return false
        val coords = requireLayoutCoordinates()
        // Undo the current scroll to get where the focused control rests with the page at
        // the top; the scrolling column starts at the root's origin.
        return coords.positionInRoot().y + focused.bottom + scrollOffset <= coords.findRootCoordinates().size.height
    }
}

/**
 * TV only: on a pointer platform pinning would fight the wheel whenever a button
 * happens to hold focus.
 */
@Composable
fun rememberPageTopHold(scroll: PageScroll): PageTopHold {
    val enabled = LocalFormFactor.current == FormFactor.TV
    val margin = with(LocalDensity.current) { FocusEdgeMargin.toPx() }
    val hold = remember(scroll, enabled, margin) { PageTopHold(scroll, enabled, margin) }
    // Arriving at the action block from further down the page still has to travel; the
    // spec only refuses scrolls, it cannot produce the one that gets us to the top.
    LaunchedEffect(hold.holding) {
        if (hold.holding && scroll.offset != 0) scroll.scrollToTop()
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
 * fixes. A lazy page turns that off with [asFocusTarget]: its controls register during
 * measure, after the page, and entry focus settles on whatever is registered first.
 */
@Composable
fun Modifier.scrollWhenFocusIsStuck(scroll: PageScroll, step: Dp = 480.dp, asFocusTarget: Boolean = true): Modifier {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { step.toPx() }
    val stuckScroll = Modifier.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val down = when (event.key) {
            Key.DirectionDown -> true
            Key.DirectionUp -> false
            else -> return@onKeyEvent false
        }
        if (focusManager.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)) {
            return@onKeyEvent true
        }
        val room = if (down) scroll.remaining else scroll.offset
        // Nothing to scroll and nowhere to focus: let the key through, so the root
        // handler can still fall back to the sidebar.
        if (room <= 0) return@onKeyEvent false
        scope.launch { scroll.scrollBy(if (down) stepPx else -stepPx) }
        true
    }
    return if (asFocusTarget) focusKey("page", entryEligible = false).then(stuckScroll).focusable() else then(stuckScroll)
}

/** Reports this element's height to [hold] as the page's pinned overlay. */
fun Modifier.pageTopOverlay(hold: PageTopHold): Modifier =
    onSizeChanged { hold.overlayTopPx = it.height }

/**
 * Fade behind a detail screen's fixed top bar. The bar is painted over scrolling
 * content, so without this the synopsis runs straight through the breadcrumb. It comes in
 * only as content slides under the bar: at rest it greyed out the top of the backdrop.
 * A phone's bar is a lone back button with no breadcrumb to protect, so it takes the tab
 * bar's light shade and the poster stays visible until it leaves the screen.
 */
@Composable
fun Modifier.detailTopScrim(scroll: PageScroll): Modifier {
    val bg = KobappTheme.colors.bg
    val fadeIn = with(LocalDensity.current) { 96.dp.toPx() }
    val phoneShade = rememberTopChromeShade()
    val brush = if (LocalFormFactor.current == FormFactor.Phone) phoneShade
    else remember(bg) { Brush.verticalGradient(0f to bg, 0.6f to bg, 1f to Color.Transparent) }
    return drawBehind {
        drawRect(brush, alpha = (scroll.offset / fadeIn).coerceIn(0f, 1f))
    }
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
fun RatingBadge(rating: Float, outOf: Int, modifier: Modifier = Modifier) {
    val color = ratingColor(rating, outOf, KobappTheme.colors)
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

const val BOOK_RATING_OUT_OF = 5
const val VIDEO_RATING_OUT_OF = 10

fun ratingOutOf(mediaType: String): Int = if (mediaType == "book") BOOK_RATING_OUT_OF else VIDEO_RATING_OUT_OF

fun formatRatingOutOf(rating: Float, outOf: Int): String = "${formatRating(rating)} / $outOf"

// Upright, a phone stacks its detail page. Turned sideways it has the width to set the
// text beside the art, and none of the height a stacked hero spends.
internal fun detailStacked(formFactor: FormFactor, width: androidx.compose.ui.unit.Dp): Boolean =
    formFactor == FormFactor.Phone && width < 600.dp

// Stacked, the back button floats over the art's corner instead of taking a header band
// of its own, which on a phone was most of a screen's worth of nothing above the poster.
internal val detailStackedTop: androidx.compose.ui.unit.Dp
    @Composable get() = kobTopInset + 8.dp

// Beside the poster, a third column leaves the synopsis and actions a strip a few words wide
// on anything short of a large window — a TV's 960dp included.
internal val detailThirdColumnMinWidth = 1400.dp

internal fun gutterFor(formFactor: FormFactor): androidx.compose.ui.unit.Dp = when (formFactor) {
    FormFactor.TV -> 48.dp
    FormFactor.Phone -> 18.dp
    FormFactor.Desktop -> 28.dp
}

// =============================================================================
// "Want to watch" — per-user list
// =============================================================================


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
