package dev.kobbler.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kobbler.app.platform.FormFactor
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.safeTopPadding
import dev.kobbler.kobfocus.focusKey

val BackButtonSize: Dp = 36.dp

// Where the search bar's row centres its back button against the query field, so leaving
// search for a page that pins its own never moves the button under the thumb.
val BackButtonTop: Dp = 10.dp

fun BackButtonGutter(formFactor: FormFactor, pageGutter: Dp): Dp =
    if (formFactor == FormFactor.Phone) 10.dp else pageGutter

@Composable
fun KobBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = KobappTheme.accent,
) {
    val colors = KobappTheme.colors
    KobButton(
        onClick = onBack,
        style = KobButtonStyle.Ghost,
        size = KobButtonSize.Icon,
        accent = accent,
        // No .clip here — a clip ahead of KobButton's ring modifier crops the focus ring away.
        cornerRadiusDp = BackButtonSize.value / 2f,
        modifier = modifier
            .size(BackButtonSize)
            // Edgeless, so it reads as shade on a poster rather than a plate, but keeps a white
            // arrow legible over pale art.
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.5f),
                        0.6f to Color.Black.copy(alpha = 0.3f),
                        1f to Color.Transparent,
                    ),
                )
            },
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Back",
            tint = colors.textHi,
            modifier = Modifier.size(22.dp),
        )
    }
}

// TV has none: it would float outside the D-pad graph, and the remote's BACK already unwinds.
@Composable
fun KobBackButtonPinned(
    onBack: () -> Unit,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,
) {
    if (formFactor == FormFactor.TV) return
    val dimens = KobappTheme.dimens
    val gutter = if (formFactor == FormFactor.Phone) dimens.pageGutterPhone else dimens.pageGutter
    Box(modifier.safeTopPadding().padding(start = BackButtonGutter(formFactor, gutter), top = BackButtonTop)) {
        KobBackButton(onBack, accent = if (accent != Color.Unspecified) accent else KobappTheme.accent)
    }
}

@Composable
fun KobBackHeader(
    onBack: () -> Unit,
    formFactor: FormFactor,
    pageGutter: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = BackButtonGutter(formFactor, pageGutter), end = pageGutter, top = BackButtonTop),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(KobappTheme.dimens.s2),
    ) {
        if (formFactor != FormFactor.TV) {
            KobBackButton(onBack, modifier = Modifier.focusKey("back", entryEligible = false))
        }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalBrowseTitleLineHeight provides BackButtonSize, content = content)
        }
    }
}
