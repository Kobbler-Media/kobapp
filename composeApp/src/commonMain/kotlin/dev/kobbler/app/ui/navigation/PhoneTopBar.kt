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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.safeTopPadding

// The bar owns the top/side insets: it is the first thing the window paints, so
// nothing above it may reserve space or the whole screen shifts down.
@Composable
fun KobPhoneTopBar(
    modifier: Modifier = Modifier,
    onSearchClick: () -> Unit = {},
    onCastClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
) {
    val colors = KobappTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .safeTopPadding()
            .padding(horizontal = 6.dp),
    ) {
        IconButton(onClick = onMenuClick) {
            Icon(Icons.Outlined.Menu, "Menu", tint = colors.textMd)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Outlined.Search, "Search", tint = colors.textMd)
        }
        IconButton(onClick = onCastClick) {
            Icon(Icons.Outlined.Cast, "Cast", tint = colors.textMd)
        }
    }
}
