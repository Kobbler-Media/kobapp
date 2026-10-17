package dev.kobbler.app.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.safeTopPadding

// The bar owns the top/side insets: it is the first thing the window paints, so
// nothing above it may reserve space or the whole screen shifts down. The page's picture
// runs up under it and the status bar, so it carries its own shade to keep both legible.
@Composable
fun KobPhoneTopBar(
    modifier: Modifier = Modifier,
    onSearchClick: () -> Unit = {},
    onCastClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
) {
    val colors = KobappTheme.colors
    val shade = remember(colors.bg) {
        Brush.verticalGradient(0f to colors.bg.copy(alpha = 0.85f), 0.55f to colors.bg.copy(alpha = 0.55f), 1f to Color.Transparent)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(shade)
            .safeTopPadding()
            .padding(horizontal = 6.dp),
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(Icons.Outlined.Menu, "Menu", tint = colors.textHi)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Outlined.Search, "Search", tint = colors.textHi)
        }
        IconButton(onClick = onCastClick) {
            Icon(Icons.Outlined.Cast, "Cast", tint = colors.textHi)
        }
    }
}
