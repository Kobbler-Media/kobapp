package dev.kobbler.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kobbler.app.theme.KobappTheme

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = KobappTheme.colors
    Box(
        modifier = modifier.fillMaxSize().background(colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(48.dp),
        ) {
            Icon(
                icon, null,
                tint = colors.textLo.copy(alpha = 0.5f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = KobappTheme.type.h3,
                color = colors.textLo,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    style = KobappTheme.type.body,
                    color = colors.textLo.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
