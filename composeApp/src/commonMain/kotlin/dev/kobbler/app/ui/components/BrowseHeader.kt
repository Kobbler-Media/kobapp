package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.app.ui.components.rememberTvFocusState
import dev.kobbler.app.ui.components.tvClickable
import dev.kobbler.app.ui.components.tvFocusGlow

/**
 * Standard browse-screen title row.
 *
 * `Movies` (display-serif) · `2,481 in library · 312 wanted` · spacer ·
 * segmented filter · sort/filter chip buttons.
 *
 * Maps to browse.jsx:TvMoviesBrowse's sticky header (TV/desktop) and
 * mobile.jsx:PhoneMoviesBrowse's PhoneTopBar+filter row (phone — phone
 * passes only `compact = true`).
 */
/**
 * `"1,204 in library"`, from the server's total when it sent one.
 *
 * [loaded] is only the cursor window that happens to be in memory, so it is a last
 * resort — reporting it as the library size makes a filtered list claim the same count
 * as the unfiltered one whenever both fit in one page.
 */
fun countLine(total: Long?, loaded: Int, suffix: String): String =
    "${(total ?: loaded.toLong()).withThousands()} $suffix"

private fun Long.withThousands(): String {
    val digits = toString()
    if (digits.length <= 3) return digits
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

@Composable
fun BrowseHeader(
    title: String,
    accent: Color,
    countLine: String? = null,
    compact: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    secondaryRow: (@Composable () -> Unit)? = null,
) {
    val colors = KobappTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        val gap = if (compact) 10.dp else 20.dp
        // Flow, not Row: a Row measured the title at its full intrinsic width first and
        // left the trailing control so little that its labels wrapped and its pill grew
        // into a blob. Here the control keeps its own size and drops to a second line
        // when the two cannot share one.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(gap),
            itemVerticalAlignment = Alignment.Bottom,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                Text(
                    title,
                    color = colors.textHi,
                    // A page title is a headline, not a display role — M3 reserves display
                    // for hero moments, and the sidebar already says which screen this is.
                    style = if (compact) KobappTheme.type.h2 else KobappTheme.type.h1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (countLine != null) {
                    Text(
                        countLine,
                        color = colors.textLo,
                        style = if (compact) KobappTheme.type.caption else KobappTheme.type.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = if (compact) 2.dp else 8.dp),
                    )
                }
            }
            if (trailing != null) trailing()
        }
        if (secondaryRow != null) {
            Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
            secondaryRow()
        }
    }
}

/**
 * Rounded-pill segmented filter (e.g. All / Wanted / Discover). Pure UI; caller
 * holds the selected index. `accent` colors the active segment's label.
 */
@Composable
fun SegmentedFilter(
    options: List<String>,
    selectedIndex: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    onSelected: (Int) -> Unit,
) {
    val colors = KobappTheme.colors
    val shape = KobappTheme.shapes.pill
    Row(
        modifier = modifier
            .let { if (fillWidth) it.fillMaxWidth() else it }
            // No .clip here: the segments' focus rings draw outside their own
            // bounds and a container clip crops them away.
            .background(colors.elev1, shape)
            .border(1.dp, colors.lineSoft, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, opt ->
            val active = i == selectedIndex
            val mod = if (fillWidth) Modifier.weight(1f) else Modifier
            val focusState = rememberTvFocusState()
            Box(
                modifier = mod
                    .focusKey("seg.${segmentKey(opt, i)}", entryEligible = false)
                    .tvFocusGlow(focusState, cornerRadiusDp = 999f, scale = TvFocusScale.Subtle)
                    .clip(shape)
                    .background(if (active) colors.elev3 else Color.Transparent)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .tvClickable(focusState) { onSelected(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    opt,
                    color = if (active) accent else colors.textMd,
                    style = KobappTheme.type.caption.copy(
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/**
 * Horizontally scrollable chip row used as the secondary filter row on browse
 * screens (genre/format chips, sort/filter dropdowns, etc.). Caller passes the
 * chip composables; this widget just provides the scrolling container.
 */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 6.dp,
    items: List<@Composable () -> Unit>,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items(items) { item -> item() }
    }
}

private fun segmentKey(option: String, index: Int): String {
    val slug = option.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')
    return slug.ifEmpty { index.toString() }
}
