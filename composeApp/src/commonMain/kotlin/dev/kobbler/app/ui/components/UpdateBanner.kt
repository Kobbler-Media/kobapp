package dev.kobbler.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AppUpdate
import dev.kobbler.app.data.KobupUpdater
import dev.kobbler.app.ui.safeTopPadding

@Composable
fun UpdateBanner(
    update: AppUpdate?,
    modifier: Modifier = Modifier,
) {
    var dismissed by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = update != null && !dismissed,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier,
    ) {
        val u = update ?: return@AnimatedVisibility
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF1B5E20))
                .safeTopPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Outlined.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))

            Text(
                "Update available: v${u.versionName}",
                style = dev.kobbler.app.theme.KobappTheme.type.body,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.width(8.dp))

            val updateFocus = rememberTvFocusState()
            Button(
                onClick = { KobupUpdater.openForUpdate() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1B5E20)),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.tvFocusGlow(updateFocus, cornerRadiusDp = 4f).tvClickable(updateFocus) { KobupUpdater.openForUpdate() },
            ) {
                Text("Update", style = dev.kobbler.app.theme.KobappTheme.type.label, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(4.dp))

            IconButton(
                onClick = { dismissed = true },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Outlined.Close, "Dismiss", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            }
        }
    }
}
