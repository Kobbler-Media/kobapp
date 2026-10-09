package dev.kobbler.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kobbler.app.data.AuthState
import dev.kobbler.app.data.PinLoginMode
import dev.kobbler.app.theme.KobappTheme
import dev.kobbler.app.ui.components.KobButton
import dev.kobbler.app.ui.components.KobButtonSize
import dev.kobbler.app.ui.components.KobButtonStyle
import dev.kobbler.kobfocus.focusKey

@Composable
fun PinLoginScreen(auth: AuthState, mode: PinLoginMode = PinLoginMode.Replace) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    val accent = colors.accHome

    AuthCenteredColumn(accent = accent, maxWidth = 520.dp) {
        BrandMark(accent)

        Spacer(Modifier.height(48.dp))

        // Nothing is drawn until the PIN lands: the server owns its length, so a
        // placeholder row is a guess that visibly re-forms under the reader when the
        // real one arrives.
        val pin = auth.pin
        if (pin != null) {
            Text("PIN", color = colors.textLo, style = type.label)
            Spacer(Modifier.height(12.dp))
            PinCells(pin = pin, accent = accent)
            Spacer(Modifier.height(20.dp))
        }

        StatusLine(
            text = when {
                auth.pin != null -> "Enter this PIN on your Kobbler server"
                auth.pinPolling -> "Generating PIN…"
                mode == PinLoginMode.Relink -> "This profile's key expired - relink to sign back in"
                else -> "Connect this device to your Kobbler server"
            },
            spinning = auth.pinPolling,
        )

        if (auth.pinError != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                auth.pinError!!,
                color = colors.err,
                style = type.caption,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(36.dp))

        if (auth.pinPolling || auth.pin != null) {
            KobButton(
                text = "Cancel",
                style = KobButtonStyle.Ghost,
                modifier = Modifier.focusKey("action.cancel"),
                onClick = { auth.cancelPinLogin() },
            )
        } else {
            KobButton(
                text = if (mode == PinLoginMode.Relink) "Relink profile" else "Link to server",
                style = KobButtonStyle.Accent,
                size = KobButtonSize.Prominent,
                accent = accent,
                modifier = Modifier.focusKey("action.link"),
                onClick = { auth.startPinLogin(mode) },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "You’ll get a code to enter on your Kobbler server’s profile page.",
                color = colors.textLo,
                style = type.caption,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BrandMark(accent: Color) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("K", color = colors.bg, style = type.display.copy(fontSize = type.h1.fontSize))
        }
        Column {
            Text("Kobbler", color = colors.textHi, style = type.display.copy(fontSize = type.h1.fontSize))
            Text(
                "Connect to your server",
                color = colors.textLo,
                style = type.caption,
            )
        }
    }
}

@Composable
private fun PinCells(pin: String, accent: Color) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    // Cells shrink once the pin outgrows a phone's width: 10 cells at the full
    // 52dp would need ~610dp. Pin length is server-side config, so this has to
    // scale rather than assume four.
    val compact = pin.length > 6
    val cellWidth = if (compact) 28.dp else 52.dp
    val cellHeight = if (compact) 42.dp else 64.dp
    val charStyle = if (compact) type.h2.copy(fontSize = type.h2.fontSize * 0.6f) else type.h2
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 10.dp)) {
        pin.forEach { ch ->
            Box(
                modifier = Modifier
                    .size(width = cellWidth, height = cellHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.elev3)
                    .border(1.dp, accent, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(ch.toString(), color = colors.textHi, style = charStyle)
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, spinning: Boolean) {
    val colors = KobappTheme.colors
    val type = KobappTheme.type
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (spinning) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = colors.textLo,
            )
        }
        Text(text, color = colors.textLo, style = type.caption)
    }
}
