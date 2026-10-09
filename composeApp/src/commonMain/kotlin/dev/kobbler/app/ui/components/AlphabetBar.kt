package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.kobfocus.focusKey
import dev.kobbler.kobfocus.focusKeyGroup

val AlphabetBarWidth = 44.dp

// The bar's own clip crops anything drawn outside a letter's bounds, and the focus ring
// is drawn outside by design — this inset is what keeps it from being sliced off.
private val LetterInset = 5.dp

/**
 * The bar letter a title belongs under.
 *
 * Mirrors the server's `compute_first_letter` (kobbler api/src/search/mod.rs) so the
 * letter highlighted on entry is the one the list is actually sitting in.
 */
fun alphabetLetterOf(title: String): String {
    val trimmed = title.trim()
    val sortTitle = when {
        trimmed.length > 4 && trimmed.regionMatches(0, "the ", 0, 4, ignoreCase = true) -> trimmed.substring(4)
        trimmed.length > 2 && trimmed.regionMatches(0, "a ", 0, 2, ignoreCase = true) -> trimmed.substring(2)
        trimmed.length > 3 && trimmed.regionMatches(0, "an ", 0, 3, ignoreCase = true) -> trimmed.substring(3)
        else -> trimmed
    }
    val first = sortTitle.firstOrNull() ?: return "#"
    return if (first in 'a'..'z' || first in 'A'..'Z') first.uppercaseChar().toString() else "#"
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AlphabetBar(
    letters: List<String>,
    onLetterClick: (String) -> Unit,
    exitLeft: FocusRequester? = null,
    currentLetter: String? = null,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    val colors = KobappTheme.colors
    // The bar runs the full height of the page, so a plain geometric search hands focus
    // to whichever letter happens to sit level with the grid item — the middle of the
    // alphabet, wherever you came from. Entering as a group instead lets us put focus on
    // the letter the list is already showing.
    val entry = remember { FocusRequester() }
    val entryLetter = currentLetter?.takeIf { it in letters }
    Column(
        modifier = modifier
            .focusKeyGroup("alphabet")
            .focusProperties { onEnter = { if (entryLetter != null) entry.requestFocus() } }
            .focusGroup()
            .width(AlphabetBarWidth)
            .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
            .background(colors.bg.copy(alpha = 0.85f))
            .padding(horizontal = LetterInset, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        letters.forEach { letter ->
            val isCurrent = letter == entryLetter
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .defaultMinSize(minHeight = 22.dp)
                    .focusKey("letter.$letter", entryEligible = false)
                    .then(if (isCurrent) Modifier.focusRequester(entry) else Modifier)
                    // The root key handler is a preview handler: it runs moveFocus(Left)
                    // before this node sees the key, and falls back to the sidebar when
                    // that returns false. Only an explicit target makes moveFocus succeed.
                    .then(if (exitLeft != null) Modifier.focusProperties { left = exitLeft } else Modifier)
                    .tvAutoGlow(cornerRadiusDp = 6f, scale = TvFocusScale.Subtle)
                    .clip(RoundedCornerShape(6.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onLetterClick(letter) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter,
                    style = KobappTheme.type.label,
                    color = if (isCurrent) colors.textHi else colors.textLo,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
