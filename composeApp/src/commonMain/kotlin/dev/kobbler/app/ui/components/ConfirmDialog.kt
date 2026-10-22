package dev.kobbler.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.kobbler.app.theme.KobappTheme

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KobappTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = colors.textHi, style = KobappTheme.type.h3) },
        text = { Text(body, color = colors.textMd, style = KobappTheme.type.body) },
        confirmButton = {
            KobButton(
                onClick = onConfirm,
                style = KobButtonStyle.Accent,
                accent = if (danger) colors.err else colors.accSet,
            ) { Text(confirmLabel, color = colors.bg, style = KobappTheme.type.body) }
        },
        dismissButton = {
            KobButton(
                onClick = onDismiss,
                style = KobButtonStyle.Ghost,
            ) { Text("Cancel", color = colors.textHi, style = KobappTheme.type.body) }
        },
        containerColor = colors.elev1,
        titleContentColor = colors.textHi,
        textContentColor = colors.textMd,
    )
}
